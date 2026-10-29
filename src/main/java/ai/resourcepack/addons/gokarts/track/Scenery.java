package ai.resourcepack.addons.gokarts.track;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Random;

/**
 * The things around a prefab track that make it a place: trees and flowers in
 * the meadow, cacti and mesas in the desert, snowy spruces and snowmen on the
 * peak, towers and street lights in the city. Built from blocks, so they are
 * permanent, lit by the game, and as editable as anything else in the world.
 *
 * <p>Everything goes through a {@link Placer}, which only ever fills AIR (and
 * plants): scenery never cuts into a road, whatever its size.
 */
final class Scenery {
    /** Sets a block if the spot is free. */
    interface Placer {
        void put(int x, int y, int z, BlockData data);

        default void put(int x, int y, int z, Material m) {
            put(x, y, z, m.createBlockData());
        }
    }

    private Scenery() {
    }

    static BlockData data(String s) {
        return Bukkit.createBlockData(s);
    }

    /** How far from any road a decoration of this theme needs, beyond the walls, so it never overhangs one. */
    static int clearance(Theme theme, int kind) {
        return switch (theme) {
            case NEON -> kind == 0 ? 9 : 3;
            case DESERT -> kind == 0 ? 8 : 2;
            default -> 4;
        };
    }

    /**
     * One decoration at a ground position (y is the first block above the
     * ground), chosen by {@code rnd}. {@code room} is how far the nearest road's
     * wall is, so the big pieces only go where they fit.
     */
    static void decorate(Theme theme, Placer p, int x, int y, int z, Random rnd, double room) {
        switch (theme) {
            case MEADOW -> meadow(p, x, y, z, rnd);
            case DESERT -> desert(p, x, y, z, rnd, room);
            case FROST -> frost(p, x, y, z, rnd);
            case NEON -> neon(p, x, y, z, rnd, room);
        }
    }

    // ---- meadow -------------------------------------------------------------------

