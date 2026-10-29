package ai.resourcepack.addons.gokarts.track;

import org.bukkit.Material;

import java.util.Random;

/**
 * What a prefab track is built OF: its road and kerbs and walls, the ground
 * around it, what is under its jumps, and the scenery. Four, as different from
 * each other as a kart game's cups are: a sunny meadow, a desert canyon, a
 * frozen peak and a neon city at night.
 *
 * <p>Every block here has a slab, or is only used where the road is flat:
 * a ramp is built in half-block steps, and RP Engine drives a slab at its
 * real height.
 */
public enum Theme {
    MEADOW("Meadow", 6000, "plains", "minecraft:stone", "minecraft:dirt", "minecraft:grass_block",
            Material.POLISHED_ANDESITE, Material.POLISHED_ANDESITE_SLAB, Material.WHITE_CONCRETE, Material.SMOOTH_QUARTZ_SLAB,
            Material.RED_CONCRETE, Material.RED_NETHER_BRICK_SLAB, Material.WHITE_CONCRETE, Material.SMOOTH_QUARTZ_SLAB,
            Material.BLACK_CONCRETE, Material.RED_CONCRETE, Material.WHITE_CONCRETE,
            Material.GRASS_BLOCK, Material.STONE_BRICKS, Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.STONE_BRICKS,
            Material.LANTERN, 6),
    DESERT("Desert", 6000, "desert", "minecraft:stone", "minecraft:sandstone", "minecraft:sand",
            Material.SMOOTH_RED_SANDSTONE, Material.SMOOTH_RED_SANDSTONE_SLAB, Material.SMOOTH_SANDSTONE, Material.SMOOTH_SANDSTONE_SLAB,
            Material.ORANGE_CONCRETE, Material.CUT_RED_SANDSTONE_SLAB, Material.WHITE_CONCRETE, Material.SMOOTH_QUARTZ_SLAB,
            Material.CHISELED_SANDSTONE, Material.ORANGE_TERRACOTTA, Material.WHITE_TERRACOTTA,
            Material.SAND, Material.SANDSTONE, Material.CUT_SANDSTONE, Material.SANDSTONE, Material.CUT_SANDSTONE,
            Material.LANTERN, 3),
    FROST("Frost", 6000, "snowy_plains", "minecraft:stone", "minecraft:dirt", "minecraft:snow_block",
            Material.POLISHED_DEEPSLATE, Material.POLISHED_DEEPSLATE_SLAB, Material.WHITE_CONCRETE, Material.SMOOTH_QUARTZ_SLAB,
            Material.LIGHT_BLUE_CONCRETE, Material.PRISMARINE_BRICK_SLAB, Material.WHITE_CONCRETE, Material.SMOOTH_QUARTZ_SLAB,
            Material.PACKED_ICE, Material.BLUE_ICE, Material.SNOW_BLOCK,
            Material.SNOW_BLOCK, Material.SNOW_BLOCK, Material.PACKED_ICE, Material.PACKED_ICE, Material.SNOW_BLOCK,
            Material.SEA_LANTERN, 8),
    NEON("Neon", 18000, "plains", "minecraft:stone", "minecraft:stone", "minecraft:gray_concrete",
            Material.POLISHED_BLACKSTONE, Material.POLISHED_BLACKSTONE_SLAB, Material.YELLOW_CONCRETE, Material.SMOOTH_SANDSTONE_SLAB,
            Material.SEA_LANTERN, Material.PRISMARINE_SLAB, Material.MAGENTA_CONCRETE, Material.PURPUR_SLAB,
            Material.BLACK_CONCRETE, Material.SEA_LANTERN, Material.MAGENTA_STAINED_GLASS,
            Material.GRAY_CONCRETE, Material.POLISHED_BLACKSTONE, Material.GRAY_CONCRETE, Material.BLACK_CONCRETE, Material.POLISHED_BLACKSTONE,
            Material.SEA_LANTERN, 0);

