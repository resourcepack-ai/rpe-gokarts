package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.VehicleInput;
import ai.resourcepack.engine.api.VehicleState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Computer drivers: {@code /kart start --withBots}.
 *
 * <p>A bot drives an ordinary kart through RP Engine's {@link Vehicle#drive},
 * with the same keys a player has: W, A and D, and space, which is the
 * handbrake on the ground and a trick in the air. So everything the plugin
 * does with what a driver presses (drift charge, boosts, tricks) is the same
 * for a bot, and a bot gets no help a player does not: same karts, same
 * speed, same items. It follows the track's racing line (the prefab's own
 * road, or the gates joined up on a track built by hand), looking further
 * ahead the faster it goes, drifts through the bends worth drifting, tricks
 * off the jumps, fires its items after a moment's thought, and backs out of
 * whatever it has driven into.
 *
 * <p>What it looks like is a mannequin (Minecraft 1.21.9 and newer) in one of
 * the game's own skins, wearing the helmet in its kart's colour, carried in
 * the driver's seat with {@link Vehicle#carry}. On an older server the kart
 * drives itself with nobody in it.
 */
final class Bots {
    private Bots() {
    }

    /** The racing line's spacing, blocks. */
    static final double STEP = 1.0;

    /** The game's own skins: texture path under entity/player/, slim or wide. */
    private static final String[] SKINS = {
            "wide/steve", "slim/alex", "wide/ari", "slim/efe", "wide/kai",
            "slim/makena", "wide/noor", "slim/sunny", "wide/zuri"};

    private static boolean warnedNoFigure;

    /** {@code count} new bots, named from config.yml's bots.names, none sharing a name with anybody racing. */
    static List<Racer> make(Race race, int count) {
        Set<String> taken = new HashSet<>();
        for (Racer r : race.members.values()) taken.add(r.name.toLowerCase(Locale.ROOT));
        List<String> names = new ArrayList<>(race.settings().botNames);
        Collections.shuffle(names);
        List<String> skins = new ArrayList<>(List.of(SKINS));
        Collections.shuffle(skins);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Racer> out = new ArrayList<>();
        int spare = 1;
        for (int i = 0; i < count; i++) {
            String name = null;
            while (!names.isEmpty() && name == null) {
                String next = names.remove(0);
                if (taken.add(next.toLowerCase(Locale.ROOT))) name = next;
            }
            while (name == null) {
                String next = "Bot " + spare++;
                if (taken.add(next.toLowerCase(Locale.ROOT))) name = next;
            }
            Bot bot = new Bot(skins.get(i % skins.size()), 0.85 + rnd.nextDouble() * 0.15, (rnd.nextDouble() - 0.5) * 2.4);
            out.add(new Racer(UUID.randomUUID(), name, bot));
        }
        return out;
    }

    // ---- the figure ----------------------------------------------------------------------

    /** Its mannequin, if it has one in the world. */
    static Entity figure(Racer r) {
        if (r.bot == null || r.bot.figure == null) return null;
        Entity e = Bukkit.getEntity(r.bot.figure);
        return e != null && e.isValid() ? e : null;
    }

    /**
     * Its mannequin in its kart's driver's seat: summoned the first time,
     * carried again whenever it has come off (a rebuilt seat drops it).
     */
    static void seat(Race race, Racer r) {
        if (r.kart == null || !r.kart.isValid()) return;
        Entity figure = figure(r);
        if (figure == null) figure = summon(race, r);
        if (figure == null || figure.getVehicle() != null) return;
        Location at = r.kart.location();
        if (figure.getWorld() != at.getWorld() || figure.getLocation().distanceSquared(at) > 9) figure.teleport(at.clone().add(0, 1, 0));
        r.kart.carry(figure, 0);
    }

    /** Takes its mannequin out of the world. */
    static void remove(Racer r) {
        Entity figure = figure(r);
        if (figure != null) figure.remove();
        if (r.bot != null) r.bot.figure = null;
    }

