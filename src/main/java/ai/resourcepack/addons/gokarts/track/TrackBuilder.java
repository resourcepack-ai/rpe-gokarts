package ai.resourcepack.addons.gokarts.track;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * Where a generated track goes: the world of its own it gets by default, and
 * the arithmetic of turning a facing into the track's frame. The building is
 * {@link PrefabBuilder}'s.
 */
public final class TrackBuilder {
    private TrackBuilder() {
    }

    /** The name of the world a track is generated into when it gets one of its own. */
    public static String worldName(String trackId) {
        return "gokarts_" + trackId;
    }

    /**
     * A track's own world, loaded or made: empty (the track brings its own
     * ground, an island round the circuit), in its theme's biome, peaceful, held at its theme's time of day and dry, so the
     * circuit is the only thing in it. Called
     * again on every start for each track that has one, because the server
     * only loads the worlds it knows about by itself.
     */
    public static World ownWorld(String name, Theme theme) {
        World w = Bukkit.getWorld(name);
        if (w != null) return w;
        org.bukkit.WorldCreator creator = new org.bukkit.WorldCreator(name).type(org.bukkit.WorldType.FLAT).generateStructures(false);
        // Nothing but the theme's biome: the track is built as an island in it.
        if (theme != null) creator.generatorSettings(theme.flatSettings());
        w = creator.createWorld();
        if (w == null) return null;
        w.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
        w.setTime(theme == null ? 6000 : theme.time);
        w.setStorm(false);
        w.setThundering(false);
        rule(w, org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
        rule(w, org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        rule(w, org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        return w;
    }

    /** A game rule, if this server still has it under this name (several were renamed in 1.21.11). */
    private static <T> void rule(World w, org.bukkit.GameRule<T> rule, T value) {
        try {
            w.setGameRule(rule, value);
        } catch (Throwable renamed) {
            // Not fatal: the world is still a fine place for a track.
        }
    }

    /**
     * Where a world's files are, loaded or not: the loaded world's own folder,
     * or, for one that is not loaded, wherever this server keeps a world of
     * that name - beside the main world on Spigot and older Paper, under the
     * main world's {@code dimensions/minecraft/} on Paper 26 and newer. Null
     * when there is none.
     */
    public static java.io.File worldFolder(String name) {
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) return loaded.getWorldFolder();
        java.io.File root = Bukkit.getWorldContainer();
        java.io.File beside = new java.io.File(root, name);
        if (beside.isDirectory()) return beside;
        String main = Bukkit.getWorlds().isEmpty() ? "world" : Bukkit.getWorlds().get(0).getName();
        java.io.File dimension = new java.io.File(root, main + "/dimensions/minecraft/" + name);
        return dimension.isDirectory() ? dimension : null;
    }

    /** Where an island track's start line is in an empty world. */
    static final int ISLAND_Y = 64;

    /**
     * Where a track goes in its own world: at the origin, the straight running
     * south. On the surface of a world that has one (made before tracks were
     * islands), and at {@link #ISLAND_Y} in an empty one.
     */
    public static Location ownWorldOrigin(World w) {
        int top = w.getHighestBlockYAt(0, 0);
        int y = top <= w.getMinHeight() ? ISLAND_Y : top + 1;
        return new Location(w, 0.5, y, 0.5, 0, 0);
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