    public final String title;
    /** The time of day its own world is held at: noon, or midnight for the city. */
    public final long time;
    /** Its own world's biome, and the flat world's layers under the ground: the ground's colour, and its weather. */
    public final String biome, deepLayer, fillLayer, topLayer;
    public final Material road, roadSlab, line, lineSlab, kerbA, kerbASlab, kerbB, kerbBSlab;
    public final Material wallBase, wallTopA, wallTopB;
    public final Material ground, fill, pillar, tunnelWall, tunnelRoof, light;
    /** How high its hills grow away from the road, in blocks. */
    public final int hills;

    Theme(String title, long time, String biome, String deepLayer, String fillLayer, String topLayer,
          Material road, Material roadSlab, Material line, Material lineSlab,
          Material kerbA, Material kerbASlab, Material kerbB, Material kerbBSlab,
          Material wallBase, Material wallTopA, Material wallTopB,
          Material ground, Material fill, Material pillar, Material tunnelWall, Material tunnelRoof, Material light, int hills) {
        this.title = title;
        this.time = time;
        this.biome = biome;
        this.deepLayer = deepLayer;
        this.fillLayer = fillLayer;
        this.topLayer = topLayer;
        this.road = road;
        this.roadSlab = roadSlab;
        this.line = line;
        this.lineSlab = lineSlab;
        this.kerbA = kerbA;
        this.kerbASlab = kerbASlab;
        this.kerbB = kerbB;
        this.kerbBSlab = kerbBSlab;
        this.wallBase = wallBase;
        this.wallTopA = wallTopA;
        this.wallTopB = wallTopB;
        this.ground = ground;
        this.fill = fill;
        this.pillar = pillar;
        this.tunnelWall = tunnelWall;
        this.tunnelRoof = tunnelRoof;
        this.light = light;
        this.hills = hills;
    }

    /**
     * A superflat world's settings for this theme: deep enough for a canyon to
     * be cut, and topped with the theme's ground, so the land beyond the track
     * already looks like it belongs.
     */
    public String flatSettings() {
        return "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},{\"block\":\"" + deepLayer + "\",\"height\":40},"
                + "{\"block\":\"" + fillLayer + "\",\"height\":3},{\"block\":\"" + topLayer + "\",\"height\":1}],"
                + "\"biome\":\"minecraft:" + biome + "\",\"lakes\":false,\"features\":false,\"structure_overrides\":[]}";
    }

    /** The ground's top block at a column of a hill or a flat, which a theme may vary for colour. */
    public Material surface(Random rnd, int y, int baseY) {
        return switch (this) {
            case DESERT -> y > baseY + 1 ? terracotta(y) : Material.SAND;
            case FROST -> Material.SNOW_BLOCK;
            case NEON -> Material.GRAY_CONCRETE;
            default -> Material.GRASS_BLOCK;
        };
    }

    /** What a hill is made of below its top: the desert's mesas are bands of terracotta. */
    public Material body(int y, int baseY) {
        return switch (this) {
            case DESERT -> terracotta(y);
            case FROST -> y > baseY + 2 ? Material.STONE : Material.DIRT;
            case NEON -> Material.STONE;
            default -> Material.DIRT;
        };
    }

    static Material terracotta(int y) {
        Material[] bands = {Material.TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA,
                Material.WHITE_TERRACOTTA, Material.BROWN_TERRACOTTA, Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA};
        return bands[Math.floorMod(y, bands.length)];
    }

    /** What a jump's gap has under it. */
    public enum Under { RIVER, CANYON, CREVASSE, VOID }

    public static Under under(String name) {
        if (name == null) return Under.VOID;
        return switch (name) {
            case "river" -> Under.RIVER;
            case "canyon" -> Under.CANYON;
            case "crevasse" -> Under.CREVASSE;
            default -> Under.VOID;
        };
    }
}