    /**
     * A mannequin, by command rather than by API: the plugin is built against
     * Spigot 1.21.4, which has none, and only the command can give it one of the
     * game's own skins without a lookup. Tagged so a crash's leftovers are
     * swept on the next start.
     *
     * <p>Asked for once and then looked for: an entity a command summons is not
     * found by a search in the same tick, and taking that for a failure summoned
     * a second and a third. A server that has not produced one a few ticks on
     * is asked once more without the skin, and after that it has none.
     */
    private static Entity summon(Race race, Racer r) {
        Bot b = r.bot;
        Location at = r.kart.location().add(0, 1, 0);
        if (b.tag == null) b.tag = "gokarts_bot_" + r.id.toString().substring(0, 8);
        Entity found = find(at, b.tag);
        if (found != null) {
            b.figure = found.getUniqueId();
            if (found instanceof LivingEntity living && living.getEquipment() != null && r.colour != null) {
                ItemStack helmet = race.content().item("helmet_" + r.colour.id());
                if (helmet != null) living.getEquipment().setHelmet(helmet);
            }
            return found;
        }
        if (b.summons >= 2 && b.summonWait-- <= 0) {
            if (!warnedNoFigure) {
                warnedNoFigure = true;
                race.plugin.getLogger().info("Bots are racing with nobody to see in their seats: this server has no "
                        + "mannequins, which came with Minecraft 1.21.9.");
            }
            return null;
        }
        if (b.summonWait-- > 0) return null;
        String model = b.skin.startsWith("slim") ? "slim" : "wide";
        String name = r.name.replace("\\", "\\\\").replace("\"", "\\\"");
        String command = String.format(Locale.ROOT, "execute in %s run summon minecraft:mannequin %.3f %.3f %.3f {",
                at.getWorld().getKey(), at.getX(), at.getY(), at.getZ());
        if (b.summons == 0) {
            command += "profile:{texture:\"minecraft:entity/player/" + b.skin + "\",model:\"" + model + "\"},";
        }
        command += "Tags:[\"gokarts\",\"gokarts_bot\",\"" + b.tag + "\"],hide_description:1b,immovable:1b,"
                + "Invulnerable:1b,Silent:1b,CustomName:\"" + name + "\",CustomNameVisible:1b}";
        b.summons++;
        b.summonWait = 5;
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (RuntimeException ignored) {
            // Looked for, and asked again without the skin, like any other failure.
        }
        return null;
    }

    private static Entity find(Location at, String tag) {
        for (Entity e : at.getWorld().getNearbyEntities(at, 6, 6, 6)) {
            if (e.getScoreboardTags().contains(tag)) return e;
        }
        return null;
    }

    // ---- the driving ---------------------------------------------------------------------

    /** Nothing pressed: a bot on the grid, frozen, or between karts. */
    static final VehicleInput NOTHING = new VehicleInput(true, 0, 0, false, false, false, false, false, false, false);

