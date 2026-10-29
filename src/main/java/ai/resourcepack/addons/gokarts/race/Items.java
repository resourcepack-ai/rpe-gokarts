package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.config.Settings;
import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The item boxes on the track, and what comes out of them.
 *
 * <ul>
 *   <li><b>Boxes</b> float over the road, turning. Driving through one smashes
 *   it (it is back a few seconds later) and, if your hands are empty, spins
 *   the roulette: what you get depends on where you are, the leader getting
 *   bananas to defend with and the back of the field rockets and nitro to
 *   catch up with ({@code items.odds}).</li>
 *   <li><b>Nitro</b> is a boost; <b>triple nitro</b> is three.</li>
 *   <li><b>A banana</b> is dropped behind you, and whoever drives over it
 *   (you too, once you have pulled away) spins out.</li>
 *   <li><b>A rocket</b> flies at the kart one place ahead of you and throws it
 *   in the air. The leader's flies straight on.</li>
 * </ul>
 *
 * <p>Everything here is an item display wearing one of the content folder's
 * Studio models, tagged {@code gokarts} and never saved, so nothing outlives
 * the race (or a crash: the plugin sweeps the tag on start).
 */
final class Items {
    static final String TAG = "gokarts";

    private final class Box {
        final Location at;
        ItemDisplay display;
        long backAt = -1;

        Box(Location at) {
            this.at = at;
        }
    }

    private record Banana(ItemDisplay display, Location at, Racer owner, long dropped) {
    }

    private static final class Rocket {
        ItemDisplay display;
        Vector pos;
        Vector dir;
        Racer owner;
        Racer target;
        /** The road it was fired from: it flies {@link #CRUISE} above it. */
        double floor;
        int age;
    }

    /** How high a rocket flies over the road: above the tyre walls, which are two blocks. */
    private static final double CRUISE = 2.4;

    private final Race race;
    private final List<Box> boxes = new ArrayList<>();
    private final List<Banana> bananas = new ArrayList<>();
    private final List<Rocket> rockets = new ArrayList<>();
    private long tick;

    Items(Race race) {
        this.race = race;
    }

    private Settings settings() {
        return race.settings();
    }

    /** The boxes go out as the grid forms. */
    void start() {
        clear();
        if (!settings().items) return;
        World w = race.track.world();
        for (Vector v : race.track.boxes()) {
            Box b = new Box(new Location(w, v.getX(), v.getY(), v.getZ()));
            b.display = spawn(b.at, "item_box", 0.8f);
            boxes.add(b);
        }
    }

    void clear() {
        for (Box b : boxes) if (b.display != null) b.display.remove();
        for (Banana b : bananas) b.display.remove();
        for (Rocket r : rockets) r.display.remove();
        boxes.clear();
        bananas.clear();
        rockets.clear();
    }

    void tick() {
        tick++;
        tickBoxes();
        tickRoulettes();
        tickBananas();
        tickRockets();
    }

    // ---- boxes -----------------------------------------------------------------------

    private void tickBoxes() {
        for (Box b : boxes) {
            if (b.display == null) {
                if (tick >= b.backAt) {
                    b.display = spawn(b.at, "item_box", 0.8f);
                    b.at.getWorld().spawnParticle(Particle.END_ROD, b.at, 6, 0.2, 0.2, 0.2, 0.02);
                }
                continue;
            }
            // Turning and bobbing: the display interpolates between these.
            Location l = b.at.clone().add(0, Math.sin(tick * 0.12) * 0.12, 0);
            l.setYaw((tick * 6) % 360);
            b.display.teleport(l);
            for (Racer r : race.order) {
                if (r.kart == null || r.finished || !near(r.kart.location(), b.at, 1.8, 2.2)) continue;
                smash(b);
                if (r.item == null && r.roulette == 0) {
                    r.item = roll(r);
                    r.itemUses = r.item.uses;
                    r.roulette = settings().rouletteTicks;
                    race.showItem(r);
                }
                break;
            }
        }
    }

