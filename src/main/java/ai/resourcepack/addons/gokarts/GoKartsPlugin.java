package ai.resourcepack.addons.gokarts;

import ai.resourcepack.addons.gokarts.command.KartCommand;
import ai.resourcepack.addons.gokarts.config.Messages;
import ai.resourcepack.addons.gokarts.config.Settings;
import ai.resourcepack.addons.gokarts.content.Content;
import ai.resourcepack.addons.gokarts.race.Driving;
import ai.resourcepack.addons.gokarts.race.KartColour;
import ai.resourcepack.addons.gokarts.race.PlayerSnapshot;
import ai.resourcepack.addons.gokarts.race.RaceListener;
import ai.resourcepack.addons.gokarts.race.RaceManager;
import ai.resourcepack.addons.gokarts.race.Records;
import ai.resourcepack.addons.gokarts.track.Track;
import ai.resourcepack.addons.gokarts.track.Theme;
import ai.resourcepack.addons.gokarts.track.TrackBuilder;
import ai.resourcepack.addons.gokarts.track.TrackStore;
import ai.resourcepack.addons.gokarts.track.TrackUndo;
import ai.resourcepack.addons.gokarts.ui.Results;
import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.AddonContent;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Go karts on RP Engine: a circuit it builds for you, eight karts on a grid,
 * three red lights and green, drift boosts, item boxes, bananas and rockets.
 *
 * <p>Every kart is an RP Engine vehicle, so the driving itself (grip, the
 * handbrake slide, the body rolling into corners, karts bumping each other)
 * is the engine's. This plugin is everything on top: the race, and the tricks
 * that make a kart a kart (see {@code race/Driving}).
 *
 * <p>Everything a player sees is an ordinary RP Engine content folder inside
 * this jar, installed into {@code plugins/RPEngine/content/gokarts/} on
 * enable: the kart in eight paints, rigged in ResourcePack AI Studio with its
 * driver's emotes; the item box, banana, nitro and rocket; the sounds; the
 * HUD ({@code huds/hud.json}) and the results screen
 * ({@code dialogs/results.json}). This plugin plays the race and feeds them
 * values.
 */
public final class GoKartsPlugin extends JavaPlugin {
    /** The oldest engine with everything this plugin calls. */
    static final String MIN_ENGINE = "0.2.40";

    private RPEnginePlugin engine;
    private Content content;
    private Settings settings;
    private Messages messages;
    private TrackStore tracks;
    private PlayerSnapshot snapshots;
    private RaceManager races;
    private Driving driving;
    private Results results;
    private Records records;
    private TrackUndo undo;
    /** Who asked for which colour, kept for as long as the server runs. */
    private final Map<UUID, KartColour> colours = new HashMap<>();
    /** Ticks since enable: Spigot has no current-tick counter of its own. */
    private long ticks;

