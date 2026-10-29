package ai.resourcepack.addons.gokarts.track;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Builds a {@link Prefab} into the world, a slice per tick: the ground, every
 * road in it, then the scenery, then the pieces that make it a circuit (the
 * gantry over the line, a grandstand, the viewing deck), and finally the
 * {@link Track} the races are run on.
 *
 * <p>A column at a time, and in each column the roads lowest first. A road's
 * surface is at a half block: a ramp is half-block steps, a full block and a
 * slab, which RP Engine drives at their real heights. A road high above the
 * ground stands on pillars rather than an embankment, and every road keeps
 * what is directly above it clear, so a bridge over another road never fills
 * that road's headroom.
 */
public final class PrefabBuilder {
    static final double KERB = 1.5, WALL = 1.0;
    /** Air kept above a road, and where a tunnel's roof goes. */
    static final int HEADROOM = 5;
    /** Land built round the roads, for the scenery. */
    static final int MARGIN = 26;
    static final int COLUMNS_PER_TICK = 150;
    static final int DECOR_PER_TICK = 80;
    /** How far across the road a jump's hazard (river, canyon...) runs either way. */
    static final double HAZARD_WIDTH = 34;
    /** The grid of samples each road is indexed on, in blocks. */
    static final int CELL = 6;

    public static final Material PAD = Material.MAGENTA_GLAZED_TERRACOTTA;
    public static final Material JUMP = Material.LIME_GLAZED_TERRACOTTA;

    private PrefabBuilder() {
    }

    /** A road found near a column. */
    private record Hit(int road, Road.Sample sample, double d, Prefab.Point point, double surface) {
    }