    /** One tick of one bot's driving. */
    static void drive(Race race, Racer r, List<double[]> line) {
        Vehicle v = r.kart;
        Bot b = r.bot;
        if (v == null || !v.isValid()) return;
        if (line.isEmpty() || r.frozen > 0 || r.respawnIn > 0) {
            v.drive(NOTHING);
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Location at = v.location();
        int n = line.size();
        b.index = nearest(line, at, b.index);
        double speed = Math.abs(v.groundSpeed());
        boolean airborne = v.is(VehicleState.AIRBORNE);

        // Where to steer: a point up the line, further the faster, and to its own side of it.
        int ahead = (int) Math.round((4 + speed * 0.38 * b.skill) / STEP);
        double[] t = line.get((b.index + ahead) % n);
        double[] t2 = line.get((b.index + ahead + 1) % n);
        double dx = t2[0] - t[0], dz = t2[2] - t[2];
        double len = Math.max(1e-6, Math.hypot(dx, dz));
        double bend = Math.abs(wrap(yaw(line, b.index + 4) - yaw(line, b.index + 4 + (int) (18 / STEP))));
        // Into a bend it cuts back to the middle, where the road is surely road.
        double lane = b.lane * Math.max(0, 1 - bend / 60);
        double tx = t[0] + (-dz / len) * lane, tz = t[2] + (dx / len) * lane;
        double want = Math.toDegrees(Math.atan2(-(tx - at.getX()), tz - at.getZ()));
        double diff = wrap(want - v.heading());

        double steer = clamp(diff / 20.0 + rnd.nextGaussian() * 0.04 * (1.1 - b.skill), -1, 1);
        double throttle = 1;
        if (Math.abs(diff) > 75) throttle = 0.35;
        else if (bend > 80 && speed > race.settings().cruiseSpeed * 0.8) throttle = 0.55;
        boolean jump = false;

        // Drifting: the handbrake held through a bend worth it, let go for the boost.
        double driftMin = race.settings().driftMinSpeed;
        if (b.drifting) {
            b.driftTicks++;
            boolean done = airborne || bend < 12 || b.driftTicks > 45 || speed < driftMin * 0.6
                    || (Math.signum(diff) != b.driftSide && Math.abs(diff) > 12);
            if (done) {
                b.drifting = false;
            } else {
                jump = true;
            }
        } else if (!airborne && bend > 30 && Math.abs(diff) > 6 && speed > driftMin + 1
                && rnd.nextDouble() < 0.08 * b.skill) {
            b.drifting = true;
            b.driftTicks = 0;
            b.driftSide = Math.signum(diff);
            jump = true;
        }

        // A trick off a jump: space a few ticks after leaving the ground, most of the time.
        if (airborne) {
            b.airTicks++;
            if (b.airTicks == 1) b.tricking = rnd.nextDouble() < 0.5 + 0.4 * b.skill;
            if (b.tricking && b.airTicks >= 4 && b.airTicks <= 6) jump = true;
        } else {
            b.airTicks = 0;
        }

        // Stuck against something: back out, turning away, and try again.
        if (b.reverseTicks > 0) {
            b.reverseTicks--;
            throttle = -1;
            steer = -Math.signum(diff);
            jump = false;
        } else if (r.stillTicks > 25 && !airborne) {
            b.reverseTicks = 18;
            b.drifting = false;
        }

        // Its item, after a moment's thought; again later if it had no target.
        if (r.item != null && r.roulette == 0) {
            if (b.itemAt < 0) b.itemAt = race.clock + 15 + rnd.nextInt((int) Math.round(70 * (1.6 - b.skill)));
            else if (race.clock >= b.itemAt) {
                b.itemAt = -1;
                race.useItem(r);
            }
        } else {
            b.itemAt = -1;
        }

        v.drive(new VehicleInput(true, throttle, steer, throttle > 0, throttle < 0,
                steer < -0.15, steer > 0.15, jump, false, false));
    }

    /**
     * The line's point nearest the kart: near where it was last tick, and by
     * height too, so the figure of eight's crossing is never mistaken for the
     * road over it.
     */
    static int nearest(List<double[]> line, Location at, int from) {
        int n = line.size();
        int best = -1;
        double bestD = Double.MAX_VALUE;
        if (from >= 0) {
            for (int k = -8; k <= 40; k++) {
                int i = Math.floorMod(from + k, n);
                double d = distance2(line.get(i), at);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            if (bestD < 20 * 20) return best;
        }
        for (int i = 0; i < n; i++) {
            double d = distance2(line.get(i), at);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static double distance2(double[] p, Location at) {
        double dx = p[0] - at.getX(), dy = (p[1] - at.getY()) * 2, dz = p[2] - at.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The line's heading at a sample, as a Minecraft yaw. */
    private static double yaw(List<double[]> line, int i) {
        int n = line.size();
        double[] a = line.get(Math.floorMod(i, n)), b = line.get(Math.floorMod(i + 1, n));
        return Math.toDegrees(Math.atan2(-(b[0] - a[0]), b[2] - a[2]));
    }

    static double wrap(double degrees) {
        double d = degrees % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
