package ai.resourcepack.addons.gokarts.track;

import java.util.ArrayList;
import java.util.List;

/**
 * One road of a prefab track: a Catmull-Rom spline through (u, v, h) points,
 * sampled evenly by distance along it. {@code u} is to the right of whoever
 * generated the track, {@code v} forward, {@code h} up, all in blocks.
 *
 * <p>A loop for the main road; open for a branch, which then runs from its
 * second point to its second-last, the first and last only steering its ends.
 * {@code tools/prefabs.py} holds the same arithmetic to draw the minimaps, so
 * change one and change the other.
 */
public final class Road {
    /** A point on the road: where, how high, how far along, and which way it runs (a unit vector across the ground). */
    public record Sample(double u, double v, double h, double s, double du, double dv) {
    }

    /** How far apart the samples are, in blocks. */
    public static final double STEP = 0.25;
    /** Dense points per control point before resampling. */
    private static final int PER = 48;

    private final List<Sample> samples;
    private final double length;
    private final boolean closed;

    private Road(List<Sample> samples, double length, boolean closed) {
        this.samples = samples;
        this.length = length;
        this.closed = closed;
    }

    public static Road through(List<double[]> points, boolean closed) {
        int n = points.size();
        if (n < 4) throw new IllegalArgumentException("a road needs at least four points");
        List<double[]> dense = new ArrayList<>();
        int first = closed ? 0 : 1, last = closed ? n : n - 2;
        for (int i = first; i < last; i++) {
            double[] p0 = points.get(Math.floorMod(i - 1, n)), p1 = points.get(i % n),
                    p2 = points.get((i + 1) % n), p3 = points.get((i + 2) % n);
            for (int k = 0; k < PER; k++) {
                double t = k / (double) PER;
                dense.add(new double[]{cr(p0[0], p1[0], p2[0], p3[0], t), cr(p0[1], p1[1], p2[1], p3[1], t),
                        cr(p0[2], p1[2], p2[2], p3[2], t)});
            }
        }
        if (!closed) dense.add(points.get(n - 2).clone());
        int count = dense.size();
        int segments = closed ? count : count - 1;
        double[] at = new double[segments + 1];
        for (int i = 1; i <= segments; i++) {
            double[] a = dense.get(i - 1), b = dense.get(i % count);
            at[i] = at[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        double total = at[segments];
        List<Sample> out = new ArrayList<>();
        int j = 0;
        for (double s = 0; s < total - (closed ? 0 : 1e-9); s += STEP) {
            while (at[j + 1] < s) j++;
            double[] a = dense.get(j), b = dense.get((j + 1) % count);
            double seg = at[j + 1] - at[j];
            double f = seg <= 0 ? 0 : (s - at[j]) / seg;
            double du = b[0] - a[0], dv = b[1] - a[1];
            double len = Math.hypot(du, dv);
            if (len == 0) {
                du = 0;
                dv = 1;
                len = 1;
            }
            out.add(new Sample(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, s,
                    du / len, dv / len));
        }
        return new Road(out, total, closed);
    }

    static double cr(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    public List<Sample> samples() {
        return samples;
    }

    public double length() {
        return length;
    }

    public boolean closed() {
        return closed;
    }

    /** The sample at distance {@code s}: wrapping round a loop, held to the ends of an open road. */
    public Sample at(double s) {
        double w = closed ? ((s % length) + length) % length : Math.max(0, Math.min(length, s));
        int i = (int) Math.min(samples.size() - 1, Math.round(w / STEP));
        return samples.get(i);
    }

    /** Distance from {@code from} forward to {@code to}: round the loop on a loop, signed on an open road. */
    public double ahead(double from, double to) {
        if (!closed) return to - from;
        return ((to - from) % length + length) % length;
    }

    /** {@link #ahead}, as the nearer way round on a loop: negative behind, positive in front. */
    public double offset(double from, double to) {
        if (!closed) return to - from;
        double d = ahead(from, to);
        return d > length / 2 ? d - length : d;
    }

    /** How sharply the road bends at {@code s}, in radians of heading over {@code window} blocks either side. */
    public double bend(double s, double window) {
        Sample a = at(s - window), b = at(s + window);
        return Math.abs(Math.atan2(a.du() * b.dv() - a.dv() * b.du(), a.du() * b.du() + a.dv() * b.dv()));
    }
}
