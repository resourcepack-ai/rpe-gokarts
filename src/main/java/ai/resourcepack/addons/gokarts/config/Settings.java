package ai.resourcepack.addons.gokarts.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** config.yml's numbers, read once per load. Every value falls back to the jar's default when it is missing or nonsense. */
public final class Settings {
    /** A boost: blocks a second added at once, and how many ticks the speed limit is lifted for. */
    public record Boost(double kick, int ticks) {
    }

    /** A drift level: how long the slide has to be held for it, in ticks, and the boost it is worth. */
    public record DriftLevel(int after, Boost boost) {
    }

    public final int laps;
    public final int minPlayers;
    public final int lobbyCountdown;
    public final int finishTimeout;
    public final int maxSeconds;
    public final int resultsSeconds;
    public final int stuckTicks;

    public final double cruiseSpeed;
    public final double offroadSpeed;
    public final Set<Material> offroad;
    public final Material boostPad;
    public final Material jumpPad;
    public final Set<Material> ice;
    /** A jump pad's throw: up and forward, blocks a second. */
    public final double jumpUp, jumpForward, jumpSpeed, jumpMargin;
    public final Boost trickBoost;
    public final boolean tricksOutside;

    public final Boost padBoost;
    public final Boost nitroBoost;
    public final Boost rocketStart;

    public final double driftMinSpeed;
    public final List<DriftLevel> driftLevels;

    public final boolean items;
    public final int boxRespawnTicks;
    public final int rouletteTicks;
    public final int bananaStunTicks;
    public final int rocketStunTicks;
    public final double rocketSpeed;
    public final int rocketTicks;
    /** Item weights, leader first. */
    public final List<Map<String, Integer>> odds;

    public final Set<String> allowedCommands;
    public final String hud;
    public final String results;

    public Settings(FileConfiguration c, Logger log) {
        laps = clamp(c.getInt("race.laps", 3), 1, 50);
        minPlayers = clamp(c.getInt("race.min-players", 2), 1, 8);
        lobbyCountdown = clamp(c.getInt("race.lobby-countdown", 20), 3, 600);
        finishTimeout = clamp(c.getInt("race.finish-timeout", 30), 5, 600);
        maxSeconds = clamp(c.getInt("race.max-seconds", 600), 30, 7200);
        resultsSeconds = clamp(c.getInt("race.results-seconds", 12), 3, 120);
        stuckTicks = clamp((int) Math.round(c.getDouble("race.stuck-seconds", 12) * 20), 60, 1200);

        cruiseSpeed = clamp(c.getDouble("karts.cruise-speed", 13), 2, 60);
        offroadSpeed = clamp(c.getDouble("karts.offroad-speed", 6), 1, 60);
        offroad = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("karts.offroad-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock()) offroad.add(m);
            else log.warning("config.yml karts.offroad-blocks: no block called " + name + ".");
        }
        Material pad = Material.matchMaterial(c.getString("karts.boost-pad-block", "MAGENTA_GLAZED_TERRACOTTA"));
        boostPad = pad != null && pad.isBlock() ? pad : Material.MAGENTA_GLAZED_TERRACOTTA;
        tricksOutside = c.getBoolean("karts.tricks-outside-races", true);
        Material jump = Material.matchMaterial(c.getString("karts.jump-pad-block", "LIME_GLAZED_TERRACOTTA"));
        jumpPad = jump != null && jump.isBlock() ? jump : Material.LIME_GLAZED_TERRACOTTA;
        ice = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("karts.ice-blocks")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock()) ice.add(m);
        }
        jumpUp = clamp(c.getDouble("karts.jump.lift", 12), 0, 30);
        jumpForward = clamp(c.getDouble("karts.jump.forward", 3), 0, 30);
        jumpSpeed = clamp(c.getDouble("karts.jump.max-speed", 22), 0, 40);
        jumpMargin = clamp(c.getDouble("karts.jump.margin", 3), 0, 20);

        padBoost = boost(c, "boost.pad", 5, 1.0);
        nitroBoost = boost(c, "boost.nitro", 7, 1.6);
        rocketStart = boost(c, "boost.rocket-start", 6, 1.2);
        trickBoost = boost(c, "boost.trick", 4, 0.8);

        driftMinSpeed = clamp(c.getDouble("drift.min-speed", 6), 0, 60);
        List<DriftLevel> levels = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("drift.levels")) {
            double after = number(m.get("after"), 1);
            levels.add(new DriftLevel((int) Math.round(after * 20),
                    new Boost(clamp(number(m.get("kick"), 3), 0, 30), clamp((int) Math.round(number(m.get("seconds"), 0.5) * 20), 1, 200))));
        }
        if (levels.isEmpty()) {
            levels.add(new DriftLevel(16, new Boost(3, 10)));
            levels.add(new DriftLevel(36, new Boost(5, 18)));
            levels.add(new DriftLevel(60, new Boost(7, 26)));
        }
        levels.sort((a, b) -> Integer.compare(a.after, b.after));
        driftLevels = List.copyOf(levels.subList(0, Math.min(3, levels.size())));

        items = c.getBoolean("items.enabled", true);
        boxRespawnTicks = ticks(c, "items.box-respawn", 3);
        rouletteTicks = ticks(c, "items.roulette", 1.5);
        bananaStunTicks = ticks(c, "items.banana-stun", 1.2);
        rocketStunTicks = ticks(c, "items.rocket-stun", 1.8);
        rocketSpeed = clamp(c.getDouble("items.rocket-speed", 34), 4, 80) / 20.0;
        rocketTicks = ticks(c, "items.rocket-seconds", 10);
        List<Map<String, Integer>> o = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("items.odds")) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                int w = (int) Math.round(number(e.getValue(), 0));
                if (w > 0) row.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), w);
            }
            if (!row.isEmpty()) o.add(row);
        }
        if (o.isEmpty()) o.add(Map.of("banana", 1, "nitro", 1, "rocket", 1));
        odds = List.copyOf(o);

        Set<String> allowed = new java.util.HashSet<>();
        for (String s : c.getStringList("allowed-commands")) allowed.add(s.toLowerCase(Locale.ROOT).replace("/", ""));
        allowedCommands = Set.copyOf(allowed);
        hud = c.getString("ui.hud", "gokarts:hud");
        results = c.getString("ui.results", "gokarts:results");
    }

    private static Boost boost(FileConfiguration c, String path, double kick, double seconds) {
        ConfigurationSection s = c.getConfigurationSection(path);
        double k = s == null ? kick : s.getDouble("kick", kick);
        double t = s == null ? seconds : s.getDouble("seconds", seconds);
        return new Boost(clamp(k, 0, 30), clamp((int) Math.round(t * 20), 1, 400));
    }

    private static int ticks(FileConfiguration c, String path, double seconds) {
        return clamp((int) Math.round(c.getDouble(path, seconds) * 20), 1, 72000);
    }

    private static double number(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try {
                return Double.parseDouble(o.toString());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double clamp(double v, double lo, double hi) {
        return Double.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo;
    }
}
