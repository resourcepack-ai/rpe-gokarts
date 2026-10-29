package ai.resourcepack.addons.gokarts.track;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * The last {@code /kart track generate} of each track, so {@code /kart track
 * undo} can take it back: in {@code plugins/RPEGoKarts/undo/}, one
 * {@code <id>.yml} saying what kind of build it was and what the track was
 * before it, and for a build in place a {@code <id>.blocks.gz} of every block
 * it replaced ({@link BlockUndo}).
 *
 * <p>One level, not a history: generating the same track twice and undoing
 * goes back to the first, and a second undo has nothing to do.
 */
public final class TrackUndo {
    /** What one generate did. {@code previous} is the track as it was before, or null when it was new. */
    public record Entry(String trackId, boolean ownWorld, String world, Track previous, int blocks) {
    }

    private final File dir;
    private final Logger log;

    public TrackUndo(File dir, Logger log) {
        this.dir = dir;
        this.log = log;
    }

    private File info(String id) {
        return new File(dir, id + ".yml");
    }

    public File blocks(String id) {
        return new File(dir, id + ".blocks.gz");
    }

    /**
     * Remembers a finished generate. {@code before} is the track's definition
     * as it was (null for a new track); {@code replaced} the blocks a build in
     * place replaced (null for one in its own world). The block file is
     * written straight away, on the calling thread: it is a few megabytes at
     * most, and an undo that was never written is worse than a short pause.
     */
    public void remember(String id, boolean ownWorld, String world, YamlConfiguration before, BlockUndo replaced) {
        dir.mkdirs();
        YamlConfiguration y = new YamlConfiguration();
        y.set("kind", ownWorld ? "world" : "place");
        y.set("world", world);
        y.set("blocks", replaced == null ? 0 : replaced.size());
        if (before != null) y.set("previous", before.getConfigurationSection("previous"));
        try {
            if (replaced != null) replaced.save(blocks(id));
            else blocks(id).delete();
            y.save(info(id));
        } catch (IOException e) {
            log.severe("Could not save the undo for track " + id + ": " + e.getMessage() + ". /kart track undo will not be able to take it back.");
        }
    }

    public Entry get(String id) {
        File f = info(id);
        if (!f.isFile()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection prev = y.getConfigurationSection("previous");
        Track previous = prev == null ? null : Track.load(id, prev);
        return new Entry(id, "world".equals(y.getString("kind")), y.getString("world"), previous, y.getInt("blocks"));
    }

    public BlockUndo loadBlocks(String id) throws IOException {
        return BlockUndo.load(blocks(id));
    }

    public void forget(String id) {
        info(id).delete();
        blocks(id).delete();
    }

    /** A track's definition as it is now, to hand to {@link #remember} before a generate changes it. */
    public static YamlConfiguration snapshot(Track t) {
        if (t == null) return null;
        YamlConfiguration y = new YamlConfiguration();
        t.save(y.createSection("previous"));
        return y;
    }
}
