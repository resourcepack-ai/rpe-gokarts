package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.config.Settings;
import ai.resourcepack.addons.gokarts.content.Content;
import ai.resourcepack.addons.gokarts.track.PrefabBuilder;
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
import org.bukkit.util.Vector;

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
 *   blue sparks, then orange, then purple, a flash of the mini-turbo VFX at
 *   each level, and letting go is a boost worth the level reached.</li>
 *   <li><b>Boosts</b> (a pad, a drift, a nitro, a rocket start, landing a
 *   trick) are a kick along the heading with {@link Vehicle#nudge} and the
 *   speed limit lifted for a moment, with the boost VFX out of the exhaust,
 *   the kart's own boost animation and speed lines on the driver's HUD.</li>
 *   <li><b>Jump pads</b> at the lip of a ramp throw the kart into the air.
 *   Space in the air is a trick (the kart's barrel roll), and landing one is a
 *   boost.</li>
 *   <li><b>Ice</b> turns less: the kart slides wide of where it is pointed.</li>
 *   <li><b>Being hit</b> (a banana, a rocket) holds a kart to a crawl for a
 *   moment while it spins out.</li>
 * </ul>
 *
 * <p>The kart's own animations ({@link Vehicle#perform}: a boost, a trick, a
 * spin-out, all made in Studio) are played by priority, so a spin-out is never
 * cut short by a boost. Everything is keyed by the vehicle's chassis id and
 * forgotten when a kart stops being driven, so a parked kart costs nothing.
 */
public final class Driving {
    /** A kart animation in progress, and how much it outranks another. */
    private enum Act {
        BOOST("boost", 1), TRICK("trick", 2), SPINOUT("spinout", 3);

        final String animation;
        final int rank;

        Act(String animation, int rank) {
            this.animation = animation;
            this.rank = rank;
        }
    }

    /** One kart's tricks in progress. */
    static final class State {
        int driftTicks = -1;
        int driftLevel;
        int boostTicks;
        int stunTicks;
        /** A hyper shield: ticks left, and how far over cruising it lets the kart go. */
        int shieldTicks;
        double shieldSpeed;
        /** A static surge: ticks left held to {@code slowShare} of the speed it would have. */
        int slowTicks;
        double slowShare = 1;
        int padCooldown;
        int jumpCooldown;
        double limit = Double.NaN;
        /** Null until this state has set the kart's handling once: a kart can come to it still turned for ice. */
        Boolean icy;
        /** Ticks since a jump pad threw it; -1 when it has not been thrown. */
        int launched = -1;
        boolean tricked;
        boolean wasAirborne;
        Act act;
        int actTicks;

        boolean drifting() {
            return driftTicks >= 0;
        }
    }

    /** Spark colours, by drift level: blue, orange, purple. */
    private static final Color[] SPARKS = {Color.fromRGB(0x4FC3FF), Color.fromRGB(0xFF9D2E), Color.fromRGB(0xC86BFF)};
    /** How long each kart animation is played for, in ticks (Studio authored them at these lengths). */
    private static final int TRICK_TICKS = 12, SPINOUT_TICKS = 24;

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
            // Only the loose karts: clearing everything here reset every race
            // kart's drift, boosts and stuns every tick.
            states.keySet().removeIf(id -> !plugin.races().drives(id));
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
        states.keySet().removeIf(id -> !seen.contains(id) && !plugin.races().drives(id));
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

    /** One kart's tick. {@code free} is a kart outside a race, which always may move. */
    void tick(Vehicle v, Player driver, boolean free) {
        State st = state(v);
        Settings s = settings();
        VehicleInput in = v.input();
        boolean airborne = v.is(VehicleState.AIRBORNE);
        double speed = v.groundSpeed();
        Material under = under(v);

        if (st.padCooldown > 0) st.padCooldown--;
        if (st.jumpCooldown > 0) st.jumpCooldown--;
        if (st.stunTicks > 0) {
            st.stunTicks--;
            cancelDrift(st);
        } else if (v.isEnabled()) {
            drift(v, driver, st, in, airborne, speed);
            if (!airborne && (under == s.boostPad || under == PrefabBuilder.PAD) && st.padCooldown == 0) {
                st.padCooldown = 12;
                boost(v, s.padBoost);
                content().vfx("gk_pad", v, driver);
            }
            // The prefab tracks are built with PrefabBuilder's pads, whatever
            // config.yml names for a track built by hand.
            if (st.jumpCooldown == 0 && speed > 3 && (onJumpPad(v, s.jumpPad) || onJumpPad(v, PrefabBuilder.JUMP))) {
                launch(v, driver, st);
            }
            trick(v, driver, st, in, airborne);
        }
        st.wasAirborne = airborne;

        // Ice turns less: the kart carries on wider than it is pointed.
        boolean icy = !airborne && s.ice.contains(under);
        if (st.icy == null || icy != st.icy) {
            st.icy = icy;
            v.setHandling(1.0, icy ? 0.55 : 1.0);
        }

        double limit;
        if (st.shieldTicks > 0) st.shieldTicks--;
        if (st.slowTicks > 0) st.slowTicks--;
        if (st.stunTicks > 0) {
            limit = 1.5;
        } else if (st.boostTicks > 0) {
            st.boostTicks--;
            limit = 0;
            exhaust(v);
        } else if (st.shieldTicks > 0) {
            // Shielded, the grass does not hold it back either.
            limit = s.cruiseSpeed + st.shieldSpeed;
        } else if (!airborne && s.offroad.contains(under)) {
            limit = s.offroadSpeed;
        } else {
            limit = s.cruiseSpeed;
        }
        if (st.slowTicks > 0 && st.stunTicks == 0) {
            // 0 is "no limit" (a boost): a surged kart's boost is held down too.
            limit = (limit == 0 ? s.cruiseSpeed * 1.5 : limit) * st.slowShare;
        }
        if (!(limit == st.limit)) {
            st.limit = limit;
            v.setSpeedLimit(limit);
        }
        animate(v, st);
    }

    /** The handbrake held through a corner: charge, sparks, and a boost on letting go. */
    private void drift(Vehicle v, Player driver, State st, VehicleInput in, boolean airborne, double speed) {
        Settings s = settings();
        boolean holding = in.keys() && in.jump();
        boolean steering = Math.abs(in.steer()) > 0.25;
        if (!st.drifting()) {
            if (holding && steering && !airborne && speed >= s.driftMinSpeed && st.launched < 0) {
                st.driftTicks = 0;
                st.driftLevel = 0;
            }
            return;
        }
        if (!holding) {
            int level = Math.min(st.driftLevel, s.driftLevels.size());
            if (level > 0) boost(v, s.driftLevels.get(level - 1).boost());
            cancelDrift(st);
            return;
        }
        if (speed < s.driftMinSpeed * 0.5) {
            cancelDrift(st);
            return;
        }
        st.driftTicks++;
        int level = 0;
        for (int i = 0; i < s.driftLevels.size(); i++) if (st.driftTicks >= s.driftLevels.get(i).after()) level = i + 1;
        if (level > st.driftLevel) {
            st.driftLevel = level;
            content().sound(driver, "spark", 0.8f, 0.9f + 0.2f * level, Sound.BLOCK_AMETHYST_BLOCK_CHIME);
            content().vfx("gk_miniturbo_" + Math.min(3, level), v, driver);
        }
        if (st.driftTicks % 2 == 0) sparks(v, st.driftLevel);
    }

    /** A jump pad: up and on, and a moment in which space is a trick. */
    private void launch(Vehicle v, Player driver, State st) {
        st.jumpCooldown = 20;
        st.launched = 0;
        st.tricked = false;
        Settings s = settings();
        double yaw = Math.toRadians(v.heading());
        Vector forward = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        double speed = v.groundSpeed();
        // Thrown just far enough to land a little past the far side: a kart
        // crawls up a ramp and at that pace drops short into the hazard, and
        // one thrown at a fixed speed overshoots a short jump into the bend
        // after it. A pad on flat ground is a hop and a kick.
        double lift = s.jumpUp;
        Location at = v.location();
        Vector throwV = forward.clone().multiply(speed + s.jumpForward);
        double[] land = landingOf(at);
        double[] gap = land == null ? gapAhead(at, forward) : null;
        if (land != null) {
            // On a prefab's ramp: aimed at the road beyond, however the kart
            // came up the ramp. Not turned to face it: a turn put on in the air
            // carries on after the landing and puts the kart into the wall.
            Vector aim = new Vector(land[0] - at.getX(), 0, land[2] - at.getZ());
            double air = airTime(lift, at.getY() - land[1]);
            double reach = aim.length();
            if (reach > 1e-3) {
                throwV = aim.multiply(1 / reach).multiply(Math.max(MIN_THROW, Math.min(s.jumpSpeed, reach / air)));
            }
        } else if (gap != null) {
            double air = airTime(lift, gap[1]);
            throwV = forward.clone().multiply(Math.max(MIN_THROW, Math.min(s.jumpSpeed, (gap[0] + s.jumpMargin) / air)));
        }
        // And no speed limit until it is down, or the limit would eat the throw.
        st.boostTicks = Math.max(st.boostTicks, 24);
        st.limit = Double.NaN;
        v.setSpeedLimit(0);
        Vector now = v.velocity();
        v.applyImpulse(new Vector(throwV.getX() - now.getX(), lift, throwV.getZ() - now.getZ()), 0);
        content().sound(v.location(), "boost", 0.8f, 1.3f, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
        content().vfx("gk_pad", v, driver);
    }

    /** Seconds in the air for a throw up at {@code lift} that lands {@code drop} blocks lower. */
    private static double airTime(double lift, double drop) {
        return (lift + Math.sqrt(lift * lift + 2 * GRAVITY * Math.max(0, drop))) / GRAVITY;
    }

    /** The landing of the prefab ramp a kart at {@code at} is on, from any track in its world. */
    private double[] landingOf(Location at) {
        for (ai.resourcepack.addons.gokarts.track.Track t : plugin.tracks().all()) {
            if (t.prefab() == null || t.world() == null || !t.world().equals(at.getWorld())) continue;
            double[] land = t.landing(at.getX(), at.getZ(), settings().jumpMargin);
            if (land != null) return land;
        }
        return null;
    }

    /** What a land vehicle falls at, blocks a second squared: RP Engine's own number. */
    private static final double GRAVITY = 28;
    /** The slowest a jump pad throws a kart over a gap, whatever the arithmetic says. */
    private static final double MIN_THROW = 8;
    /** How far ahead a jump pad looks for the far side, blocks. */
    private static final double PROBE = 32;

    /**
     * The jump in front of a kart on a pad: how far along {@code forward} the
     * ground comes back after the gap, and how far below the kart it is.
     * Null when there is no gap within a few blocks (a pad on flat ground) or
     * no far side within reach.
     */
    static double[] gapAhead(Location at, Vector forward) {
        double y0 = at.getY();
        boolean gone = false;
        for (double d = 0.5; d <= PROBE; d += 0.5) {
            double top = groundTop(at.getWorld(), at.getX() + forward.getX() * d, y0, at.getZ() + forward.getZ() * d);
            if (!gone) {
                if (Double.isNaN(top)) {
                    if (d > 6) return null;
                    gone = true;
                }
            } else if (!Double.isNaN(top)) {
                return new double[]{d, y0 - top};
            }
        }
        return null;
    }

    /** The top of the highest solid block in a column from a block over y0 to six under it; NaN if none. */
    private static double groundTop(org.bukkit.World w, double x, double y0, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int y = (int) Math.floor(y0 + 0.9); y >= (int) Math.floor(y0 - 6); y--) {
            org.bukkit.block.Block b = w.getBlockAt(bx, y, bz);
            if (b.getType().isSolid()) return b.getBoundingBox().getMaxY();
            if (b.isLiquid()) return Double.NaN;
        }
        return Double.NaN;
    }

    /**
     * In the air after a jump pad, space is a trick: the kart rolls, and
     * landing it is a boost. One per jump.
     */
    private void trick(Vehicle v, Player driver, State st, VehicleInput in, boolean airborne) {
        if (st.launched < 0) return;
        st.launched++;
        if (airborne && !st.tricked && in.keys() && in.jump() && st.launched > 2) {
            st.tricked = true;
            play(v, st, Act.TRICK, TRICK_TICKS);
            content().sound(driver, "spark", 1f, 1.6f, Sound.ENTITY_PLAYER_ATTACK_SWEEP);
            driver.getWorld().spawnParticle(Particle.FIREWORK, v.pointOn(0, 1, 0), 12, 0.4, 0.3, 0.4, 0.05);
        }
        if (st.wasAirborne && !airborne && st.launched > 4) {
            if (st.tricked) {
                boost(v, settings().trickBoost);
                content().sound(driver, "item_got", 1f, 1.2f, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
            }
            st.launched = -1;
        } else if (st.launched > 100) {
            st.launched = -1;
        }
    }

    private static void cancelDrift(State st) {
        st.driftTicks = -1;
        st.driftLevel = 0;
    }

    /** The drift level a kart has reached, 0 to 3, for the HUD's gauge. */
    int driftLevel(Vehicle v) {
        State st = states.get(v.uniqueId());
        return st == null || !st.drifting() ? 0 : Math.min(3, st.driftLevel);
    }

    boolean boosting(Vehicle v) {
        State st = states.get(v.uniqueId());
        return st != null && st.boostTicks > 0;
    }

    /** A boost: the kick now, the speed limit lifted for a moment, flames, a whoosh and the boost VFX. */
    public void boost(Vehicle v, Settings.Boost b) {
        State st = state(v);
        st.boostTicks = Math.max(st.boostTicks, b.ticks());
        st.limit = Double.NaN;
        v.setSpeedLimit(0);
        v.nudge(b.kick());
        content().sound(v.location(), "boost", 1f, 1f, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
        Player driver = v.driver().orElse(null);
        content().vfx("gk_boost", v, driver);
        play(v, st, Act.BOOST, b.ticks());
    }

    /** Hit by something: held to a crawl and spun round, any drift lost. */
    public void stun(Vehicle v, int ticks) {
        State st = state(v);
        st.stunTicks = Math.max(st.stunTicks, ticks);
        st.boostTicks = 0;
        cancelDrift(st);
        // Hit mid-jump: the landing is never looked for while stunned, and a
        // kart left "launched" could not drift for five seconds after.
        st.launched = -1;
        st.tricked = false;
        play(v, st, Act.SPINOUT, Math.max(SPINOUT_TICKS, ticks));
    }

    /** A hyper shield for {@code ticks}: no hit lands, and the kart may go {@code extra} over cruising. */
    public void shield(Vehicle v, int ticks, double extra) {
        State st = state(v);
        st.shieldTicks = Math.max(st.shieldTicks, ticks);
        st.shieldSpeed = extra;
        st.stunTicks = 0;
        st.slowTicks = 0;
    }

    public boolean shielded(Vehicle v) {
        State st = states.get(v.uniqueId());
        return st != null && st.shieldTicks > 0;
    }

    /** Held to {@code share} of its speed for {@code ticks}: a static surge. */
    public void slow(Vehicle v, int ticks, double share) {
        State st = state(v);
        st.slowTicks = Math.max(st.slowTicks, ticks);
        st.slowShare = share;
    }

    public boolean stunned(Vehicle v) {
        State st = states.get(v.uniqueId());
        return st != null && st.stunTicks > 0;
    }

    /** Plays one of the kart's own animations, unless something more important is playing. */
    private static void play(Vehicle v, State st, Act act, int ticks) {
        if (st.act != null && st.actTicks > 0 && st.act.rank > act.rank) return;
        st.act = act;
        st.actTicks = ticks;
        v.perform(act.animation);
    }

    /** Ends a kart animation when its time is up, handing back to the driving states. */
    private static void animate(Vehicle v, State st) {
        if (st.act == null) return;
        if (--st.actTicks > 0) return;
        if (st.act != Act.BOOST && st.boostTicks > 0) {
            st.act = Act.BOOST;
            st.actTicks = st.boostTicks;
            v.perform(Act.BOOST.animation);
            return;
        }
        st.act = null;
        v.rest();
    }

    /** Forgets a kart, which is about to go: removed, or replaced on a respawn. */
    public void forget(Vehicle v) {
        states.remove(v.uniqueId());
    }

    /**
     * Whether the kart is on a jump pad, or stepping onto one. A ramp climbs in
     * half-block steps and the pad sits a half block proud at its lip, so at
     * speed a kart crosses it on the very ticks it counts as airborne: asking
     * only "grounded, on a pad" missed most jumps. So this looks a little above
     * and below the kart, and a block ahead of it.
     */
    private static boolean onJumpPad(Vehicle v, Material pad) {
        Location l = v.location();
        double yaw = Math.toRadians(v.heading());
        double ax = -Math.sin(yaw) * 0.8, az = Math.cos(yaw) * 0.8;
        for (double[] at : new double[][]{{0, 0}, {ax, az}}) {
            int bx = (int) Math.floor(l.getX() + at[0]), bz = (int) Math.floor(l.getZ() + at[1]);
            for (double dy : new double[]{0.3, -0.2, -0.8}) {
                if (l.getWorld().getBlockAt(bx, (int) Math.floor(l.getY() + dy), bz).getType() == pad) return true;
            }
        }
        return false;
    }

    private static Material under(Vehicle v) {
        Location l = v.location();
        return l.getWorld().getBlockAt(l.getBlockX(), (int) Math.floor(l.getY() - 0.2), l.getBlockZ()).getType();
    }

    private static void sparks(Vehicle v, int level) {
        Location l = v.location();
        if (level == 0) {
            for (double side : new double[]{-0.6, 0.6}) {
                l.getWorld().spawnParticle(Particle.SMOKE, v.pointOn(side, 0.1, -0.7), 1, 0.05, 0.02, 0.05, 0.005);
            }
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(SPARKS[Math.min(level, 3) - 1], 1.2f);
        for (double side : new double[]{-0.65, 0.65}) {
            l.getWorld().spawnParticle(Particle.DUST, v.pointOn(side, 0.12, -0.75), 3, 0.12, 0.05, 0.12, 0, dust);
            l.getWorld().spawnParticle(Particle.CRIT, v.pointOn(side, 0.1, -0.75), 1, 0.05, 0.02, 0.05, 0.15);
        }
    }

    private static void exhaust(Vehicle v) {
        // The exhaust tips, where Studio's vehicle editor put the kart's smoke.
        Location at = v.pointOn(-0.375, 0.56, -1.1);
        at.getWorld().spawnParticle(Particle.FLAME, at, 2, 0.06, 0.04, 0.06, 0.01);
        at.getWorld().spawnParticle(Particle.FLAME, v.pointOn(0.375, 0.56, -1.1), 2, 0.06, 0.04, 0.06, 0.01);
    }
}
