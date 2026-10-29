package ai.resourcepack.addons.gokarts.track;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * {@code /kart track generate}: a whole circuit built where you stand, its
 * start line under your feet and the rest of it forward and to your right.
 *
 * <p>Asphalt nine blocks wide, red and white kerbs, a two-high tyre wall both
 * sides (a kart steps up one block, like a player, so one would not stop it),
 * a chequered line under a gantry, boost pads on the two straightest stretches,
 * two rows of item boxes and a glass viewing box over the infield for whoever
 * is waiting. Everything that is not road, kerb or wall is grass, which slows a
 * kart right down: cutting the infield is a choice, and it costs.
 *
 * <p>Built a slice per tick rather than all at once, so a hundred thousand
 * blocks do not stall the server; the track is only saved, and so only
 * raceable, once the last slice is down.
 */
public final class TrackBuilder {
    /** How many times its drawn size {@link Circuit#DEFAULT_POINTS} is built at. */
    public static final double SCALE = 1.3;
    /** Half the road's width. Nine blocks: eight karts start two abreast. */
    public static final double HALF = 4.5;
    public static final double KERB = 1.5;
    public static final double WALL = 1.0;
    /** Grass beyond the walls. */
    public static final double MARGIN = 5;
    /** Air cleared above the road. */
    public static final int CLEAR = 14;
    /** Where the start line goes on the default circuit, before scaling: two thirds of the way up the main straight. */
    private static final double[] START = {0, -6};
    /** The viewing box over the infield, before scaling, and how high its floor is. */
    private static final double[] LOBBY = {11, -10};
    private static final int LOBBY_HEIGHT = 10;
    /** Where along the loop the two rows of item boxes are, as fractions of a lap from the line. */
    private static final double[] BOX_ROWS = {0.30, 0.66};
    private static final double CHECKPOINT_EVERY = 16;
    private static final int COLUMNS_PER_TICK = 160;

    public static final Material ROAD = Material.GRAY_CONCRETE;
    public static final Material PAD = Material.MAGENTA_GLAZED_TERRACOTTA;

    private TrackBuilder() {
    }

    /** What a generated track measures, side to side and front to back, in blocks: for the warning before it is built. */
    public static int[] footprint() {
        Circuit c = Circuit.through(Circuit.DEFAULT_POINTS, SCALE);
        double edge = HALF + KERB + WALL + MARGIN;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (Circuit.Sample s : c.samples()) {
            minU = Math.min(minU, s.u());
            maxU = Math.max(maxU, s.u());
            minV = Math.min(minV, s.v());
            maxV = Math.max(maxV, s.v());
        }
        return new int[]{(int) Math.ceil(maxU - minU + 2 * edge), (int) Math.ceil(maxV - minV + 2 * edge), (int) Math.round(c.length())};
    }

