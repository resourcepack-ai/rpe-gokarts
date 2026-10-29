package ai.resourcepack.addons.gokarts.track;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** Every track, kept in {@code plugins/RPEGoKarts/tracks.yml} and written whenever one changes. */
public final class TrackStore {
    private final File file;
    private final Logger log;
    private final Map<String, Track> tracks = new LinkedHashMap<>();

    public TrackStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        tracks.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("tracks");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                tracks.put(id, Track.load(id, s));
            } catch (RuntimeException e) {
                log.warning("Could not read track " + id + " in tracks.yml: " + e.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of(
                "Go Karts tracks. Written by /kart track; edit with the server stopped,",
                "or run /kart reload after. Checkpoint 0 is the start/finish line:",
                "[x, y, z, radius], in the order a lap passes them."));
        ConfigurationSection root = y.createSection("tracks");
        for (Track t : tracks.values()) t.save(root.createSection(t.id()));
        try {
            y.save(file);
        } catch (IOException e) {
            log.severe("Could not save tracks.yml: " + e.getMessage());
        }
    }

    public static String normalise(String id) {
        return id == null ? null : id.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-z0-9_-]{1,32}");
    }

    public Track get(String id) {
        return tracks.get(normalise(id));
    }

    public Track create(String id) {
        Track t = new Track(normalise(id));
        tracks.put(t.id(), t);
        return t;
    }

    /** Puts a track in, replacing any with its id: an undo bringing back what a generate replaced. */
    public void put(Track t) {
        tracks.put(t.id(), t);
    }

    public void delete(String id) {
        tracks.remove(normalise(id));
    }

    public Collection<Track> all() {
        return tracks.values();
    }

    public List<Track> enabled() {
        List<Track> out = new ArrayList<>();
        for (Track t : tracks.values()) if (t.enabled()) out.add(t);
        return out;
    }
}
