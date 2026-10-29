package ai.resourcepack.addons.gokarts.track;

import java.util.ArrayList;
import java.util.List;

/**
 * The racing line of a generated circuit: a closed Catmull-Rom loop through
 * a handful of points, sampled evenly by distance along it.
 *
 * <p>Plain numbers, no Bukkit, in the circuit's own frame: {@code u} is to the
 * right of whoever generated it and {@code v} is forward. {@link TrackBuilder}
 * turns these into blocks.
 */
public final class Circuit {
    /** One point on the line: where it is, how far along the loop, and which way the line runs there. */
    public record Sample(double u, double v, double s, double du, double dv) {
    }

    /** Where a point is relative to the line: how far from it, and the nearest sample. */
    public record Near(double distance, Sample sample) {
    }

    /**
     * The circuit {@code /kart track generate} builds, before scaling: a long
     * start straight, a hairpin, an S through the middle, a long sweeper
     * round the far end and a fast kink back onto the straight. Checked to
     * keep every two separate stretches of road at least twenty blocks apart,
     * which leaves room for both sets of walls.
     */
    public static final double[][] DEFAULT_POINTS = {
            {0, -28}, {0, 8}, {3, 26}, {14, 36}, {28, 34}, {36, 22}, {30, 10}, {22, 0}, {26, -12},
            {42, -18}, {52, -30}, {48, -46}, {32, -54}, {14, -52}, {4, -42},
    };

    /** How far apart the samples are, in blocks. */
    public static final double STEP = 0.25;

    private final List<Sample> samples;
    private final double length;

    private Circuit(List<Sample> samples, double length) {
        this.samples = samples;
        this.length = length;
    }

    /** The loop through {@code points} (each {u, v}), every coordinate times {@code scale}. */
    public static Circuit through(double[][] points, double scale) {
        int n = points.length;
        if (n < 4) throw new IllegalArgumentException("a circuit needs at least four points");
        // Densely first, then resampled evenly: a spline's parameter is not distance.
        List<double[]> dense = new ArrayList<>();
        int per = 64;
        for (int i = 0; i < n; i++) {
            double[] p0 = points[(i - 1 + n) % n], p1 = points[i], p2 = points[(i + 1) % n], p3 = points[(i + 2) % n];
            for (int k = 0; k < per; k++) {
                double t = k / (double) per;
                dense.add(new double[]{cr(p0[0], p1[0], p2[0], p3[0], t) * scale, cr(p0[1], p1[1], p2[1], p3[1], t) * scale});
            }
        }
        double[] at = new double[dense.size() + 1];
        for (int i = 1; i <= dense.size(); i++) {
            double[] a = dense.get(i - 1), b = dense.get(i % dense.size());
            at[i] = at[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        double total = at[dense.size()];
        List<Sample> out = new ArrayList<>();
        int j = 0;
        for (double s = 0; s < total; s += STEP) {
            while (at[j + 1] < s) j++;
            double[] a = dense.get(j), b = dense.get((j + 1) % dense.size());
            double seg = at[j + 1] - at[j];
            double f = seg <= 0 ? 0 : (s - at[j]) / seg;
            double du = b[0] - a[0], dv = b[1] - a[1];
            double len = Math.hypot(du, dv);
            if (len == 0) {
                du = 0;
                dv = 1;
            } else {
                du /= len;
                dv /= len;
            }
            out.add(new Sample(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, s, du, dv));
        }
        return new Circuit(out, total);
    }

    private static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    public double length() {
        return length;
    }

    public List<Sample> samples() {
        return samples;
    }

    /** The sample at distance {@code s} along the loop, wrapping round. */
    public Sample at(double s) {
        double w = ((s % length) + length) % length;
        int i = (int) Math.min(samples.size() - 1, Math.round(w / STEP));
        return samples.get(i);
    }

    /** The sample nearest a point. */
    public Sample nearestTo(double u, double v) {
        Sample best = null;
        double bd = Double.MAX_VALUE;
        for (Sample p : samples) {
            double du = p.u - u, dv = p.v - v;
            double d = du * du + dv * dv;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** How far a point is from the line, and where on it. */
    public Near near(double u, double v) {
        Sample p = nearestTo(u, v);
        return new Near(Math.hypot(p.u - u, p.v - v), p);
    }

    /** Forward distance from {@code from} to {@code to} along the loop, 0 to length. */
    public double ahead(double from, double to) {
        return ((to - from) % length + length) % length;
    }

    /**
     * How sharply the line bends at distance {@code s}: the change of
     * heading, in radians, over {@code window} blocks either side. Zero is a
     * straight.
     */
    public double bend(double s, double window) {
        Sample a = at(s - window), b = at(s + window);
        double cross = a.du * b.dv - a.dv * b.du;
        double dot = a.du * b.du + a.dv * b.dv;
        return Math.abs(Math.atan2(cross, dot));
    }

    /**
     * The {@code n} straightest places on the loop, each at least {@code gap}
     * blocks from the others and from every distance in {@code avoid}: where a
     * generated track puts its boost pads, since a boost into a hairpin is a
     * boost into a wall.
     */
    public List<Double> straightest(int n, double gap, List<Double> avoid) {
        List<Double> taken = new ArrayList<>(avoid);
        List<Double> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            double best = Double.NaN, bestBend = Double.MAX_VALUE;
            for (double s = 0; s < length; s += 2) {
                boolean clear = true;
                for (double t : taken) {
                    if (Math.min(ahead(s, t), ahead(t, s)) < gap) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) continue;
                double b = bend(s, 10);
                if (b < bestBend) {
                    bestBend = b;
                    best = s;
                }
            }
            if (Double.isNaN(best)) break;
            out.add(best);
            taken.add(best);
        }
        return out;
    }

    /**
     * The least distance between two stretches of the line that are more than
     * {@code along} blocks apart along it: how close the road comes to itself.
     */
    public double closestApproach(double along) {
        double best = Double.MAX_VALUE;
        int stride = 4;
        for (int i = 0; i < samples.size(); i += stride) {
            Sample a = samples.get(i);
            for (int j = i + stride; j < samples.size(); j += stride) {
                Sample b = samples.get(j);
                if (Math.min(ahead(a.s, b.s), ahead(b.s, a.s)) <= along) continue;
                best = Math.min(best, Math.hypot(a.u - b.u, a.v - b.v));
            }
        }
        return best;
    }
}