    private static void meadow(Placer p, int x, int y, int z, Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.32) {
            tree(p, x, y, z, rnd, rnd.nextBoolean() ? "oak" : "birch", 4 + rnd.nextInt(3));
        } else if (r < 0.45) {
            bush(p, x, y, z, rnd, "oak");
        } else if (r < 0.50) {
            p.put(x, y, z, data("minecraft:hay_block[axis=y]"));
            if (rnd.nextBoolean()) p.put(x + 1, y, z, data("minecraft:hay_block[axis=x]"));
            if (rnd.nextInt(3) == 0) p.put(x, y + 1, z, data("minecraft:hay_block[axis=z]"));
        } else {
            String[] flowers = {"dandelion", "poppy", "cornflower", "oxeye_daisy", "allium", "azure_bluet", "red_tulip", "orange_tulip"};
            String f = flowers[rnd.nextInt(flowers.length)];
            for (int i = 0; i < 6; i++) {
                p.put(x + rnd.nextInt(5) - 2, y, z + rnd.nextInt(5) - 2, data("minecraft:" + (rnd.nextInt(3) == 0 ? "short_grass" : f)));
            }
        }
    }

    private static void tree(Placer p, int x, int y, int z, Random rnd, String wood, int height) {
        for (int i = 0; i < height; i++) p.put(x, y + i, z, data("minecraft:" + wood + "_log[axis=y]"));
        BlockData leaves = data("minecraft:" + wood + "_leaves[persistent=true]");
        int top = y + height;
        for (int dy = -2; dy <= 1; dy++) {
            int r = dy >= 0 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && (dy == 1 || rnd.nextBoolean())) continue;
                    p.put(x + dx, top + dy, z + dz, leaves);
                }
            }
        }
    }

    private static void bush(Placer p, int x, int y, int z, Random rnd, String wood) {
        BlockData leaves = data("minecraft:" + wood + "_leaves[persistent=true]");
        p.put(x, y, z, leaves);
        p.put(x + 1, y, z, leaves);
        p.put(x, y, z + 1, leaves);
        if (rnd.nextBoolean()) p.put(x, y + 1, z, leaves);
    }

    // ---- desert ----------------------------------------------------------------------

    private static void desert(Placer p, int x, int y, int z, Random rnd, double room) {
        double r = rnd.nextDouble();
        if (r < 0.10 && room > 14) {
            mesa(p, x, y, z, rnd);
        } else if (r < 0.40) {
            int h = 2 + rnd.nextInt(3);
            for (int i = 0; i < h; i++) p.put(x, y + i, z, Material.CACTUS);
        } else if (r < 0.58) {
            p.put(x, y, z, Material.DEAD_BUSH);
        } else if (r < 0.66) {
            // A broken sandstone column.
            int h = 2 + rnd.nextInt(4);
            for (int i = 0; i < h; i++) p.put(x, y + i, z, i == h - 1 ? Material.CHISELED_SANDSTONE : Material.CUT_SANDSTONE);
            if (rnd.nextBoolean()) p.put(x + 1, y, z, data("minecraft:sandstone_slab[type=bottom]"));
        } else if (r < 0.70) {
            p.put(x, y, z, data("minecraft:bone_block[axis=x]"));
            p.put(x + 1, y, z, data("minecraft:bone_block[axis=x]"));
        }
    }

    /** A flat-topped terracotta mesa, banded like the real ones. */
    private static void mesa(Placer p, int x, int y, int z, Random rnd) {
        int radius = 3 + rnd.nextInt(3);
        int height = 6 + rnd.nextInt(8);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > radius + 0.3) continue;
                int h = d > radius - 1 ? height - 2 - rnd.nextInt(2) : height;
                for (int i = 0; i < h; i++) p.put(x + dx, y + i, z + dz, Theme.terracotta(y + i));
                p.put(x + dx, y + h, z + dz, Material.RED_SAND);
            }
        }
    }

    // ---- frost -----------------------------------------------------------------------

    private static void frost(Placer p, int x, int y, int z, Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.45) {
            spruce(p, x, y, z, rnd, 5 + rnd.nextInt(5));
        } else if (r < 0.52) {
            // A snowman, facing a random way.
            p.put(x, y, z, Material.SNOW_BLOCK);
            p.put(x, y + 1, z, Material.SNOW_BLOCK);
            String[] faces = {"north", "south", "east", "west"};
            p.put(x, y + 2, z, data("minecraft:carved_pumpkin[facing=" + faces[rnd.nextInt(4)] + "]"));
        } else if (r < 0.62) {
            // An ice spire.
            int h = 3 + rnd.nextInt(6);
            for (int i = 0; i < h; i++) p.put(x, y + i, z, i < h - 2 ? Material.PACKED_ICE : Material.BLUE_ICE);
            if (h > 5) {
                p.put(x + 1, y, z, Material.PACKED_ICE);
                p.put(x, y, z + 1, Material.PACKED_ICE);
            }
        } else if (r < 0.80) {
            p.put(x, y, z, data("minecraft:snow[layers=" + (1 + rnd.nextInt(3)) + "]"));
        }
    }

    private static void spruce(Placer p, int x, int y, int z, Random rnd, int height) {
        for (int i = 0; i < height; i++) p.put(x, y + i, z, data("minecraft:spruce_log[axis=y]"));
        BlockData leaves = data("minecraft:spruce_leaves[persistent=true]");
        for (int i = 2; i <= height; i++) {
            int r = (height - i) % 2 == 0 ? 1 : 2;
            if (i > height - 2) r = i == height ? 0 : 1;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > r + 1) continue;
                    if (dx == 0 && dz == 0 && i < height) continue;
                    p.put(x + dx, y + i, z + dz, leaves);
                    p.put(x + dx, y + i + 1, z + dz, data("minecraft:snow[layers=1]"));
                }
            }
        }
        p.put(x, y + height + 1, z, leaves);
    }

    // ---- neon --------------------------------------------------------------------------

    private static void neon(Placer p, int x, int y, int z, Random rnd, double room) {
        double r = rnd.nextDouble();
        if (r < 0.30 && room > 14) {
            tower(p, x, y, z, rnd);
        } else if (r < 0.55) {
            // A street light: a black pole and a lantern head.
            for (int i = 0; i < 5; i++) p.put(x, y + i, z, data("minecraft:polished_blackstone_wall"));
            p.put(x, y + 5, z, Material.SEA_LANTERN);
        } else if (r < 0.65) {
            billboard(p, x, y, z, rnd);
        }
    }

    /** A tower block with a grid of windows, some lit, and a light on the roof. */
    private static void tower(Placer p, int x, int y, int z, Random rnd) {
        int w = 2 + rnd.nextInt(3);
        int d = 2 + rnd.nextInt(3);
        int h = 14 + rnd.nextInt(26);
        Material shell = rnd.nextBoolean() ? Material.BLACK_CONCRETE : Material.GRAY_CONCRETE;
        Material glow = switch (rnd.nextInt(3)) {
            case 0 -> Material.YELLOW_STAINED_GLASS;
            case 1 -> Material.CYAN_STAINED_GLASS;
            default -> Material.MAGENTA_STAINED_GLASS;
        };
        for (int dy = 0; dy < h; dy++) {
            for (int dx = -w; dx <= w; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    boolean edge = Math.abs(dx) == w || Math.abs(dz) == d;
                    boolean corner = Math.abs(dx) == w && Math.abs(dz) == d;
                    Material m;
                    if (!edge) {
                        // A lit core behind the windows, so the glass glows.
                        m = dy % 3 == 1 && rnd.nextInt(3) > 0 ? Material.SEA_LANTERN : shell;
                    } else if (!corner && dy % 3 == 1 && dy < h - 1) {
                        m = glow;
                    } else {
                        m = shell;
                    }
                    p.put(x + dx, y + dy, z + dz, m);
                }
            }
        }
        p.put(x, y + h, z, Material.SEA_LANTERN);
        p.put(x, y + h + 1, z, data("minecraft:lightning_rod[facing=up]"));
    }

    private static void billboard(Placer p, int x, int y, int z, Random rnd) {
        for (int i = 0; i < 4; i++) p.put(x, y + i, z, data("minecraft:polished_blackstone_wall"));
        Material[] panels = {Material.MAGENTA_WOOL, Material.CYAN_WOOL, Material.LIME_WOOL, Material.YELLOW_WOOL, Material.PINK_WOOL};
        Material a = panels[rnd.nextInt(panels.length)], b = panels[rnd.nextInt(panels.length)];
        boolean alongX = rnd.nextBoolean();
        for (int i = -2; i <= 2; i++) {
            for (int j = 0; j < 3; j++) {
                Material m = (i + j) % 2 == 0 ? a : b;
                if (alongX) p.put(x + i, y + 4 + j, z, m);
                else p.put(x, y + 4 + j, z + i, m);
            }
        }
        if (alongX) p.put(x, y + 7, z, Material.SEA_LANTERN);
        else p.put(x, y + 7, z, Material.SEA_LANTERN);
    }
}