    /**
     * Builds the circuit at {@code where} into {@code track} (which is
     * cleared first), telling {@code progress} as it goes and running
     * {@code done} when the last block is down.
     */
    public static void build(Plugin plugin, Track track, Location where, Consumer<Integer> progress, Runnable done) {
        World w = where.getWorld();
        BlockFace facing = cardinal(where.getYaw());
        Frame f = new Frame(where.getBlockX() + 0.5, where.getBlockY(), where.getBlockZ() + 0.5, facing);
        Circuit c = Circuit.through(Circuit.DEFAULT_POINTS, SCALE);
        Circuit.Sample line = c.nearestTo(START[0] * SCALE, START[1] * SCALE);
        f.anchor(line.u(), line.v());
        double s0 = line.s();

        List<Double> boxRows = new ArrayList<>();
        for (double frac : BOX_ROWS) boxRows.add(s0 + frac * c.length());
        List<Double> avoid = new ArrayList<>(boxRows);
        avoid.add(s0);
        List<Double> pads = c.straightest(2, 30, avoid);

        double edge = HALF + KERB + WALL + MARGIN;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (Circuit.Sample s : c.samples()) {
            minU = Math.min(minU, s.u());
            maxU = Math.max(maxU, s.u());
            minV = Math.min(minV, s.v());
            maxV = Math.max(maxV, s.v());
        }
        // The four corners of the local box, in world blocks.
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (double u : new double[]{minU - edge, maxU + edge}) {
            for (double v : new double[]{minV - edge, maxV + edge}) {
                double[] xz = f.world(u, v);
                x0 = Math.min(x0, (int) Math.floor(xz[0]));
                x1 = Math.max(x1, (int) Math.ceil(xz[0]));
                z0 = Math.min(z0, (int) Math.floor(xz[1]));
                z1 = Math.max(z1, (int) Math.ceil(xz[1]));
            }
        }
        final int fx0 = x0, fx1 = x1, fz0 = z0, fz1 = z1;
        final double bu0 = minU - edge, bu1 = maxU + edge, bv0 = minV - edge, bv1 = maxV + edge;
        final int total = (x1 - x0 + 1) * (z1 - z0 + 1);
        final int y0 = f.y;

        new BukkitRunnable() {
            int x = fx0, z = fz0, doneCount = 0, lastPercent = -1;

            @Override
            public void run() {
                for (int n = 0; n < COLUMNS_PER_TICK; n++) {
                    if (x > fx1) {
                        cancel();
                        finish(plugin, track, w, f, c, s0, boxRows);
                        done.run();
                        return;
                    }
                    double[] uv = f.local(x + 0.5, z + 0.5);
                    if (uv[0] >= bu0 && uv[0] <= bu1 && uv[1] >= bv0 && uv[1] <= bv1) {
                        column(w, x, z, y0, uv[0], uv[1], c, s0, pads, f);
                    }
                    doneCount++;
                    if (++z > fz1) {
                        z = fz0;
                        x++;
                    }
                }
                int percent = doneCount * 100 / total;
                if (percent / 10 != lastPercent / 10) {
                    lastPercent = percent;
                    progress.accept(percent);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** One column of the circuit: the floor, what stands on it, and air above. */
    private static void column(World w, int x, int z, int y0, double u, double v, Circuit c, double s0,
                               List<Double> pads, Frame f) {
        Circuit.Near near = c.near(u, v);
        double d = near.distance();
        Circuit.Sample p = near.sample();
        double along = c.ahead(s0, p.s());
        boolean onLine = Math.min(along, c.length() - along) < 1.2;
        // Across the road, positive to the right of the direction of travel.
        double lateral = (u - p.u()) * p.dv() - (v - p.v()) * p.du();

        Material floor, under, base = Material.AIR, top = Material.AIR;
        BlockData floorData = null;
        if (d <= HALF) {
            under = Material.STONE;
            floor = ROAD;
            if (onLine) {
                floor = ((x + z) & 1) == 0 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE;
            } else {
                for (double pad : pads) {
                    double off = c.ahead(pad, p.s());
                    off = Math.min(off, c.length() - off);
                    if (off <= 1.5 && Math.abs(lateral) <= 3) {
                        floor = PAD;
                        floorData = padFacing(f, p);
                    }
                }
            }
        } else if (d <= HALF + KERB) {
            under = Material.STONE;
            floor = ((int) Math.floor(p.s() / 2) & 1) == 0 ? Material.RED_CONCRETE : Material.WHITE_CONCRETE;
        } else if (d <= HALF + KERB + WALL) {
            under = Material.STONE;
            floor = Material.STONE;
            base = Material.BLACK_CONCRETE;
            top = ((int) Math.floor(p.s() / 3) & 1) == 0 ? Material.YELLOW_CONCRETE : Material.BLACK_CONCRETE;
        } else {
            under = Material.DIRT;
            floor = Material.GRASS_BLOCK;
        }
        set(w, x, y0 - 2, z, under, null);
        set(w, x, y0 - 1, z, floor, floorData);
        set(w, x, y0, z, base, null);
        set(w, x, y0 + 1, z, top, null);
        for (int y = y0 + 2; y <= y0 + CLEAR; y++) set(w, x, y, z, Material.AIR, null);
    }

    private static BlockData padFacing(Frame f, Circuit.Sample p) {
        BlockData data = PAD.createBlockData();
        if (data instanceof Directional dir) {
            double[] d = f.direction(p.du(), p.dv());
            // A glazed terracotta's arrow points the opposite way to its facing.
            dir.setFacing(cardinalOf(-d[0], -d[1]));
        }
        return data;
    }

    private static void set(World w, int x, int y, int z, Material m, BlockData data) {
        Block b = w.getBlockAt(x, y, z);
        if (data != null) {
            b.setBlockData(data, false);
        } else if (b.getType() != m) {
            b.setType(m, false);
        }
    }

    /** The gantry, the viewing box, and the track itself. */
    private static void finish(Plugin plugin, Track track, World w, Frame f, Circuit c, double s0, List<Double> boxRows) {
        int y0 = f.y;
        Circuit.Sample line = c.at(s0);
        // The gantry over the line: a white pillar beyond each wall and a chequered beam across.
        double reach = HALF + KERB + WALL + 1;
        for (double lat = -reach; lat <= reach + 0.01; lat += 0.5) {
            double[] xz = f.world(line.u() + line.dv() * lat, line.v() - line.du() * lat);
            int bx = (int) Math.floor(xz[0]), bz = (int) Math.floor(xz[1]);
            boolean pillar = Math.abs(lat) > reach - 0.6;
            if (pillar) {
                for (int y = y0 - 1; y <= y0 + 6; y++) set(w, bx, y, bz, Material.WHITE_CONCRETE, null);
            }
            set(w, bx, y0 + 6, bz, ((bx + bz) & 1) == 0 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE, null);
            set(w, bx, y0 + 7, bz, ((bx + bz) & 1) == 0 ? Material.BLACK_CONCRETE : Material.WHITE_CONCRETE, null);
        }

        // The viewing box: a glass floor and a waist-high glass wall, high over the infield.
        double lu = LOBBY[0] * SCALE, lv = LOBBY[1] * SCALE;
        int ly = y0 + LOBBY_HEIGHT;
        for (int du = -3; du <= 3; du++) {
            for (int dv = -3; dv <= 3; dv++) {
                double[] xz = f.world(lu + du, lv + dv);
                int bx = (int) Math.floor(xz[0]), bz = (int) Math.floor(xz[1]);
                set(w, bx, ly, bz, Material.LIGHT_BLUE_STAINED_GLASS, null);
                boolean rim = Math.abs(du) == 3 || Math.abs(dv) == 3;
                set(w, bx, ly + 1, bz, rim ? Material.GLASS : Material.AIR, null);
                set(w, bx, ly + 2, bz, Material.AIR, null);
                set(w, bx, ly + 3, bz, Material.AIR, null);
            }
        }
        double[] lxz = f.world(lu, lv);
        // Facing the main straight, which is to the box's left.
        double[] toStraight = f.direction(-1, 0);
        Location lobby = new Location(w, Math.floor(lxz[0]) + 0.5, ly + 1, Math.floor(lxz[1]) + 0.5,
                yaw(toStraight[0], toStraight[1]), 15);

        track.world(w);
        track.lobby(lobby);
        track.grid().clear();
        track.checkpoints().clear();
        track.boxes().clear();
        track.floor(y0 - 8);

        // Eight grid slots behind the line, two abreast and staggered.
        double[] dir = f.direction(line.du(), line.dv());
        float gridYaw = yaw(dir[0], dir[1]);
        for (int i = 0; i < 8; i++) {
            int row = i / 2;
            int side = (i % 2 == 0) ? -1 : 1;
            double back = 3 + 3.6 * row + (side > 0 ? 1.8 : 0);
            double lat = side * 2.2;
            double u = line.u() - line.du() * back + line.dv() * lat;
            double v = line.v() - line.dv() * back - line.du() * lat;
            double[] xz = f.world(u, v);
            track.grid().add(new Location(w, xz[0], y0, xz[1], gridYaw, 0));
        }
        for (double s = 0; s < c.length() - CHECKPOINT_EVERY / 2; s += CHECKPOINT_EVERY) {
            Circuit.Sample p = c.at(s0 + s);
            double[] xz = f.world(p.u(), p.v());
            track.checkpoints().add(new Track.Checkpoint(xz[0], y0, xz[1], HALF + KERB + 1));
        }
        for (double s : boxRows) {
            Circuit.Sample p = c.at(s);
            for (double lat : new double[]{-2.6, 0, 2.6}) {
                double[] xz = f.world(p.u() + p.dv() * lat, p.v() - p.du() * lat);
                track.boxes().add(new Vector(xz[0], y0 + 0.9, xz[1]));
            }
        }
        track.enabled(true);
        plugin.getLogger().info("Generated track " + track.id() + ": " + Math.round(c.length()) + " blocks a lap, "
                + track.checkpoints().size() + " checkpoints.");
    }

    // ---- frames ------------------------------------------------------------------

    /** The circuit's frame in the world: its origin, which way is forward and which is right. */
    private static final class Frame {
        final double ox, oz;
        final int y;
        final double fx, fz, rx, rz;
        double u0, v0;

        Frame(double ox, int y, double oz, BlockFace facing) {
            this.ox = ox;
            this.oz = oz;
            this.y = y;
            this.fx = facing.getModX();
            this.fz = facing.getModZ();
            // Facing south (+z), your right hand points west (-x).
            this.rx = -fz;
            this.rz = fx;
        }

        /** Puts local (u, v) on the origin. */
        void anchor(double u, double v) {
            this.u0 = u;
            this.v0 = v;
        }

        double[] world(double u, double v) {
            double a = u - u0, b = v - v0;
            return new double[]{ox + rx * a + fx * b, oz + rz * a + fz * b};
        }

        double[] local(double x, double z) {
            double dx = x - ox, dz = z - oz;
            return new double[]{dx * rx + dz * rz + u0, dx * fx + dz * fz + v0};
        }

        /** A direction in the local frame, as a world direction. */
        double[] direction(double du, double dv) {
            return new double[]{rx * du + fx * dv, rz * du + fz * dv};
        }
    }

    static BlockFace cardinal(float yaw) {
        double rad = Math.toRadians(yaw);
        return cardinalOf(-Math.sin(rad), Math.cos(rad));
    }

    static BlockFace cardinalOf(double dx, double dz) {
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** Whether the server has a world loaded under this name, for the console form. */
    public static World world(String name) {
        return Bukkit.getWorld(name);
    }
}
