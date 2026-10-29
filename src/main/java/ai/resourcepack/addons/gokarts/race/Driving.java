package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.config.Settings;
import ai.resourcepack.addons.gokarts.content.Content;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleInput;
import ai.resourcepack.engine.api.VehicleState;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What makes a kart a kart rather than a small car, on top of RP Engine's
 * vehicle physics: every one of the karts, raced or just parked and driven.
 *
 * <ul>
 *   <li><b>Cruise speed.</b> A kart's vehicle {@code speed:} is the most a
 *   boost may take it to; the rest of the time it is held to config.yml's
 *   {@code cruise-speed} with {@link Vehicle#setSpeedLimit}, and off the road
 *   to {@code offroad-speed}. Grass is a choice, and it costs.</li>
 *   <li><b>Drifting.</b> Space is the engine's handbrake, and the handbrake is
 *   already what lets the rear go. Held through a corner at speed it charges:
 *   blue sparks, then orange, then purple, and letting go is a boost worth
 *   the level reached. Nothing here moves the kart sideways: the slide is the
 *   engine's own.</li>
 *   <li><b>Boosts</b> (a pad, a drift, a nitro, a rocket start) are a kick
 *   along the heading with {@link Vehicle#nudge} and the speed limit lifted
 *   for a moment, with flames out of the exhaust.</li>
 *   <li><b>Being hit</b> (a banana, a rocket) holds a kart to a crawl for a
 *   moment.</li>
 * </ul>
 *
 * <p>All of it is keyed by the vehicle's chassis id, and forgotten when a kart
 * stops being driven, so a parked kart costs nothing.
 */
public final class Driving {
    /** One kart's tricks in progress. */
    static final class State {
        int driftTicks = -1;
        int driftLevel;
        int boostTicks;
        int stunTicks;
        int padCooldown;
        double limit = Double.NaN;
        boolean wasDrifting;

        boolean drifting() {
            return driftTicks >= 0;
        }
    }

    /** Spark colours, by drift level: blue, orange, purple. */
    private static final Color[] SPARKS = {Color.fromRGB(0x4FC3FF), Color.fromRGB(0xFF9D2E), Color.fromRGB(0xC86BFF)};

    private final GoKartsPlugin plugin;
    private final Map<UUID, State> states = new HashMap<>();

    public Driving(GoKartsPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Content content() {
        return plugin.content();
    }

    State state(Vehicle v) {
        return states.computeIfAbsent(v.uniqueId(), k -> new State());
    }

    /** Whether a vehicle is one of the karts. */
    public static boolean isKart(Vehicle v) {
        ContentId id = v.id();
        return id.namespace().equals(Content.NS) && KartColour.ofVehicle(id.path()) != null;
    }

    /**
     * Every driven kart outside a race, once a tick: the same tricks with no
     * race around them. A kart in a race is ticked by its race instead, which
     * knows when it may move.
     */
    public void tickLoose() {
        if (!settings().tricksOutside) {
            states.clear();
            return;
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (Vehicle v : content().engine().vehicles().loaded()) {
            if (!isKart(v)) continue;
            Optional<Player> driver = v.driver();
            if (driver.isEmpty() || plugin.races().of(driver.get()) != null) continue;
            seen.add(v.uniqueId());
            tick(v, driver.get(), true);
            if (plugin.ticks() % 4 == 0) speedLine(v, driver.get());
        }
        // A kart nobody is driving has nothing in progress: let it go back to its definition.
        states.keySet().removeIf(id -> !seen.contains(id) && !plugin.races().drives(id));
    }

    /**
     * One kart's tick. {@code free} is a kart outside a race, which has no
     * race to say it may move: it always may.
     */
    void tick(Vehicle v, Player driver, boolean free) {
        State st = state(v);
        Settings s = settings();
        VehicleInput in = v.input();
        boolean airborne = v.is(VehicleState.AIRBORNE);
        double speed = v.groundSpeed();

        if (st.padCooldown > 0) st.padCooldown--;
        if (st.stunTicks > 0) {
            st.stunTicks--;
            cancelDrift(st);
        } else if (v.isEnabled()) {
            drift(v, driver, st, in, airborne, speed);
            Material under = under(v);
            if (!airborne && under == s.boostPad && st.padCooldown == 0) {
                st.padCooldown = 12;
                boost(v, s.padBoost);
            }
        }

        double limit;
        if (st.stunTicks > 0) {
            limit = 1.5;
        } else if (st.boostTicks > 0) {
            st.boostTicks--;
            limit = 0;
            exhaust(v, true);
        } else if (!airborne && s.offroad.contains(under(v))) {
            limit = s.offroadSpeed;
        } else {
            limit = s.cruiseSpeed;
        }
        if (!(limit == st.limit)) {
            st.limit = limit;
            v.setSpeedLimit(limit);
        }
    }

    /**
     * Out of a race the kart's own speedometer is off (the race HUD shares its
     * line), so this is it: the speed, and the drift charge as it builds.
     */
    private void speedLine(Vehicle v, Player driver) {
        State st = states.get(v.uniqueId());
        String kmh = String.valueOf(Math.round(Math.abs(v.groundSpeed()) * 3.6));
        StringBuilder line = new StringBuilder(plugin.messages().raw("free-speed").replace("{speed}", kmh));
        if (st != null && st.boostTicks > 0) line.append(plugin.messages().raw("free-boost"));
        else if (st != null && st.drifting() && st.driftLevel > 0) {
            line.append(plugin.messages().raw("free-drift-" + Math.min(3, st.driftLevel)));
        }
        ai.resourcepack.addons.gokarts.text.Text.actionBar(driver, ai.resourcepack.addons.gokarts.text.Text.mm(line.toString()));
    }

    /** The handbrake held through a corner: charge, sparks, and a boost on letting go. */
    private void drift(Vehicle v, Player driver, State st, VehicleInput in, boolean airborne, double speed) {
        Settings s = settings();
        boolean holding = in.keys() && in.jump();
        boolean steering = Math.abs(in.steer()) > 0.25;
        if (!st.drifting()) {
            if (holding && steering && !airborne && speed >= s.driftMinSpeed) {
                st.driftTicks = 0;
                st.driftLevel = 0;
            }
            return;
        }
        if (!holding) {
            // Let go: the boost the slide was worth.
            if (st.driftLevel > 0) boost(v, s.driftLevels.get(st.driftLevel - 1).boost());
            cancelDrift(st);
            return;
        }
        if (speed < s.driftMinSpeed * 0.5) {
            // Scrubbed off all the speed it had: a spin, not a drift.
            cancelDrift(st);
            return;
        }
        st.driftTicks++;
        int level = 0;
        for (int i = 0; i < s.driftLevels.size(); i++) if (st.driftTicks >= s.driftLevels.get(i).after()) level = i + 1;
        if (level > st.driftLevel) {
            st.driftLevel = level;
            content().sound(driver, "spark", 0.8f, 0.9f + 0.2f * level, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
        }
        if (st.driftTicks % 2 == 0) sparks(v, st.driftLevel);
    }

    private static void cancelDrift(State st) {
        st.driftTicks = -1;
        st.driftLevel = 0;
    }

    /**
     * The drift bar's value, 0 to 100, laid out so the HUD's colour thresholds
     * (34% and 67%) are the levels: nothing until the first level (the bar is
     * hidden at 0), blue filling through the first, orange through the second,
     * full and purple at the third.
     */
    int driftDisplay(Vehicle v) {
        State st = states.get(v.uniqueId());
        if (st == null || !st.drifting() || st.driftLevel == 0) return 0;
        java.util.List<Settings.DriftLevel> levels = settings().driftLevels;
        if (st.driftLevel >= levels.size()) return 100;
        int from = levels.get(st.driftLevel - 1).after();
        int to = levels.get(st.driftLevel).after();
        double f = Math.max(0, Math.min(1, (st.driftTicks - from) / (double) Math.max(1, to - from)));
        int base = st.driftLevel == 1 ? 1 : 34;
        return base + (int) Math.round(f * 32);
    }

    /** A boost: the kick now, the speed limit lifted for a moment, flames and a whoosh. */
    public void boost(Vehicle v, Settings.Boost b) {
        State st = state(v);
        st.boostTicks = Math.max(st.boostTicks, b.ticks());
        st.limit = Double.NaN;
        v.setSpeedLimit(0);
        v.nudge(b.kick());
        content().sound(v.location(), "boost", 1f, 1f, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
        exhaust(v, false);
    }

    /** Hit by something: held to a crawl for a while, and any drift lost. */
    public void stun(Vehicle v, int ticks) {
        State st = state(v);
        st.stunTicks = Math.max(st.stunTicks, ticks);
        st.boostTicks = 0;
        cancelDrift(st);
    }

    public boolean stunned(Vehicle v) {
        State st = states.get(v.uniqueId());
        return st != null && st.stunTicks > 0;
    }

    /** Forgets a kart, which is about to go: removed, or replaced on a respawn. */
    public void forget(Vehicle v) {
        states.remove(v.uniqueId());
    }

    private static Material under(Vehicle v) {
        Location l = v.location();
        return l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.2), l.getBlockZ()).getType();
    }

    private static void sparks(Vehicle v, int level) {
        Location l = v.location();
        if (level == 0) {
            for (double side : new double[]{-0.55, 0.55}) {
                l.getWorld().spawnParticle(Particle.SMOKE, v.pointOn(side, 0.1, -0.6), 1, 0.05, 0.02, 0.05, 0.005);
            }
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(SPARKS[Math.min(level, 3) - 1], 1.1f);
        for (double side : new double[]{-0.6, 0.6}) {
            l.getWorld().spawnParticle(Particle.DUST, v.pointOn(side, 0.12, -0.6), 3, 0.12, 0.05, 0.12, 0, dust);
            l.getWorld().spawnParticle(Particle.CRIT, v.pointOn(side, 0.1, -0.6), 1, 0.05, 0.02, 0.05, 0.15);
        }
    }

    private static void exhaust(Vehicle v, boolean steady) {
        // The exhaust tip, where Studio's vehicle editor put the kart's smoke.
        Location at = v.pointOn(-0.25, 0.35, -1.0);
        at.getWorld().spawnParticle(Particle.FLAME, at, steady ? 2 : 10, 0.06, 0.04, 0.06, steady ? 0.01 : 0.06);
        if (!steady) at.getWorld().spawnParticle(Particle.CLOUD, at, 4, 0.1, 0.05, 0.1, 0.03);
    }
}