    /**
     * Builds {@code prefab} at {@code where} (its start line on the block,
     * running the way {@code where} faces) into {@code track}. {@code fresh} is
     * a world made for it, where nothing above the ground needs clearing.
     */
    public static void build(Plugin plugin, Track track, Prefab prefab, Location where, boolean fresh, BlockUndo undo,
                             Consumer<Integer> progress, Runnable done) {
        World w = where.getWorld();
        Theme theme = prefab.theme;
        Road main = prefab.roads.get(0);
        double s0 = prefab.start * main.length();
        Road.Sample line = main.at(s0);
        BlockFace facing = TrackBuilder.cardinal(where.getYaw());
        Frame f = new Frame(where.getBlockX() + 0.5, where.getBlockY(), where.getBlockZ() + 0.5, facing);
        f.anchor(line.u(), line.v());
        int baseY = where.getBlockY();
        double hStart = line.h();
        double outer = prefab.half + KERB + WALL;

        // Each road's samples on a coarse grid, every other one: what a column looks for.
        List<Map<Long, List<Road.Sample>>> index = new ArrayList<>();
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        double minH = Double.MAX_VALUE;
        for (Road r : prefab.roads) {
            Map<Long, List<Road.Sample>> cells = new HashMap<>();
            List<Road.Sample> all = r.samples();
            for (int i = 0; i < all.size(); i += 2) {
                Road.Sample p = all.get(i);
                cells.computeIfAbsent(cell(p.u(), p.v()), k -> new ArrayList<>()).add(p);
                minU = Math.min(minU, p.u());
                maxU = Math.max(maxU, p.u());
                minV = Math.min(minV, p.v());
                maxV = Math.max(maxV, p.v());
                minH = Math.min(minH, p.h());
            }
            index.add(cells);
        }
        double[] lob = prefab.lobby;
        minU = Math.min(minU, lob[0] - 8);
        maxU = Math.max(maxU, lob[0] + 8);
        minV = Math.min(minV, lob[1] - 8);
        maxV = Math.max(maxV, lob[1] + 8);
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (double u : new double[]{minU - MARGIN, maxU + MARGIN}) {
            for (double v : new double[]{minV - MARGIN, maxV + MARGIN}) {
                double[] xz = f.world(u, v);
                x0 = Math.min(x0, (int) Math.floor(xz[0]));
                x1 = Math.max(x1, (int) Math.ceil(xz[0]));
                z0 = Math.min(z0, (int) Math.floor(xz[1]));
                z1 = Math.max(z1, (int) Math.ceil(xz[1]));
            }
        }
        final int bx0 = x0, bx1 = x1, bz0 = z0, bz1 = z1;
        final int width = bx1 - bx0 + 1, depth = bz1 - bz0 + 1;
        final int[] ground = new int[width * depth];
        final double[] room = new double[width * depth];
        final boolean[] hazard = new boolean[width * depth];
        final int total = width * depth;
        final Random noiseSeed = new Random(prefab.id.hashCode());
        final double[] phases = {noiseSeed.nextDouble() * 6.3, noiseSeed.nextDouble() * 6.3, noiseSeed.nextDouble() * 6.3,
                noiseSeed.nextDouble() * 6.3};
        Context ctx = new Context(w, prefab, theme, f, baseY, hStart, outer, index, undo, fresh, phases);

        new BukkitRunnable() {
            int stage = 0, x = bx0, z = bz0, count = 0, lastTenth = -1;
            final List<int[]> decor = new ArrayList<>();
            int decorAt = 0;

            @Override
            public void run() {
                if (stage == 0) {
                    for (int n = 0; n < COLUMNS_PER_TICK; n++) {
                        if (x > bx1) {
                            stage = 1;
                            decor.addAll(candidates(ctx, bx0, bz0, width, depth, ground, room, hazard));
                            return;
                        }
                        int i = (x - bx0) * depth + (z - bz0);
                        ctx.column(x, z, i, ground, room, hazard);
                        count++;
                        if (++z > bz1) {
                            z = bz0;
                            x++;
                        }
                    }
                    int tenth = count * 9 / total;
                    if (tenth != lastTenth) {
                        lastTenth = tenth;
                        progress.accept(tenth * 10);
                    }
                } else if (stage == 1) {
                    Random rnd = new Random(prefab.id.hashCode() * 31L + 7);
                    for (int n = 0; n < DECOR_PER_TICK && decorAt < decor.size(); n++, decorAt++) {
                        int[] c = decor.get(decorAt);
                        rnd.setSeed(prefab.id.hashCode() * 31L + c[0] * 341873128712L + c[2] * 132897987541L);
                        Scenery.decorate(theme, ctx.placer, c[0], c[1], c[2], rnd, c[3]);
                    }
                    if (decorAt >= decor.size()) stage = 2;
                } else {
                    cancel();
                    ctx.finish(plugin, track);
                    progress.accept(100);
                    done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    static long cell(double u, double v) {
        return ((long) Math.floorDiv((int) Math.floor(u), CELL) << 32) ^ (Math.floorDiv((int) Math.floor(v), CELL) & 0xffffffffL);
    }

    /** Where the scenery may go: a jittered grid over the land, away from the roads and the hazards. */
    private static List<int[]> candidates(Context ctx, int bx0, int bz0, int width, int depth, int[] ground, double[] room,
                                          boolean[] hazard) {
        List<int[]> out = new ArrayList<>();
        Random rnd = new Random(ctx.prefab.id.hashCode());
        double density = switch (ctx.theme) {
            case MEADOW -> 0.55;
            case DESERT -> 0.30;
            case FROST -> 0.50;
            case NEON -> 0.32;
        };
        int step = ctx.theme == Theme.NEON ? 5 : 3;
        for (int gx = 1; gx < width - 1; gx += step) {
            for (int gz = 1; gz < depth - 1; gz += step) {
                int x = gx + rnd.nextInt(step), z = gz + rnd.nextInt(step);
                if (x >= width || z >= depth) continue;
                if (rnd.nextDouble() > density) continue;
                int i = x * depth + z;
                if (hazard[i] || room[i] < Scenery.clearance(ctx.theme, 1)) continue;
                double[] lobby = ctx.prefab.lobby;
                double[] uv = ctx.frame.local(bx0 + x + 0.5, bz0 + z + 0.5);
                if (Math.hypot(uv[0] - lobby[0], uv[1] - lobby[1]) < 9) continue;
                if (ctx.nearStands(uv[0], uv[1])) continue;
                out.add(new int[]{bx0 + x, ground[i] + 1, bz0 + z, (int) room[i]});
            }
        }
        return out;
    }

    /** Everything a column needs to know, and the writes. */
    private static final class Context {
        final World w;
        final Prefab prefab;
        final Theme theme;
        final Frame frame;
        final int baseY;
        final double hStart, outer;
        final List<Map<Long, List<Road.Sample>>> index;
        final BlockUndo undo;
        final boolean fresh;
        final double[] phases;
        final Scenery.Placer placer;
        /** Which side of the start straight the grandstand is on: away from the infield. */
        final int standSide;

        Context(World w, Prefab prefab, Theme theme, Frame frame, int baseY, double hStart, double outer,
                List<Map<Long, List<Road.Sample>>> index, BlockUndo undo, boolean fresh, double[] phases) {
            this.w = w;
            this.prefab = prefab;
            this.theme = theme;
            this.frame = frame;
            this.baseY = baseY;
            this.hStart = hStart;
            this.outer = outer;
            this.index = index;
            this.undo = undo;
            this.fresh = fresh;
            this.phases = phases;
            this.placer = (x, y, z, data) -> {
                Block b = w.getBlockAt(x, y, z);
                Material m = b.getType();
                if (!m.isAir() && m != Material.SHORT_GRASS && m != Material.SNOW && !m.name().endsWith("_FLOWER")) return;
                if (undo != null) undo.record(b);
                b.setBlockData(data, false);
            };
            Road main = prefab.roads.get(0);
            Road.Sample line = main.at(prefab.start * main.length());
            double lat = (prefab.lobby[0] - line.u()) * line.dv() - (prefab.lobby[1] - line.v()) * line.du();
            this.standSide = lat > 0 ? -1 : 1;
        }

        double y(double h) {
            return baseY + (h - hStart);
        }

        /** The nearest sample of each road to (u, v), within reach blocks; null where a road is further. */
        Road.Sample[] nearest(double u, double v, double reach, double[] dist) {
            Road.Sample[] out = new Road.Sample[index.size()];
            int span = (int) Math.ceil(reach / CELL);
            int cu = Math.floorDiv((int) Math.floor(u), CELL), cv = Math.floorDiv((int) Math.floor(v), CELL);
            for (int r = 0; r < index.size(); r++) {
                double best = reach * reach;
                Map<Long, List<Road.Sample>> cells = index.get(r);
                for (int a = -span; a <= span; a++) {
                    for (int b = -span; b <= span; b++) {
                        List<Road.Sample> list = cells.get(((long) (cu + a) << 32) ^ ((cv + b) & 0xffffffffL));
                        if (list == null) continue;
                        for (Road.Sample p : list) {
                            double du = p.u() - u, dv = p.v() - v;
                            double d = du * du + dv * dv;
                            if (d < best) {
                                best = d;
                                out[r] = p;
                            }
                        }
                    }
                }
                dist[r] = out[r] == null ? Double.MAX_VALUE : Math.sqrt(best);
            }
            return out;
        }

        /** Rolling hills, from a few sine waves: no seed to keep, and the same every time. */
        double hill(double u, double v) {
            double a = Math.sin(u * 0.045 + phases[0]) * Math.cos(v * 0.038 + phases[1]);
            double b = Math.sin(u * 0.11 + v * 0.07 + phases[2]) * 0.4;
            double c = Math.cos(v * 0.09 - u * 0.05 + phases[3]) * 0.3;
            return Math.max(0, (a + b + c + 0.6) / 2.3);
        }

        boolean nearStands(double u, double v) {
            Road main = prefab.roads.get(0);
            Road.Sample line = main.at(prefab.start * main.length());
            double along = (u - line.u()) * line.du() + (v - line.v()) * line.dv();
            double lat = ((u - line.u()) * line.dv() - (v - line.v()) * line.du()) * standSide;
            return along > -30 && along < 10 && lat > outer && lat < outer + 14;
        }

        // ---- one column -----------------------------------------------------------

        void column(int x, int z, int i, int[] ground, double[] room, boolean[] hazardOut) {
            double[] uv = frame.local(x + 0.5, z + 0.5);
            double u = uv[0], v = uv[1];
            double[] dist = new double[index.size()];
            Road.Sample[] near = nearest(u, v, outer + 18, dist);
            double nearestAny = Double.MAX_VALUE;
            for (double d : dist) nearestAny = Math.min(nearestAny, d);

            // The roads at this column, as they really are.
            List<Hit> hits = new ArrayList<>();
            double tunnelRoof = Double.NaN, tunnelD = Double.MAX_VALUE;
            for (int r = 0; r < near.length; r++) {
                if (near[r] == null) continue;
                Prefab.Point pt = prefab.point(r, near[r]);
                if (pt.tunnel() && dist[r] < outer + 6 && dist[r] < tunnelD) {
                    tunnelD = dist[r];
                    tunnelRoof = Math.ceil(Math.round(y(pt.h()) * 2) / 2.0) + HEADROOM;
                }
                if (dist[r] <= outer) hits.add(new Hit(r, near[r], dist[r], pt, y(pt.h())));
            }

            // ---- the ground.
            int groundTop = baseY - 1;
            if (theme.hills > 0) {
                double blend = Prefab.smoothstep((nearestAny - (outer + 3)) / 16);
                groundTop += (int) Math.round(hill(u, v) * theme.hills * blend);
            }
            Theme.Under under = null;
            for (Prefab.Ramp ramp : prefab.ramps) {
                Road r = prefab.roads.get(ramp.on());
                Road.Sample lip = r.at(ramp.lip() * r.length());
                double along = (u - lip.u()) * lip.du() + (v - lip.v()) * lip.dv();
                double lat = (u - lip.u()) * lip.dv() - (v - lip.v()) * lip.du();
                if (along > -0.5 && along < ramp.gap() + 0.5 && Math.abs(lat) < HAZARD_WIDTH) {
                    under = Theme.under(ramp.under());
                    groundTop = baseY - switch (under) {
                        case RIVER -> 4;
                        case CANYON -> 15;
                        case CREVASSE -> 11;
                        case VOID -> 13;
                    };
                }
            }
            if (!Double.isNaN(tunnelRoof)) {
                // A mound over the tunnel, falling away to the sides.
                int mound = (int) tunnelRoof + 1 + (int) Math.round(Math.max(0, outer + 6 - tunnelD) * 0.5);
                groundTop = Math.max(groundTop, mound);
            }
            // A branch on ice crosses a frozen lake: flat, and ice all round it.
            boolean lake = false;
            for (int r = 1; r < near.length; r++) {
                if (near[r] != null && prefab.branches.get(r - 1).ice() && dist[r] <= outer + 14) lake = true;
            }
            if (lake && under == null) groundTop = baseY - 1;
            ground[i] = groundTop;
            room[i] = nearestAny - outer;
            hazardOut[i] = under != null || lake;

            int top = Math.max(groundTop, baseY - 1);
            Random rnd = new Random(x * 3129871L ^ z * 116129781L);
            if (groundTop >= baseY - 1) {
                for (int y = baseY - 2; y <= groundTop; y++) {
                    Material m = y == groundTop ? (lake ? Material.ICE : theme.surface(rnd, y, baseY)) : theme.body(y, baseY);
                    if (!fresh || y >= baseY) set(x, y, z, m, null);
                }
            } else {
                for (int y = groundTop + 1; y <= baseY - 1; y++) set(x, y, z, Material.AIR, null);
                hazardFloor(x, z, groundTop, under, rnd);
            }
            if (!fresh) {
                for (int y = top + 1; y <= baseY + 14; y++) set(x, y, z, Material.AIR, null);
            }

            // ---- where two roads meet (a branch leaving or rejoining the loop), one
            // road's kerb or wall must not stand on the other's surface: an edge is
            // left out where another road at about the same height is more road.
            hits.removeIf(h -> {
                int zone = zone(h.d());
                if (zone == 0 || h.point().gap()) return false;
                for (Hit o : hits) {
                    if (o == h || o.point().gap()) continue;
                    if (Math.abs(o.surface() - h.surface()) < 2.5 && zone(o.d()) < zone) return true;
                }
                return false;
            });

            // ---- the roads, lowest first, each keeping the space over the ones under it.
            hits.sort(Comparator.comparingDouble(Hit::surface));
            List<int[]> reserved = new ArrayList<>();
            for (Hit h : hits) road(x, z, u, v, h, groundTop, reserved);
        }

        /** 0 on the road, 1 on its kerb, 2 on its wall. */
        int zone(double d) {
            return d <= prefab.half ? 0 : d <= prefab.half + KERB ? 1 : 2;
        }

        private void hazardFloor(int x, int z, int floor, Theme.Under under, Random rnd) {
            if (under == null) return;
            switch (under) {
                case RIVER -> {
                    set(x, floor, z, Material.SAND, null);
                    set(x, floor + 1, z, Material.WATER, null);
                    set(x, floor + 2, z, Material.WATER, null);
                }
                case CANYON -> {
                    set(x, floor, z, Material.RED_SAND, null);
                    if (rnd.nextInt(14) == 0) set(x, floor + 1, z, Material.DEAD_BUSH, null);
                }
                case CREVASSE -> {
                    set(x, floor, z, Material.BLUE_ICE, null);
                    set(x, floor + 1, z, Material.WATER, null);
                    set(x, floor + 2, z, Material.WATER, null);
                }
                case VOID -> set(x, floor, z, rnd.nextInt(5) == 0 ? Material.SEA_LANTERN : Material.BLACK_CONCRETE, null);
            }
        }

        private void road(int x, int z, double u, double v, Hit h, int groundTop, List<int[]> reserved) {
            Prefab.Point pt = h.point();
            if (pt.gap()) return;
            Road.Sample p = h.sample();
            double d = h.d();
            double q = Math.round(h.surface() * 2) / 2.0;
            int full = (int) Math.floor(q);
            boolean halfStep = q - full >= 0.5;
            int above = halfStep ? full + 1 : full;
            int roof = (int) Math.ceil(q) + HEADROOM;
            double lateral = (u - p.u()) * p.dv() - (v - p.v()) * p.du();
            int zone = zone(d);
            boolean tunnel = pt.tunnel();
            boolean main = h.road() == 0;

            // The deck.
            Material deck;
            Material slab;
            BlockData facingData = null;
            if (zone == 0) {
                deck = theme.road;
                slab = theme.roadSlab;
                boolean onLine = main && Math.abs(prefab.roads.get(0).offset(prefab.start * prefab.roads.get(0).length(), p.s())) < 1.25;
                if (pt.ice()) {
                    deck = Material.PACKED_ICE;
                    slab = null;
                } else if (onLine && !halfStep) {
                    deck = ((x + z) & 1) == 0 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE;
                } else if (pt.lip() && Math.abs(lateral) <= prefab.half - 0.5) {
                    deck = JUMP;
                    facingData = facing(JUMP, p);
                } else if (onPad(h.road(), p, lateral)) {
                    deck = PAD;
                    facingData = facing(PAD, p);
                } else if (Math.abs(lateral) < 0.55 && ((int) Math.floor(p.s() / 3) & 1) == 0) {
                    deck = theme.line;
                    slab = theme.lineSlab;
                }
            } else if (zone == 1) {
                boolean a = ((int) Math.floor(p.s() / 2) & 1) == 0;
                deck = a ? theme.kerbA : theme.kerbB;
                slab = a ? theme.kerbASlab : theme.kerbBSlab;
            } else {
                deck = tunnel ? theme.tunnelWall : theme.wallBase;
                slab = null;
            }
            if (facingData != null) {
                // A pad or jump is a full block; on a half step it sits a half block proud, which only the ramp's lip ever is.
                set(x, full - 1, z, Material.STONE, null);
                set(x, halfStep ? full : full - 1, z, deck, facingData);
            } else if (halfStep && slab != null) {
                set(x, full - 1, z, deck == theme.line ? theme.road : deck, null);
                set(x, full, z, slab, slab.createBlockData("[type=bottom]"));
            } else {
                set(x, full - 1, z, deck, null);
                if (halfStep) set(x, full, z, deck, null);
            }
            int deckBottom = full - 1;

            // What stands on it: walls, or a tunnel's walls and roof.
            if (zone == 2) {
                if (tunnel) {
                    for (int y = above; y < roof; y++) set(x, y, z, theme.tunnelWall, null);
                    set(x, roof, z, theme.tunnelRoof, null);
                } else {
                    set(x, above, z, theme.wallBase, null);
                    boolean a = ((int) Math.floor(p.s() / 3) & 1) == 0;
                    set(x, above + 1, z, a ? theme.wallTopA : theme.wallTopB, null);
                    for (int y = above + 2; y <= Math.max(groundTop, above + 2); y++) {
                        if (y > above + 2) set(x, y, z, Material.AIR, null);
                    }
                }
            } else {
                int clearTo = Math.max(roof - 1, Math.min(groundTop, roof + 20));
                if (tunnel) clearTo = roof - 1;
                for (int y = above; y <= clearTo; y++) {
                    if (y == above && halfStep) continue;
                    if (!isReserved(reserved, y)) set(x, y, z, Material.AIR, null);
                }
                if (halfStep) {
                    for (int y = above + 1; y <= clearTo; y++) if (!isReserved(reserved, y)) set(x, y, z, Material.AIR, null);
                }
                if (tunnel) {
                    set(x, roof, z, theme.tunnelRoof, null);
                    if (zone == 0 && Math.abs(lateral) < 0.55 && ((int) Math.floor(p.s()) % 8) == 0) {
                        BlockData light = theme.light == Material.LANTERN
                                ? Scenery.data("minecraft:lantern[hanging=true]") : theme.light.createBlockData();
                        set(x, roof - 1, z, theme.light, light);
                    }
                }
            }

            // Under it: an embankment near the ground, pillars above it.
            int gap = deckBottom - 1 - groundTop;
            if (gap > 0) {
                boolean high = gap > 3;
                boolean pillar = zone == 2 && ((int) Math.floor(p.s()) % 7) == 0;
                for (int y = deckBottom - 1; y > groundTop; y--) {
                    if (isReserved(reserved, y)) break;
                    if (!high) set(x, y, z, theme.fill, null);
                    else if (pillar) set(x, y, z, theme.pillar, null);
                }
            }
            reserved.add(new int[]{deckBottom, roof});
        }

        private boolean onPad(int road, Road.Sample p, double lateral) {
            for (Prefab.Spot s : prefab.pads) {
                if (s.on() != road) continue;
                Road r = prefab.roads.get(road);
                if (Math.abs(r.offset(s.frac() * r.length(), p.s())) <= 1.5 && Math.abs(lateral) <= 2.5) return true;
            }
            return false;
        }

        private BlockData facing(Material m, Road.Sample p) {
            BlockData data = m.createBlockData();
            if (data instanceof Directional dir) {
                double[] d = frame.direction(p.du(), p.dv());
                // A glazed terracotta's arrow points the opposite way to its facing.
                dir.setFacing(TrackBuilder.cardinalOf(-d[0], -d[1]));
            }
            return data;
        }

        private static boolean isReserved(List<int[]> reserved, int y) {
            for (int[] r : reserved) if (y >= r[0] && y <= r[1]) return true;
            return false;
        }

        void set(int x, int y, int z, Material m, BlockData data) {
            Block b = w.getBlockAt(x, y, z);
            if (data != null) {
                if (undo != null) undo.record(b);
                b.setBlockData(data, false);
            } else if (b.getType() != m) {
                if (undo != null) undo.record(b);
                b.setType(m, false);
            }
        }

        // ---- the finishing pieces, and the track ---------------------------------

        void finish(Plugin plugin, Track track) {
            Road main = prefab.roads.get(0);
            double s0 = prefab.start * main.length();
            Road.Sample line = main.at(s0);
            int lineY = (int) Math.round(y(prefab.point(0, line).h()));
            gantry(line, lineY);
            stands(line, lineY);
            Location lobby = lobby();

            track.world(w);
            track.lobby(lobby);
            track.grid().clear();
            track.checkpoints().clear();
            track.boxes().clear();
            track.laps(prefab.laps);
            track.prefab(prefab.id);
            track.mapFrame(frame.save(), baseY, hStart);
            double lowest = Double.MAX_VALUE;
            for (Road r : prefab.roads) for (Road.Sample p : r.samples()) lowest = Math.min(lowest, y(p.h()));
            track.floor(lowest - 5);

            // Eight grid slots behind the line, two abreast and staggered, along the road itself.
            for (int i = 0; i < 8; i++) {
                int row = i / 2;
                int side = (i % 2 == 0) ? -1 : 1;
                double back = 3 + 3.6 * row + (side > 0 ? 1.8 : 0);
                Road.Sample p = main.at(s0 - back);
                double lat = side * 2.2;
                double[] xz = frame.world(p.u() + p.dv() * lat, p.v() - p.du() * lat);
                double[] dir = frame.direction(p.du(), p.dv());
                track.grid().add(new Location(w, xz[0], Math.round(y(prefab.point(0, p).h()) * 2) / 2.0, xz[1],
                        TrackBuilder.yaw(dir[0], dir[1]), 0));
            }
            // Gates every 14 blocks from the line, none where a branch stands in for the loop, none in a gap.
            double every = 14;
            for (double s = 0; s < main.length() - every / 2; s += every) {
                Road.Sample p = main.at(s0 + s);
                double frac = p.s() / main.length();
                Prefab.Point pt = prefab.point(0, p);
                if (s > 0 && (prefab.coveredByBranch(frac) || nearGap(p))) continue;
                double[] xz = frame.world(p.u(), p.v());
                track.checkpoints().add(new Track.Checkpoint(xz[0], y(pt.h()), xz[1], prefab.half + KERB + 0.5));
            }
            for (Prefab.Spot spot : prefab.boxes) {
                Road.Sample p = prefab.spot(spot);
                Prefab.Point pt = prefab.point(spot.on(), p);
                for (double lat : new double[]{-2.8, 0, 2.8}) {
                    double[] xz = frame.world(p.u() + p.dv() * lat, p.v() - p.du() * lat);
                    track.boxes().add(new Vector(xz[0], y(pt.h()) + 1.0, xz[1]));
                }
            }
            track.enabled(true);
            if (track.ownWorld()) {
                for (org.bukkit.entity.LivingEntity e : w.getLivingEntities()) {
                    if (!(e instanceof org.bukkit.entity.Player)) e.remove();
                }
                w.setSpawnLocation(lobby);
            }
            plugin.getLogger().info("Built " + prefab.name + " as track " + track.id() + ": " + Math.round(main.length())
                    + " blocks a lap, " + (prefab.roads.size() - 1) + " branch(es), " + track.checkpoints().size() + " gates.");
        }

        private boolean nearGap(Road.Sample p) {
            Road main = prefab.roads.get(0);
            for (double off = -4; off <= 4; off += 1) {
                if (prefab.point(0, main.at(p.s() + off)).gap()) return true;
            }
            return false;
        }

        /** A pillar beyond each wall and a chequered beam across, with lights hanging from it. */
        private void gantry(Road.Sample line, int y) {
            double reach = outer + 0.6;
            for (double lat = -reach; lat <= reach + 0.01; lat += 0.5) {
                double[] xz = frame.world(line.u() + line.dv() * lat, line.v() - line.du() * lat);
                int bx = (int) Math.floor(xz[0]), bz = (int) Math.floor(xz[1]);
                if (Math.abs(lat) > reach - 0.6) {
                    for (int yy = y - 1; yy <= y + 7; yy++) set(bx, yy, bz, theme.pillar, null);
                }
                set(bx, y + 7, bz, ((bx + bz) & 1) == 0 ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE, null);
                set(bx, y + 8, bz, ((bx + bz) & 1) == 0 ? Material.BLACK_CONCRETE : Material.WHITE_CONCRETE, null);
                if (Math.abs(lat) < prefab.half && Math.abs(lat % 3) < 0.25) {
                    set(bx, y + 6, bz, Material.LANTERN, Scenery.data("minecraft:lantern[hanging=true]"));
                }
            }
        }

        /** A grandstand along the outside of the start straight, seats facing the road. */
        private void stands(Road.Sample line, int y) {
            Material[] seats = {Material.RED_WOOL, Material.WHITE_WOOL, Material.BLUE_WOOL, Material.YELLOW_WOOL};
            for (double along = -28; along <= 8; along += 0.5) {
                for (double t = 0; t < 12; t += 0.5) {
                    double lat = (outer + 2 + t) * standSide;
                    double u = line.u() + line.du() * along + line.dv() * lat;
                    double v = line.v() + line.dv() * along - line.du() * lat;
                    double[] xz = frame.world(u, v);
                    int bx = (int) Math.floor(xz[0]), bz = (int) Math.floor(xz[1]);
                    int tier = (int) (t / 1.5);
                    int top = y + tier;
                    for (int yy = y - 1; yy < top; yy++) set(bx, yy, bz, theme.pillar, null);
                    set(bx, top, bz, seats[Math.floorMod((int) Math.floor(along / 3), seats.length)], null);
                    for (int yy = top + 1; yy <= top + 3; yy++) set(bx, yy, bz, Material.AIR, null);
                    if (t > 11) {
                        for (int yy = top + 1; yy <= top + 4; yy++) set(bx, yy, bz, theme.pillar, null);
                    }
                    if (t > 7.5) set(bx, y + 12, bz, theme.tunnelRoof, null);
                }
            }
        }

        /** The viewing deck the lobby waits on, facing the start line. */
        private Location lobby() {
            double[] l = prefab.lobby;
            int y = (int) Math.round(y(l[2]));
            Material floor = switch (theme) {
                case DESERT -> Material.SMOOTH_SANDSTONE;
                case FROST -> Material.LIGHT_BLUE_STAINED_GLASS;
                case NEON -> Material.BLACK_STAINED_GLASS;
                default -> Material.SPRUCE_PLANKS;
            };
            Material rail = switch (theme) {
                case DESERT -> Material.SANDSTONE_WALL;
                case FROST -> Material.PACKED_ICE;
                case NEON -> Material.SEA_LANTERN;
                default -> Material.SPRUCE_FENCE;
            };
            for (int du = -4; du <= 4; du++) {
                for (int dv = -4; dv <= 4; dv++) {
                    double[] xz = frame.world(l[0] + du, l[1] + dv);
                    int bx = (int) Math.floor(xz[0]), bz = (int) Math.floor(xz[1]);
                    set(bx, y, bz, floor, null);
                    boolean rim = Math.abs(du) == 4 || Math.abs(dv) == 4;
                    set(bx, y + 1, bz, rim ? rail : Material.AIR, null);
                    for (int yy = y + 2; yy <= y + 4; yy++) set(bx, yy, bz, Material.AIR, null);
                    if (rim && Math.abs(du) == 4 && Math.abs(dv) == 4) {
                        for (int yy = y - 1; yy > baseY - 2 && w.getBlockAt(bx, yy, bz).getType().isAir(); yy--) {
                            set(bx, yy, bz, theme.pillar, null);
                        }
                        set(bx, y + 2, bz, Material.LANTERN, null);
                    }
                }
            }
            Road main = prefab.roads.get(0);
            Road.Sample line = main.at(prefab.start * main.length());
            double[] at = frame.world(l[0], l[1]);
            double[] to = frame.world(line.u(), line.v());
            return new Location(w, Math.floor(at[0]) + 0.5, y + 1, Math.floor(at[1]) + 0.5,
                    TrackBuilder.yaw(to[0] - at[0], to[1] - at[1]), 10);
        }
    }
}