    private void smash(Box b) {
        race.content().sound(b.at, "smash", 1f, 1f, Sound.BLOCK_GLASS_BREAK);
        b.at.getWorld().spawnParticle(Particle.FIREWORK, b.at, 14, 0.3, 0.3, 0.3, 0.12);
        b.display.remove();
        b.display = null;
        b.backAt = tick + settings().boxRespawnTicks;
    }

    private void tickRoulettes() {
        for (Racer r : race.order) {
            if (r.roulette <= 0) continue;
            if (--r.roulette == 0) {
                Player p = r.player();
                if (p != null) race.content().sound(p, "item_got", 1f, 1f, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                race.showItem(r);
            }
        }
    }

    /** The item a box gives, by place: config.yml's odds rows run from the leader to last. */
    private Item roll(Racer r) {
        List<Map<String, Integer>> rows = settings().odds;
        int field = Math.max(1, race.order.size());
        double share = field == 1 ? 0.5 : (Math.max(1, r.position) - 1) / (double) (field - 1);
        Map<String, Integer> row = rows.get((int) Math.round(share * (rows.size() - 1)));
        int total = 0;
        for (Map.Entry<String, Integer> e : row.entrySet()) if (Item.parse(e.getKey()) != null) total += e.getValue();
        if (total <= 0) return Item.NITRO;
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<String, Integer> e : row.entrySet()) {
            Item item = Item.parse(e.getKey());
            if (item == null) continue;
            pick -= e.getValue();
            if (pick < 0) return item;
        }
        return Item.NITRO;
    }

    // ---- using one ---------------------------------------------------------------------

    void use(Racer r) {
        if (r.item == null || r.roulette > 0 || r.kart == null || r.frozen > 0) return;
        Item item = r.item;
        switch (item) {
            case NITRO, TRIPLE_NITRO -> race.plugin.driving().boost(r.kart, settings().nitroBoost);
            case BANANA -> dropBanana(r);
            case ROCKET -> fireRocket(r);
        }
        if (--r.itemUses <= 0) r.item = null;
        race.showItem(r);
    }

