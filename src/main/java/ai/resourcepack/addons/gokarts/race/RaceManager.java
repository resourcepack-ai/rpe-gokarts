package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.addons.gokarts.track.Track;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One {@link Race} per track, which player is in which, and the tick that drives them all. */
public final class RaceManager {
    private final GoKartsPlugin plugin;
    private final Map<String, Race> races = new LinkedHashMap<>();
    private final Map<UUID, Race> byPlayer = new HashMap<>();
    /** The race that finished last, kept for /kart results after its lobby has emptied. */
    private Race lastFinished;

    public RaceManager(GoKartsPlugin plugin) {
        this.plugin = plugin;
    }

    public Race race(Track track) {
        return races.computeIfAbsent(track.id(), id -> new Race(plugin, this, track));
    }

    /** The most recent race with results, which may have closed since. */
    public Race lastFinished() {
        return lastFinished;
    }

    void finished(Race race) {
        lastFinished = race;
    }

    public Race existing(String trackId) {
        return races.get(trackId);
    }

    public Race of(Player p) {
        return byPlayer.get(p.getUniqueId());
    }

    public Collection<Race> races() {
        return races.values();
    }

    /** Whether a vehicle is one of a race's karts, which the race ticks itself. */
    boolean drives(UUID chassis) {
        for (Race r : races.values()) if (r.owns(chassis)) return true;
        return false;
    }

    /** Whether a vehicle belongs to a race, and to whom: nobody else may get into it. */
    public boolean isRaceKart(UUID chassis) {
        return drives(chassis);
    }

    /** The track people race on when nobody names one: the first open one that is set up. */
    public Track defaultTrack() {
        for (Track t : plugin.tracks().enabled()) if (t.missing().isEmpty()) return t;
        return null;
    }

    public boolean join(Player p, Track track) {
        var m = plugin.messages();
        if (byPlayer.containsKey(p.getUniqueId())) {
            Text.send(p, m.component("already-in"));
            return false;
        }
        if (track == null) {
            Text.send(p, m.component("no-track"));
            return false;
        }
        if (!track.enabled()) {
            Text.send(p, m.component("track-closed", "track", track.name()));
            return false;
        }
        if (!track.missing().isEmpty()) {
            Text.send(p, m.component("track-not-ready", "track", track.name(), "missing", String.join(", ", track.missing())));
            return false;
        }
        Race race = race(track);
        if (race.size() >= 32) {
            Text.send(p, m.component("full", "track", track.name()));
            return false;
        }
        // In a vehicle of their own: out first, or the lobby teleport drags it along.
        plugin.content().engine().vehicles().of(p).ifPresent(v -> v.eject(p));
        byPlayer.put(p.getUniqueId(), race);
        if (!race.join(p)) {
            byPlayer.remove(p.getUniqueId());
            Text.send(p, Text.error("Your inventory could not be saved, so you were not let in. Tell staff."));
            return false;
        }
        return true;
    }

    public void leave(Player p) {
        Race r = byPlayer.remove(p.getUniqueId());
        if (r != null) r.leave(p);
    }

    /** /kart stop: a race ends (results, then its lobby); a lobby goes home. */
    public void stop(Race race) {
        race.stop();
    }

    /** A track has been closed or deleted: its race ends, its lobby goes home. */
    public void shut(Track track) {
        Race r = existing(track.id());
        if (r != null) r.close();
    }

    /** Forgets a player without telling the race: the race is sending them home itself. */
    void forget(Player p) {
        byPlayer.remove(p.getUniqueId());
    }

    /** A race with nobody left in it. */
    void closed(Race race) {
        races.remove(race.track.id());
        byPlayer.values().removeIf(x -> x == race);
    }

    public void tick() {
        for (Race r : new ArrayList<>(races.values())) {
            try {
                r.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().severe("The race on " + r.track.id() + " failed a tick: " + e);
                e.printStackTrace();
            }
        }
        plugin.driving().tickLoose();
    }

    /** Server going down or plugin reloading: everybody home. */
    public void stopAll() {
        for (Race r : new ArrayList<>(races.values())) r.close();
        races.clear();
        byPlayer.clear();
    }
}
