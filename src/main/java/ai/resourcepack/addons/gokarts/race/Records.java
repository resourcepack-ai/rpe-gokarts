package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.track.Track;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Each track's records, the fastest lap and the fastest race, with who set
 * them, in {@code plugins/RPEGoKarts/records.yml}. The results screen shows
 * them, and says so when somebody has just broken one.
 */
public final class Records {
    /** A record: who, and how many ticks. */
    public record Entry(String name, long ticks) {
    }

    private final File file;
    private final Logger log;
    private YamlConfiguration yaml = new YamlConfiguration();

    public Records(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    public void load() {
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public Entry lap(String track) {
        return entry(track + ".lap");
    }

    public Entry race(String track) {
        return entry(track + ".race");
    }

    private Entry entry(String path) {
        ConfigurationSection s = yaml.getConfigurationSection(path);
        return s == null ? null : new Entry(s.getString("name", "?"), s.getLong("ticks"));
    }

    /** Takes a race's results in; answers whether a record fell. */
    boolean record(Track track, List<Race.Result> results, String fastestName, long fastestLap) {
        boolean broke = false;
        Entry lap = lap(track.id());
        if (fastestName != null && fastestLap > 0 && (lap == null || fastestLap < lap.ticks())) {
            set(track.id() + ".lap", fastestName, fastestLap);
            broke = true;
        }
        Entry race = race(track.id());
        for (Race.Result r : results) {
            if (r.dnf() || r.ticks() <= 0) continue;
            if (race == null || r.ticks() < race.ticks()) {
                set(track.id() + ".race", r.name(), r.ticks());
                broke = true;
            }
            break;
        }
        if (broke) save();
        return broke;
    }

    private void set(String path, String name, long ticks) {
        yaml.set(path + ".name", name);
        yaml.set(path + ".ticks", ticks);
    }

    private void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            log.severe("Could not save records.yml: " + e.getMessage());
        }
    }
}
