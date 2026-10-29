package ai.resourcepack.addons.gokarts.track;

import org.bukkit.block.BlockFace;

/**
 * A generated track's frame in the world: where its start line is, which way
 * is forward and which is right. Local (u, v) is the prefab's own coordinates
 * (u right, v forward); the frame puts its start line on the origin.
 *
 * <p>Kept on the track once built ({@link Track#mapFrame}), because the HUD's
 * minimap needs the way back: a kart's world position as the prefab's (u, v).
 */
public final class Frame {
    final double ox, oz;
    final int y;
    final double fx, fz, rx, rz;
    double u0, v0;

    Frame(double ox, int y, double oz, BlockFace facing) {
        this(ox, y, oz, facing.getModX(), facing.getModZ());
    }

    Frame(double ox, int y, double oz, double fx, double fz) {
        this.ox = ox;
        this.oz = oz;
        this.y = y;
        this.fx = fx;
        this.fz = fz;
        // Facing south (+z), your right hand points west (-x).
        this.rx = -fz;
        this.rz = fx;
    }

    /** Puts local (u, v) on the origin. */
    void anchor(double u, double v) {
        this.u0 = u;
        this.v0 = v;
    }

    public double[] world(double u, double v) {
        double a = u - u0, b = v - v0;
        return new double[]{ox + rx * a + fx * b, oz + rz * a + fz * b};
    }

    public double[] local(double x, double z) {
        double dx = x - ox, dz = z - oz;
        return new double[]{dx * rx + dz * rz + u0, dx * fx + dz * fz + v0};
    }

    /** A direction in the local frame, as a world direction. */
    double[] direction(double du, double dv) {
        return new double[]{rx * du + fx * dv, rz * du + fz * dv};
    }

    /** What {@link Track} keeps: enough to rebuild this frame. */
    double[] save() {
        return new double[]{ox, oz, fx, fz, u0, v0};
    }

    static Frame load(double[] f, int y) {
        Frame frame = new Frame(f[0], y, f[1], f[2], f[3]);
        frame.anchor(f[4], f[5]);
        return frame;
    }
}