    /**
     * Whether version {@code have} is {@code need} or newer, compared number by
     * number; anything after a dash is ignored, and a version that is not
     * numbers passes.
     */
    static boolean atLeast(String have, String need) {
        String[] a = have.split("-", 2)[0].split("\\.");
        String[] b = need.split("\\.");
        try {
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                int x = i < a.length ? Integer.parseInt(a[i].trim()) : 0;
                int y = i < b.length ? Integer.parseInt(b[i].trim()) : 0;
                if (x != y) return x > y;
            }
            return true;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    @Override
    public void onEnable() {
        ai.resourcepack.common.banner.StartupBanner banner = ai.resourcepack.common.banner.StartupBanner.begin(this)
                .name("Go Karts")
                .addonOf("RP Engine")
                .tagline("Go-kart racing: drift, boost, bananas and rockets.")
                .docs("https://resourcepack.ai/docs/rp-engine");
        Plugin found = getServer().getPluginManager().getPlugin("RPEngine");
        if (!(found instanceof RPEnginePlugin rp)) {
            getLogger().severe("RP Engine isn't here, and every kart is one of its vehicles. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        engine = rp;
        String have = rp.getDescription().getVersion();
        if (!atLeast(have, MIN_ENGINE)) {
            getLogger().severe("Go Karts needs RP Engine " + MIN_ENGINE + " or newer, and this server has " + have
                    + ". Update it from https://resourcepack.ai/rp-engine and restart. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            if (AddonContent.install(this, engine, Content.NS)) {
                banner.ok("Content", "Installed into RP Engine, which reloaded once");
                engine.reload();
            }
        } catch (Exception e) {
            getLogger().severe("Could not install the gokarts content: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        content = new Content(engine);

        saveDefaultConfig();
        addNewOptions();
        settings = new Settings(getConfig(), getLogger());
        messages = new Messages(getConfig());
        getDataFolder().mkdirs();
        tracks = new TrackStore(new File(getDataFolder(), "tracks.yml"), getLogger());
        tracks.load();
        loadOwnWorlds();
        undo = new TrackUndo(new File(getDataFolder(), "undo"), getLogger());
        records = new Records(new File(getDataFolder(), "records.yml"), getLogger());
        records.load();
        snapshots = new PlayerSnapshot(new File(getDataFolder(), "snapshots"), getLogger());
        driving = new Driving(this);
        races = new RaceManager(this);
        results = new Results(this);

        sweepStrays();
        getServer().getPluginManager().registerEvents(new RaceListener(this), this);
        PluginCommand cmd = getCommand("kart");
        if (cmd != null) {
            KartCommand handler = new KartCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            ticks++;
            races.tick();
        }, 1L, 1L);
        int n = tracks.all().size();
        banner.ok("Tracks", n == 0 ? "None yet. /kart track generate main builds one, in a world of its own"
                : n + (n == 1 ? " track" : " tracks") + ". /kart join, then /kart start");
        banner.print();
    }

    @Override
    public void onDisable() {
        if (races != null) races.stopAll();
    }

    /**
     * A track generated into a world of its own needs that world loaded, and
     * the server only loads the worlds it knows about by itself. Loading one
     * Multiverse already loaded is nothing.
     */
    private void loadOwnWorlds() {
        for (Track t : tracks.all()) {
            if (!t.ownWorld() || t.worldName() == null || getServer().getWorld(t.worldName()) != null) continue;
            if (TrackBuilder.worldFolder(t.worldName()) == null) {
                getLogger().warning("Track " + t.id() + " lives in its own world, " + t.worldName() + ", which is gone.");
                continue;
            }
            Theme theme = null;
            try {
                if (t.prefab() != null) theme = ai.resourcepack.addons.gokarts.track.Prefab.load(t.prefab()).theme;
            } catch (java.io.IOException ignored) {
                // A prefab this build no longer has: the world loads as it was saved either way.
            }
            if (TrackBuilder.ownWorld(t.worldName(), theme) == null) {
                getLogger().warning("Could not load " + t.worldName() + ", the world of track " + t.id() + ".");
            }
        }
    }

    /** Takes down any item boxes, bananas or rockets a crash left in loaded worlds (they are never saved, but be sure). */
    private void sweepStrays() {
        int n = 0;
        for (World w : getServer().getWorlds()) {
            for (Entity e : w.getEntitiesByClass(Display.class)) {
                if (e.getScoreboardTags().contains("gokarts")) {
                    e.remove();
                    n++;
                }
            }
        }
        if (n > 0) getLogger().info("Removed " + n + " stray item display(s).");
    }

    /** Re-reads config.yml, and tracks.yml too unless a race is using it. Answers whether tracks were read. */
    public boolean reloadSettings() {
        reloadConfig();
        addNewOptions();
        settings = new Settings(getConfig(), getLogger());
        messages = new Messages(getConfig());
        if (!races.races().isEmpty()) return false;
        tracks.load();
        loadOwnWorlds();
        records.load();
        return true;
    }

    /** Writes any option a newer build added into the server's config.yml, with its default. */
    private void addNewOptions() {
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    public long ticks() {
        return ticks;
    }

    public Content content() {
        return content;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public TrackStore tracks() {
        return tracks;
    }

    public PlayerSnapshot snapshots() {
        return snapshots;
    }

    public RaceManager races() {
        return races;
    }

    public Driving driving() {
        return driving;
    }

    public Results results() {
        return results;
    }

    public TrackUndo undo() {
        return undo;
    }

    public Records records() {
        return records;
    }

    public Map<UUID, KartColour> colours() {
        return colours;
    }
}