    private void dropBanana(Racer r) {
        Location at = r.kart.pointOn(0, 0, -1.4);
        // On the road, wherever behind the kart that is.
        Block below = at.getBlock();
        for (int i = 0; i < 3 && below.isPassable(); i++) below = below.getRelative(0, -1, 0);
        at.setY(below.getY() + 1 + 0.35);
        at.setYaw(ThreadLocalRandom.current().nextFloat() * 360);
        ItemDisplay d = spawn(at, "banana", 1f);
        d.teleport(at);
        bananas.add(new Banana(d, at, r, tick));
        at.getWorld().playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.7f);
    }

    private void fireRocket(Racer r) {
        Rocket k = new Rocket();
        // A homing rocket starts above the tyre walls, so the first bend does not take it.
        Location start = r.kart.pointOn(0, r.position > 1 ? CRUISE : 0.7, 1.3);
        k.pos = start.toVector();
        k.floor = r.kart.location().getY();
        double yaw = Math.toRadians(r.kart.heading());
        k.dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        k.owner = r;
        k.target = r.position > 1 && r.position - 2 < race.order.size() ? race.order.get(r.position - 2) : null;
        k.display = spawn(start, "rocket", 1f);
        rockets.add(k);
        race.content().sound(start, "launch", 1f, 1f, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH);
    }

    // ---- hazards in flight -------------------------------------------------------------

    private void tickBananas() {
        for (Iterator<Banana> it = bananas.iterator(); it.hasNext(); ) {
            Banana b = it.next();
            for (Racer r : race.order) {
                if (r.kart == null || r.finished) continue;
                if (r == b.owner && tick - b.dropped < 25) continue;
                if (!near(r.kart.location(), b.at, 1.1, 1.6)) continue;
                spinOut(r);
                b.display.remove();
                it.remove();
                break;
            }
        }
    }

    private void spinOut(Racer r) {
        Vehicle v = r.kart;
        race.plugin.driving().stun(v, settings().bananaStunTicks);
        v.spin(ThreadLocalRandom.current().nextBoolean() ? 720 : -720);
        race.content().sound(v.location(), "spinout", 1f, 1f, Sound.ENTITY_ITEM_BREAK);
        Player p = r.player();
        if (p != null) Text.send(p, race.messages().component("hit-banana"));
    }

    private void tickRockets() {
        double speed = settings().rocketSpeed;
        for (Iterator<Rocket> it = rockets.iterator(); it.hasNext(); ) {
            Rocket k = it.next();
            k.age++;
            Location here = k.pos.toLocation(k.display.getWorld());
            if (k.target != null && k.target.kart != null && k.target.kart.isValid() && !k.target.finished) {
                // Over the tyre walls (two blocks) until it is nearly there, then down onto the kart.
                Location aim = k.target.kart.location();
                double across = Math.hypot(aim.getX() - k.pos.getX(), aim.getZ() - k.pos.getZ());
                Vector want = aim.add(0, across > 4 ? CRUISE : 0.6, 0).toVector().subtract(k.pos);
                if (want.lengthSquared() > 1e-6) {
                    k.dir = k.dir.multiply(0.6).add(want.normalize().multiply(0.4)).normalize();
                }
            } else {
                // Nobody to chase (the leader's): straight on at kart height, into whatever is ahead.
                double drop = (k.floor + 0.7) - k.pos.getY();
                k.dir = k.dir.clone().setY(0).normalize().setY(Math.max(-0.5, Math.min(0.5, drop * 0.5))).normalize();
            }
            k.pos.add(k.dir.clone().multiply(speed));
            Location next = k.pos.toLocation(here.getWorld());
            next.setDirection(k.dir);
            // The model's nose is its -z, which is the front of a display turned to face its heading.
            k.display.teleport(next);
            here.getWorld().spawnParticle(Particle.FLAME, here, 2, 0.05, 0.05, 0.05, 0.01);
            here.getWorld().spawnParticle(Particle.SMOKE, here, 1, 0.05, 0.05, 0.05, 0.01);

            Racer hit = null;
            for (Racer r : race.order) {
                if (r.kart == null || r.finished || (r == k.owner && k.age < 15)) continue;
                if (near(r.kart.location().add(0, 0.5, 0), next, 1.4, 1.6)) {
                    hit = r;
                    break;
                }
            }
            boolean wall = !next.getBlock().isPassable();
            if (hit != null || wall || k.age > settings().rocketTicks) {
                blast(next, hit, k.owner);
                k.display.remove();
                it.remove();
            }
        }
    }

    private void blast(Location at, Racer hit, Racer owner) {
        at.getWorld().spawnParticle(Particle.EXPLOSION, at, 1);
        at.getWorld().spawnParticle(Particle.FLAME, at, 20, 0.4, 0.3, 0.4, 0.08);
        race.content().sound(at, "blast", 1f, 1f, Sound.ENTITY_GENERIC_EXPLODE);
        if (hit == null) return;
        Vehicle v = hit.kart;
        race.plugin.driving().stun(v, settings().rocketStunTicks);
        v.applyImpulse(new Vector(0, 6.5, 0), ThreadLocalRandom.current().nextBoolean() ? 540 : -540);
        Player victim = hit.player();
        if (victim != null) Text.send(victim, race.messages().component("hit-rocket", "name", owner.name));
        Player shooter = owner.player();
        if (shooter != null && owner != hit) Text.send(shooter, race.messages().component("got-rocket", "name", hit.name));
    }

    // ---- helpers ---------------------------------------------------------------------

    private static boolean near(Location a, Location b, double across, double up) {
        if (a.getWorld() != b.getWorld()) return false;
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz <= across * across && Math.abs(a.getY() - b.getY()) <= up;
    }

    private ItemDisplay spawn(Location at, String item, float scale) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setItemStack(race.content().item(item));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTeleportDuration(2);
            d.setViewRange(1.5f);
            if (scale != 1f) {
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(scale, scale, scale), new AxisAngle4f()));
            }
        });
    }
}
