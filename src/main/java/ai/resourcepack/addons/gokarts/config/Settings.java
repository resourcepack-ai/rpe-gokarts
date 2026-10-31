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
    /** Whether a lobby with min-players waiting counts down and starts on its own. Off: staff start every race. */
    public final boolean autoStart;
    public final int lobbyCountdown;
    public final int finishTimeout;
    public final int maxSeconds;
    public final int resultsSeconds;
    public final int stuckTicks;
    /** How far from the lobby somebody waiting may go before they are put back on it, in blocks. */
    public final double lobbyRadius;
    /** What the bots are called, in no order: /kart start --withBots. */
    public final java.util.List<String> botNames;

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
    /** A bumper orb: blocks a tick, how long it rolls, how many walls it takes, and its hit. */
    public final double orbSpeed;
    public final int orbTicks, orbBounces, orbStunTicks;
    /** A storm drone: blocks a tick, its hit, and how far round its target the strike reaches. */
    public final double droneSpeed, droneRadius;
    public final int droneStunTicks;
    /** How long overdrive lasts. */
    public final int overdriveTicks;
    /** How long a hyper shield lasts, and how much faster than cruising it lets a kart go (blocks a second). */
    public final int shieldTicks;
    public final double shieldSpeed;
    /** How long a smoke bomb blinds whoever is ahead. */
    public final int smokeTicks;
    /** How far a shockwave horn reaches, and its hit. */
    public final double hornRadius;
    public final int hornStunTicks;
    /** How long a static surge slows everybody else, and to what share of their speed. */
    public final int surgeTicks;
    public final double surgeSlow;
    /** Item weights, leader first. */
    public final List<Map<String, Integer>> odds;

    public final Set<String> allowedCommands;
    public final String hud;
    public final String results;

    public Settings(FileConfiguration c, Logger log) {
        laps = clamp(c.getInt("race.laps", 3), 1, 50);
        minPlayers = clamp(c.getInt("race.min-players", 2), 1, 8);
        autoStart = c.getBoolean("race.auto-start", false);
        lobbyCountdown = clamp(c.getInt("race.lobby-countdown", 20), 3, 600);
        finishTimeout = clamp(c.getInt("race.finish-timeout", 30), 5, 600);
        maxSeconds = clamp(c.getInt("race.max-seconds", 600), 30, 7200);
        resultsSeconds = clamp(c.getInt("race.results-seconds", 12), 3, 120);
        stuckTicks = clamp((int) Math.round(c.getDouble("race.stuck-seconds", 12) * 20), 60, 1200);
        lobbyRadius = clamp(c.getDouble("race.lobby-radius", 4.8), 1.5, 200);
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String name : c.getStringList("bots.names")) {
            if (name != null && !name.isBlank()) names.add(name.length() > 16 ? name.substring(0, 16) : name);
        }
        botNames = java.util.List.copyOf(names);

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
        orbSpeed = clamp(c.getDouble("items.orb.speed", 24), 4, 60) / 20.0;
        orbTicks = ticks(c, "items.orb.seconds", 8);
        orbBounces = clamp(c.getInt("items.orb.bounces", 5), 0, 50);
        orbStunTicks = ticks(c, "items.orb.stun", 1.2);
        droneSpeed = clamp(c.getDouble("items.drone.speed", 40), 4, 120) / 20.0;
        droneRadius = clamp(c.getDouble("items.drone.radius", 4), 0.5, 20);
        droneStunTicks = ticks(c, "items.drone.stun", 2.0);
        overdriveTicks = ticks(c, "items.overdrive-seconds", 6);
        shieldTicks = ticks(c, "items.shield.seconds", 6);
        shieldSpeed = clamp(c.getDouble("items.shield.speed", 3), 0, 30);
        smokeTicks = ticks(c, "items.smoke-seconds", 3);
        hornRadius = clamp(c.getDouble("items.horn.radius", 7), 1, 30);
        hornStunTicks = ticks(c, "items.horn.stun", 0.8);
        surgeTicks = ticks(c, "items.surge.seconds", 3);
        surgeSlow = clamp(c.getDouble("items.surge.slow", 0.65), 0.1, 1);
        List<Map<String, Integer>> o = rows(c.getMapList("items.odds"));
        if (o.isEmpty()) o.add(new LinkedHashMap<>(Map.of("banana", 1, "nitro", 1, "rocket", 1)));
        // An item a newer version adds, named in none of this server's rows,
        // joins at its default weight (from the jar's config.yml), so it drops
        // without anybody editing their odds. Naming it with 0 leaves it out.
        List<Map<String, Integer>> defaults = c.getDefaults() == null ? List.of() : rows(c.getDefaults().getMapList("items.odds"));
        if (!defaults.isEmpty()) {
            java.util.Set<String> named = new java.util.HashSet<>();
            for (Map<?, ?> m : c.getMapList("items.odds")) for (Object k : m.keySet()) named.add(String.valueOf(k).toLowerCase(Locale.ROOT));
            for (int i = 0; i < o.size(); i++) {
                int d = o.size() == 1 ? defaults.size() / 2 : (int) Math.round(i * (defaults.size() - 1) / (double) (o.size() - 1));
                for (Map.Entry<String, Integer> e : defaults.get(d).entrySet()) {
                    if (!named.contains(e.getKey())) o.get(i).put(e.getKey(), e.getValue());
                }
            }
        }
        List<Map<String, Integer>> frozen = new ArrayList<>();
        for (Map<String, Integer> row : o) frozen.add(java.util.Collections.unmodifiableMap(row));
        odds = List.copyOf(frozen);

        Set<String> allowed = new java.util.HashSet<>();
        for (String s : c.getStringList("allowed-commands")) allowed.add(s.toLowerCase(Locale.ROOT).replace("/", ""));
        allowedCommands = Set.copyOf(allowed);
        hud = c.getString("ui.hud", "gokarts:hud");
        results = c.getString("ui.results", "gokarts:results");
    }

    /** Odds rows as written: weights above zero, names lower-cased. */
    private static List<Map<String, Integer>> rows(List<Map<?, ?>> list) {
        List<Map<String, Integer>> o = new ArrayList<>();
        for (Map<?, ?> m : list) {
            Map<String, Integer> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                int w = (int) Math.round(number(e.getValue(), 0));
                if (w > 0) row.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), w);
            }
            if (!row.isEmpty()) o.add(row);
        }
        return o;
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
