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
 *   <li><b>Nitro</b> is a boost; <b>triple nitro</b> is three; <b>overdrive</b>
 *   is a few seconds in which every press is one.</li>
 *   <li><b>A banana</b> is dropped behind you, and whoever drives over it
 *   (you too, once you have pulled away) spins out. <b>A banana bunch</b> is
 *   three. <b>A decoy box</b> is dropped the same way and looks like an item
 *   box, except that it does not turn.</li>
 *   <li><b>A rocket</b> flies at the kart one place ahead of you and throws it
 *   in the air. The leader's flies straight on.</li>
 *   <li><b>A bumper orb</b> rolls along the road ahead of you, ricocheting off
 *   the walls, and spins out whoever it meets.</li>
 *   <li><b>A storm drone</b> flies over everything to the leader (never to
 *   whoever sent it), hovers, and strikes everybody round them.</li>
 *   <li><b>A hyper shield</b> takes every hit for a few seconds, lets the kart
 *   run a little faster, and spins out whoever it touches.</li>
 *   <li><b>A smoke bomb</b> blinds everybody ahead of you.</li>
 *   <li><b>A shockwave horn</b> throws back the karts round you and clears the
 *   bananas, decoys, orbs and rockets near you, and a drone coming for you.</li>
 *   <li><b>A static surge</b> takes everybody else's item and slows them.</li>
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

    private record Decoy(ItemDisplay display, Location at, Racer owner, long dropped) {
    }

    private static final class Orb {
        ItemDisplay display;
        double x, z;
        /** The road under it, which it rolls along: up half-block steps, off edges. */
        double ground;
        double dx, dz;
        Racer owner;
        int age;
        int bounces;
    }

    private static final class Drone {
        ItemDisplay display;
        Vector pos;
        Racer owner;
        Racer target;
        int age;
        /** Ticks hovering over its target before it strikes; -1 while it is still on its way. */
        int hover = -1;
    }

    /** How high a rocket flies over the road: above the tyre walls, which are two blocks. */
    private static final double CRUISE = 2.4;
    /** An orb's centre over the road, and how far a drone flies over its target on the way. */
    private static final double ORB_LIFT = 0.45, DRONE_CRUISE = 7;
    /** How long a drone hovers over its target, warning them, before it strikes. */
    private static final int DRONE_HOVER = 20;

    private final Race race;
    private final List<Box> boxes = new ArrayList<>();
    /**
     * Where Studio drew these two, in blocks ahead of whoever casts them, so
     * that played on a kart they can be pulled back onto it: the box breaks
     * round the kart that drove through it, the rocket explodes on its target.
     */
    static final double BOX_BREAK_AHEAD = -2.7, EXPLOSION_AHEAD = -5;
    private final List<Banana> bananas = new ArrayList<>();
    private final List<Rocket> rockets = new ArrayList<>();
    private final List<Decoy> decoys = new ArrayList<>();
    private final List<Orb> orbs = new ArrayList<>();
    private final List<Drone> drones = new ArrayList<>();
    /** When each kart may next be spun out by a shield it touches: one touch, one spin. */
    private final Map<java.util.UUID, Long> shieldBumped = new java.util.HashMap<>();
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
        for (Decoy d : decoys) d.display.remove();
        for (Orb o : orbs) o.display.remove();
        for (Drone d : drones) d.display.remove();
        boxes.clear();
        bananas.clear();
        rockets.clear();
        decoys.clear();
        orbs.clear();
        drones.clear();
        shieldBumped.clear();
    }

    void tick() {
        tick++;
        tickBoxes();
        tickRoulettes();
        tickBananas();
        tickRockets();
        tickDecoys();
        tickOrbs();
        tickDrones();
        tickOverdrives();
        tickShields();
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
            if (!b.display.isValid()) {
                // Its chunk unloaded with nobody near, which takes a display
                // with it: drawn again once the chunk is back, or it was an
                // invisible box that still handed out items.
                if (!loaded(b.at)) continue;
                b.display = spawn(b.at, "item_box", 0.8f);
            }
            // Turning and bobbing: the display interpolates between these.
            Location l = b.at.clone().add(0, Math.sin(tick * 0.12) * 0.12, 0);
            l.setYaw((tick * 6) % 360);
            b.display.teleport(l);
            for (Racer r : race.order) {
                if (r.kart == null || r.finished || !near(r.kart.location(), b.at, 1.8, 2.2)) continue;
                smash(b, r);
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

    /** A box driven through: it breaks, and the burst rides away with the kart that broke it. */
    private void smash(Box b, Racer by) {
        race.content().sound(b.at, "smash", 1f, 1f, Sound.BLOCK_GLASS_BREAK);
        if (by != null && by.kart != null) race.content().vfx("gk_box_break", by.kart, by.player(), BOX_BREAK_AHEAD);
        else race.content().vfx("gk_box_break", b.at, null);
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
            case BANANA, BANANA_BUNCH -> {
                // Nowhere to put it (over a gap, off the edge): kept for later.
                if (!dropBanana(r)) return;
            }
            case DECOY -> {
                if (!dropDecoy(r)) return;
            }
            case ROCKET -> fireRocket(r);
            case ORB -> fireOrb(r);
            case DRONE -> {
                if (!fireDrone(r)) return;
            }
            case OVERDRIVE -> {
                // Not used up by a press: it runs out by the clock (tickOverdrives).
                overdrive(r);
                race.showItem(r);
                return;
            }
            case SHIELD -> shield(r);
            case SMOKE -> {
                if (!smoke(r)) return;
            }
            case HORN -> horn(r);
            case SURGE -> surge(r);
        }
        if (--r.itemUses <= 0) r.item = null;
        race.showItem(r);
    }

    private boolean dropBanana(Racer r) {
        Location at = r.kart.pointOn(0, 0, -1.4);
        // On the road, wherever behind the kart that is: down to the track's
        // floor, so one dropped in the air off a jump lands rather than hangs.
        Block below = at.getBlock();
        int floor = (int) Math.floor(race.track.floor());
        while (below.isPassable() && !below.isLiquid() && below.getY() > floor) below = below.getRelative(0, -1, 0);
        if (below.isPassable() || below.isLiquid()) return false;
        // Lying on what is there, a slab's top included: the model is drawn
        // round its centre, half a block above its own bottom, and it was
        // half sunk into a full block and floating over a slab.
        double surface = below.isPassable() ? below.getY() + 1 : below.getBoundingBox().getMaxY();
        at.setY(surface + 0.5 + 0.01);
        at.setYaw(ThreadLocalRandom.current().nextFloat() * 360);
        ItemDisplay d = spawn(at, "banana", 1f);
        d.teleport(at);
        bananas.add(new Banana(d, at, r, tick));
        at.getWorld().playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.7f);
        return true;
    }

    private static boolean loaded(Location at) {
        return at.getWorld() != null && at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4);
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
        for (java.util.ListIterator<Banana> it = bananas.listIterator(); it.hasNext(); ) {
            Banana b = it.next();
            if (!b.display.isValid() && loaded(b.at)) {
                // The same as a box: a banana nobody could see still spun karts out.
                ItemDisplay d = spawn(b.at, "banana", 1f);
                d.teleport(b.at);
                b = new Banana(d, b.at, b.owner, b.dropped);
                it.set(b);
            }
            for (Racer r : race.order) {
                if (r.kart == null || r.finished) continue;
                if (r == b.owner && tick - b.dropped < 25) continue;
                if (!near(r.kart.location(), b.at, 1.1, 1.6)) continue;
                spinOut(r, b.owner);
                b.display.remove();
                it.remove();
                break;
            }
        }
    }

    private void spinOut(Racer r, Racer owner) {
        spin(r, owner, settings().bananaStunTicks, "hit-banana", "feed-banana");
    }

    /**
     * A hit that spins a kart round where it is: a banana, a decoy, an orb, a
     * shield. {@code hit} is what its driver is told, {@code feed} what
     * everybody reads across the top.
     */
    private void spin(Racer r, Racer owner, int stunTicks, String hit, String feed) {
        if (r.kart == null || blocked(r)) return;
        Vehicle v = r.kart;
        race.plugin.driving().stun(v, stunTicks);
        v.spin(ThreadLocalRandom.current().nextBoolean() ? 720 : -720);
        race.content().sound(v.location(), "spinout", 1f, 1f, Sound.ENTITY_ITEM_BREAK);
        daze(r);
        Player p = r.player();
        if (p != null) Text.send(p, race.messages().component(hit, "owner", owner.name));
        race.feed(race.messages().plain(feed, "victim", r.name, "owner", owner.name));
    }

    /** A hit that throws a kart up in the air: a storm drone. */
    private void thrown(Racer r, Racer owner, int stunTicks, double up, String hit, String feed) {
        if (r.kart == null || blocked(r)) return;
        Vehicle v = r.kart;
        race.plugin.driving().stun(v, stunTicks);
        v.applyImpulse(new Vector(0, up, 0), ThreadLocalRandom.current().nextBoolean() ? 540 : -540);
        daze(r);
        Player p = r.player();
        if (p != null) Text.send(p, race.messages().component(hit, "owner", owner.name));
        race.feed(race.messages().plain(feed, "victim", r.name, "owner", owner.name));
    }

    /** Whether a hit on this racer is taken by a hyper shield, which says so. */
    private boolean blocked(Racer r) {
        if (r.kart == null || !race.plugin.driving().shielded(r.kart)) return false;
        Location at = r.kart.location().add(0, 1, 0);
        at.getWorld().playSound(at, Sound.ITEM_SHIELD_BLOCK, 1f, 1.3f);
        at.getWorld().spawnParticle(Particle.END_ROD, at, 14, 0.7, 0.5, 0.7, 0.06);
        Player p = r.player();
        if (p != null) Text.send(p, race.messages().component("blocked"));
        return true;
    }

    /** Stars round the driver's head and the driver reeling for a moment. */
    private void daze(Racer r) {
        Player p = r.player();
        if (r.kart != null && r.isBot()) race.content().vfx("gk_dizzy", r.kart, null);
        if (p == null || r.kart == null) return;
        race.content().vfx("gk_dizzy", r.kart, p);
        String emote = "gokarts_" + r.colour.id() + "_dazed";
        r.kart.dress(p, emote);
        org.bukkit.Bukkit.getScheduler().runTaskLater(race.plugin, () -> {
            if (r.kart != null && r.kart.isValid() && !r.finished) r.kart.undress(p);
        }, 30L);
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
        // On the kart it hit, which is thrown and carries it; a miss stays put.
        if (hit != null && hit.kart != null) race.content().vfx("gk_explosion", hit.kart, hit.player(), EXPLOSION_AHEAD);
        else race.content().vfx("gk_explosion", at, null);
        at.getWorld().spawnParticle(Particle.FLAME, at, 20, 0.4, 0.3, 0.4, 0.08);
        race.content().sound(at, "blast", 1f, 1f, Sound.ENTITY_GENERIC_EXPLODE);
        if (hit == null || blocked(hit)) return;
        Vehicle v = hit.kart;
        race.plugin.driving().stun(v, settings().rocketStunTicks);
        v.applyImpulse(new Vector(0, 6.5, 0), ThreadLocalRandom.current().nextBoolean() ? 540 : -540);
        daze(hit);
        race.feed(race.messages().plain("feed-rocket", "shooter", owner.name, "victim", hit.name));
        Player victim = hit.player();
        if (victim != null) Text.send(victim, race.messages().component("hit-rocket", "name", owner.name));
        Player shooter = owner.player();
        if (shooter != null && owner != hit) Text.send(shooter, race.messages().component("got-rocket", "name", hit.name));
    }

    // ---- the decoy box ----------------------------------------------------------------

    /**
     * Dropped behind like a banana, at an item box's height, and it does not
     * turn: that is the tell. Further back than a banana, because a decoy is
     * hit from as far as a real box is (1.8): at 1.6 a kart that had not moved
     * drove into its own once it stopped being immune.
     */
    private boolean dropDecoy(Racer r) {
        Location at = r.kart.pointOn(0, 0, -2.8);
        Block below = at.getBlock();
        int floor = (int) Math.floor(race.track.floor());
        while (below.isPassable() && !below.isLiquid() && below.getY() > floor) below = below.getRelative(0, -1, 0);
        if (below.isPassable() || below.isLiquid()) return false;
        at.setY(below.getBoundingBox().getMaxY() + 1.0);
        at.setYaw(ThreadLocalRandom.current().nextFloat() * 360);
        at.setPitch(0);
        ItemDisplay d = spawn(at, "item_box", 0.8f);
        d.teleport(at);
        decoys.add(new Decoy(d, at, r, tick));
        at.getWorld().playSound(at, Sound.ENTITY_ITEM_PICKUP, 0.6f, 0.5f);
        return true;
    }

    private void tickDecoys() {
        for (java.util.ListIterator<Decoy> it = decoys.listIterator(); it.hasNext(); ) {
            Decoy d = it.next();
            if (!d.display.isValid() && loaded(d.at)) {
                ItemDisplay again = spawn(d.at, "item_box", 0.8f);
                again.teleport(d.at);
                d = new Decoy(again, d.at, d.owner, d.dropped);
                it.set(d);
            }
            for (Racer r : race.order) {
                if (r.kart == null || r.finished) continue;
                if (r == d.owner && tick - d.dropped < 25) continue;
                if (!near(r.kart.location(), d.at, 1.8, 2.2)) continue;
                race.content().sound(d.at, "smash", 1f, 0.7f, Sound.BLOCK_GLASS_BREAK);
                d.at.getWorld().spawnParticle(Particle.LARGE_SMOKE, d.at, 12, 0.3, 0.3, 0.3, 0.03);
                spin(r, d.owner, settings().bananaStunTicks, "hit-decoy", "feed-decoy");
                d.display.remove();
                it.remove();
                break;
            }
        }
    }

    // ---- the bumper orb ----------------------------------------------------------------

    private void fireOrb(Racer r) {
        Orb o = new Orb();
        Location l = r.kart.location();
        double yaw = Math.toRadians(r.kart.heading());
        o.dx = -Math.sin(yaw);
        o.dz = Math.cos(yaw);
        o.x = l.getX() + o.dx * 1.8;
        o.z = l.getZ() + o.dz * 1.8;
        o.ground = l.getY();
        o.owner = r;
        Location at = new Location(l.getWorld(), o.x, o.ground + ORB_LIFT, o.z);
        o.display = spawn(at, "orb", 1f);
        o.display.setBillboard(Display.Billboard.CENTER);
        orbs.add(o);
        at.getWorld().playSound(at, Sound.ENTITY_SLIME_JUMP, 1f, 1.4f);
    }

    /**
     * Rolls each orb along the road: up half-block steps, over the edge of
     * anything it runs off, and back off any wall a full step or more high,
     * the way it hit it (the axis it hit, or both in a corner).
     */
    private void tickOrbs() {
        double speed = settings().orbSpeed;
        int steps = (int) Math.ceil(speed / 0.4);
        double sub = speed / steps;
        for (Iterator<Orb> it = orbs.iterator(); it.hasNext(); ) {
            Orb o = it.next();
            o.age++;
            World w = o.display.getWorld();
            for (int i = 0; i < steps; i++) {
                double nx = o.x + o.dx * sub, nz = o.z + o.dz * sub;
                if (wall(surface(w, nx, nz, o.ground), o.ground)) {
                    boolean wx = wall(surface(w, nx, o.z, o.ground), o.ground);
                    boolean wz = wall(surface(w, o.x, nz, o.ground), o.ground);
                    if (wx) o.dx = -o.dx;
                    if (wz) o.dz = -o.dz;
                    if (!wx && !wz) {
                        o.dx = -o.dx;
                        o.dz = -o.dz;
                    }
                    o.bounces++;
                    w.playSound(new Location(w, o.x, o.ground, o.z), Sound.BLOCK_SLIME_BLOCK_HIT, 1f, 1.3f);
                    continue;
                }
                double s = surface(w, nx, nz, o.ground);
                o.x = nx;
                o.z = nz;
                o.ground = Double.isNaN(s) ? o.ground - 0.5 : s;
            }
            Location at = new Location(w, o.x, o.ground + ORB_LIFT, o.z);
            o.display.teleport(at);
            w.spawnParticle(Particle.DUST, at, 2, 0.15, 0.1, 0.15, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0x3DE0C8), 1.2f));
            boolean gone = o.age > settings().orbTicks || o.bounces > settings().orbBounces || o.ground < race.track.floor();
            Racer hit = null;
            for (Racer r : race.order) {
                if (r.kart == null || r.finished || (r == o.owner && o.age < 15)) continue;
                if (near(r.kart.location().add(0, ORB_LIFT, 0), at, 1.3, 1.4)) {
                    hit = r;
                    break;
                }
            }
            if (hit != null) {
                spin(hit, o.owner, settings().orbStunTicks, "hit-orb", "feed-orb");
                gone = true;
            }
            // An orb and a banana or a decoy take each other out.
            if (!gone && (clearNear(bananas, b -> b.display(), b -> b.at(), at, 1.1) || clearNear(decoys, d -> d.display(), d -> d.at(), at, 1.4))) gone = true;
            if (gone) {
                w.spawnParticle(Particle.POOF, at, 8, 0.2, 0.2, 0.2, 0.03);
                o.display.remove();
                it.remove();
            }
        }
    }

    /**
     * The top of the ground at (x, z), looking from a little over {@code ground}
     * down three blocks; NaN over a gap. Anything solid as high as 1.2 over the
     * ground is there too, so a wall reads as a step too high to climb.
     */
    private static double surface(World w, double x, double z, double ground) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int bottom = (int) Math.floor(ground - 3);
        for (int by = (int) Math.floor(ground + 1.2); by >= bottom; by--) {
            Block b = w.getBlockAt(bx, by, bz);
            if (b.isPassable() || b.isLiquid()) continue;
            return b.getBoundingBox().getMaxY();
        }
        return Double.NaN;
    }

    /** A step up of more than a slab: something to bounce off rather than roll up. */
    private static boolean wall(double surface, double ground) {
        return !Double.isNaN(surface) && surface - ground > 0.6;
    }

    /** Removes the first of {@code list} within {@code across} of {@code at}, display and all; whether one was. */
    private static <T> boolean clearNear(List<T> list, java.util.function.Function<T, ItemDisplay> display,
                                         java.util.function.Function<T, Location> where, Location at, double across) {
        for (Iterator<T> it = list.iterator(); it.hasNext(); ) {
            T t = it.next();
            if (!near(where.apply(t), at, across, 1.6)) continue;
            display.apply(t).remove();
            it.remove();
            return true;
        }
        return false;
    }

    // ---- the storm drone ---------------------------------------------------------------

    /** The racer out in front who is not {@code not}: a drone's target. */
    private Racer leader(Racer not) {
        for (Racer o : race.order) {
            if (o != not && !o.finished && o.kart != null && o.kart.isValid()) return o;
        }
        return null;
    }

    private boolean fireDrone(Racer r) {
        Racer target = leader(r);
        if (target == null) {
            Player p = r.player();
            if (p != null) Text.send(p, race.messages().component("no-target"));
            return false;
        }
        Drone d = new Drone();
        d.owner = r;
        d.target = target;
        Location at = r.kart.location().add(0, 2, 0);
        d.pos = at.toVector();
        d.display = spawn(at, "drone", 1.3f);
        d.display.setBillboard(Display.Billboard.CENTER);
        drones.add(d);
        at.getWorld().playSound(at, Sound.ENTITY_PHANTOM_FLAP, 1f, 1.6f);
        Player p = target.player();
        if (p != null) {
            Text.send(p, race.messages().component("drone-coming"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BIT, 1f, 0.6f);
        }
        return true;
    }

    /**
     * Each drone climbs over everything and flies at its target; over them it
     * hovers for a second, coming down and beeping, and strikes. A target that
     * finishes or drops out hands it on to whoever leads now.
     */
    private void tickDrones() {
        double speed = settings().droneSpeed;
        for (Iterator<Drone> it = drones.iterator(); it.hasNext(); ) {
            Drone d = it.next();
            d.age++;
            if (d.target == null || d.target.finished || d.target.kart == null || !d.target.kart.isValid()) {
                d.target = leader(d.owner);
            }
            World w = d.display.getWorld();
            if (d.target == null || d.age > 20 * 20) {
                w.spawnParticle(Particle.POOF, d.pos.toLocation(w), 10, 0.3, 0.3, 0.3, 0.03);
                d.display.remove();
                it.remove();
                continue;
            }
            Location t = d.target.kart.location();
            double across = Math.hypot(t.getX() - d.pos.getX(), t.getZ() - d.pos.getZ());
            if (d.hover < 0) {
                Vector step = new Vector(t.getX() - d.pos.getX(), 0, t.getZ() - d.pos.getZ());
                if (step.lengthSquared() > 1e-6) step.normalize().multiply(Math.min(speed, across));
                double climb = Math.max(-0.8, Math.min(0.8, t.getY() + DRONE_CRUISE - d.pos.getY()));
                d.pos.add(step).setY(d.pos.getY() + climb);
                if (across < 2.5) d.hover = 0;
            } else {
                d.hover++;
                d.pos = new Vector(t.getX(), t.getY() + 6 - 4 * Math.min(1, d.hover / (double) DRONE_HOVER), t.getZ());
                if (d.hover % 4 == 0) w.playSound(t, Sound.BLOCK_NOTE_BLOCK_BIT, 1.2f, 1.6f);
                for (int k = 0; k < 8; k++) {
                    double a = k * Math.PI / 4 + d.hover * 0.2;
                    double radius = settings().droneRadius;
                    w.spawnParticle(Particle.DUST, t.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(org.bukkit.Color.fromRGB(0xFF3040), 1.6f));
                }
            }
            Location at = d.pos.toLocation(w);
            d.display.teleport(at);
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 2, 0.2, 0.1, 0.2, 0.02);
            if (d.hover >= DRONE_HOVER) {
                strike(d, t);
                d.display.remove();
                it.remove();
            }
        }
    }

    private void strike(Drone d, Location at) {
        World w = at.getWorld();
        w.strikeLightningEffect(at);
        w.spawnParticle(Particle.EXPLOSION, at, 3, 0.8, 0.4, 0.8, 0);
        race.content().sound(at, "blast", 1f, 0.8f, Sound.ENTITY_GENERIC_EXPLODE);
        if (d.target.kart != null) race.content().vfx("gk_explosion", d.target.kart, d.target.player(), EXPLOSION_AHEAD);
        for (Racer r : new ArrayList<>(race.order)) {
            if (r.kart == null || r.finished || !near(r.kart.location(), at, settings().droneRadius, 3)) continue;
            thrown(r, d.owner, settings().droneStunTicks, 7.5, "hit-drone", "feed-drone");
        }
    }

    // ---- overdrive and the shield ------------------------------------------------------

    /** A boost every press, for overdrive-seconds from the first one. */
    private void overdrive(Racer r) {
        if (r.overdriveUntil <= tick) {
            r.overdriveUntil = tick + settings().overdriveTicks;
            Location at = r.kart.location();
            at.getWorld().playSound(at, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.8f);
        }
        race.plugin.driving().boost(r.kart, settings().nitroBoost);
    }

    private void tickOverdrives() {
        for (Racer r : race.order) {
            if (r.overdriveUntil <= 0 || tick < r.overdriveUntil) continue;
            r.overdriveUntil = 0;
            if (r.item == Item.OVERDRIVE) {
                r.item = null;
                race.showItem(r);
            }
        }
    }

    private void shield(Racer r) {
        race.plugin.driving().shield(r.kart, settings().shieldTicks, settings().shieldSpeed);
        Location at = r.kart.location();
        at.getWorld().playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.5f);
        race.content().vfx("gk_boost", r.kart, r.player());
    }

    /** A shielded kart glows in a turning rainbow, and spins out whoever it touches. */
    private void tickShields() {
        for (Racer r : race.order) {
            if (r.kart == null || r.finished || !race.plugin.driving().shielded(r.kart)) continue;
            Location c = r.kart.location();
            for (int k = 0; k < 6; k++) {
                double a = k * Math.PI / 3 + tick * 0.35;
                java.awt.Color hue = java.awt.Color.getHSBColor((float) ((tick * 0.03 + k / 6.0) % 1), 0.8f, 1f);
                c.getWorld().spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * 1.3, 0.4 + (k % 2) * 0.6, Math.sin(a) * 1.3),
                        1, 0, 0, 0, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(hue.getRed(), hue.getGreen(), hue.getBlue()), 1.4f));
            }
            for (Racer o : race.order) {
                if (o == r || o.kart == null || o.finished) continue;
                if (!near(o.kart.location(), c, 1.9, 1.5)) continue;
                if (shieldBumped.getOrDefault(o.id, -1L) > tick) continue;
                shieldBumped.put(o.id, tick + 30);
                spin(o, r, settings().bananaStunTicks, "hit-shield", "feed-shield");
            }
        }
    }

    // ---- smoke, the horn and the surge -------------------------------------------------

    /** Blinds everybody ahead. False, and kept, when nobody is. */
    private boolean smoke(Racer r) {
        List<Racer> ahead = new ArrayList<>();
        for (Racer o : race.order) {
            if (o != r && !o.finished && o.kart != null && o.position > 0 && o.position < r.position) ahead.add(o);
        }
        if (ahead.isEmpty()) {
            Player p = r.player();
            if (p != null) Text.send(p, race.messages().component("no-target"));
            return false;
        }
        Location from = r.kart.location();
        from.getWorld().playSound(from, Sound.ENTITY_SPLASH_POTION_THROW, 1f, 0.7f);
        for (Racer o : ahead) {
            if (blocked(o)) continue;
            Location at = o.kart.location().add(0, 1.2, 0);
            at.getWorld().spawnParticle(Particle.SQUID_INK, at, 40, 0.8, 0.6, 0.8, 0.03);
            at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at, 24, 0.9, 0.6, 0.9, 0.02);
            Player p = o.player();
            if (p == null) continue;
            p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS,
                    settings().smokeTicks, 0, false, false, false));
            p.playSound(p.getLocation(), Sound.ENTITY_SQUID_SQUIRT, 1f, 0.8f);
            Text.send(p, race.messages().component("hit-smoke", "owner", r.name));
        }
        race.feed(race.messages().plain("feed-smoke", "owner", r.name));
        return true;
    }

    /**
     * A blast round the kart: everybody within reach is thrown back and
     * stunned for a moment, and the bananas, decoys, orbs and rockets near it
     * are gone, as is a storm drone coming for this racer.
     */
    private void horn(Racer r) {
        Location c = r.kart.location();
        World w = c.getWorld();
        double reach = settings().hornRadius;
        w.playSound(c, Sound.ITEM_GOAT_HORN_SOUND_0, 2f, 1.4f);
        w.spawnParticle(Particle.SONIC_BOOM, c.clone().add(0, 1, 0), 1);
        for (int k = 0; k < 48; k++) {
            double a = k * Math.PI / 24;
            w.spawnParticle(Particle.CLOUD, c.clone().add(Math.cos(a) * reach, 0.5, Math.sin(a) * reach), 1, 0, 0, 0, 0.02);
        }
        for (Racer o : new ArrayList<>(race.order)) {
            if (o == r || o.kart == null || o.finished || !near(o.kart.location(), c, reach, 3) || blocked(o)) continue;
            Vector away = o.kart.location().toVector().subtract(c.toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            away.normalize().multiply(7).setY(2.5);
            race.plugin.driving().stun(o.kart, settings().hornStunTicks);
            o.kart.applyImpulse(away, ThreadLocalRandom.current().nextBoolean() ? 180 : -180);
            Player p = o.player();
            if (p != null) Text.send(p, race.messages().component("hit-horn", "owner", r.name));
            race.feed(race.messages().plain("feed-horn", "victim", o.name, "owner", r.name));
        }
        double clear = reach + 1;
        while (clearNear(bananas, b -> b.display(), b -> b.at(), c, clear)) {
        }
        while (clearNear(decoys, d -> d.display(), d -> d.at(), c, clear)) {
        }
        for (Iterator<Orb> it = orbs.iterator(); it.hasNext(); ) {
            Orb o = it.next();
            if (!near(new Location(w, o.x, o.ground, o.z), c, clear, 3)) continue;
            o.display.remove();
            it.remove();
        }
        for (Iterator<Rocket> it = rockets.iterator(); it.hasNext(); ) {
            Rocket k = it.next();
            if (!near(k.pos.toLocation(w), c, clear, 6)) continue;
            w.spawnParticle(Particle.EXPLOSION, k.pos.toLocation(w), 1);
            k.display.remove();
            it.remove();
        }
        for (Iterator<Drone> it = drones.iterator(); it.hasNext(); ) {
            Drone d = it.next();
            if (d.target != r || d.pos.distance(c.toVector()) > 14) continue;
            w.spawnParticle(Particle.EXPLOSION, d.pos.toLocation(w), 1);
            d.display.remove();
            it.remove();
        }
    }

    /** Everybody else: their item gone, a jolt, and a few seconds slower. */
    private void surge(Racer r) {
        for (Racer o : race.order) {
            if (o == r || o.kart == null || o.finished || blocked(o)) continue;
            o.item = null;
            o.itemUses = 0;
            o.roulette = 0;
            o.overdriveUntil = 0;
            race.showItem(o);
            race.plugin.driving().stun(o.kart, 8);
            race.plugin.driving().slow(o.kart, settings().surgeTicks, settings().surgeSlow);
            Location at = o.kart.location().add(0, 1, 0);
            at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 40, 0.7, 0.7, 0.7, 0.2);
            Player p = o.player();
            if (p == null) continue;
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.8f, 1.2f);
            Text.send(p, race.messages().component("hit-surge", "owner", r.name));
        }
        Location at = r.kart.location();
        at.getWorld().playSound(at, Sound.BLOCK_BEACON_DEACTIVATE, 1f, 2f);
        race.feed(race.messages().plain("feed-surge", "owner", r.name));
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
