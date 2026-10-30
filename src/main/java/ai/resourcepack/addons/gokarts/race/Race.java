package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.command.Perms;
import ai.resourcepack.addons.gokarts.config.Messages;
import ai.resourcepack.addons.gokarts.config.Settings;
import ai.resourcepack.addons.gokarts.content.Content;
import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.addons.gokarts.track.Track;
import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One track's race: its lobby, and the race run on it.
 *
 * <ol>
 *   <li><b>Waiting</b> in the lobby. Once {@code min-players} are there it
 *   counts down on its own; an op can {@code /kart start} at any time, alone
 *   included.</li>
 *   <li><b>The grid.</b> Everybody is put in a kart on a grid slot, engines
 *   off. Three red lights a second apart, then green: GO. W held when the
 *   second light comes on stalls you; W held as it goes green and not before
 *   is a rocket start.</li>
 *   <li><b>Racing.</b> Laps are counted gate by gate (see {@link Track}).
 *   Falling off, ending up in water, going nowhere for {@code stuck-seconds}
 *   or pressing Q puts you back at the last gate you passed.</li>
 *   <li><b>Results</b> once everybody is home, or {@code finish-timeout} after
 *   the winner; then back to the lobby for the next one.</li>
 * </ol>
 */
public final class Race {
    public enum Phase { WAITING, STARTING, GRID, RACING, RESULTS }

    /** Ticks on the grid: a light every second, green on the fourth. */
    static final int LIGHT_1 = 20, LIGHT_2 = 40, LIGHT_3 = 60, GO = 80;

    /** One line of the results: who, where, and how fast. */
    public record Result(UUID id, String name, KartColour colour, int place, long ticks, long bestLap, boolean dnf) {
    }

    final GoKartsPlugin plugin;
    final RaceManager manager;
    final Track track;
    final Hud hud;
    final Items items;
    final Map<UUID, Racer> members = new LinkedHashMap<>();
    /** Everybody in the race now, in race order. */
    final List<Racer> order = new ArrayList<>();
    Phase phase = Phase.WAITING;
    int phaseTicks;
    /** Ticks since GO. */
    long clock;
    boolean forced;
    int finishers;
    long firstFinish = -1;
    int laps;
    /** The line across the top of every racer's screen, and for how many more ticks. */
    String feed = "";
    int feedTicks;
    private List<Result> lastResults = List.of();
    private String fastestName;
    private long fastestLap = -1;

    Race(GoKartsPlugin plugin, RaceManager manager, Track track) {
        this.plugin = plugin;
        this.manager = manager;
        this.track = track;
        this.hud = new Hud(this);
        this.items = new Items(this);
    }

    public Track track() {
        return track;
    }

    public Phase phase() {
        return phase;
    }

    public int size() {
        return members.size();
    }

    public List<Result> lastResults() {
        return lastResults;
    }

    /** How many laps the race being run (or the last one) is. */
    public int laps() {
        return laps > 0 ? laps : track.laps() > 0 ? track.laps() : settings().laps;
    }

    Settings settings() {
        return plugin.settings();
    }

    Messages messages() {
        return plugin.messages();
    }

    Content content() {
        return plugin.content();
    }

    Racer racer(Player p) {
        return members.get(p.getUniqueId());
    }

    /** Whether this vehicle is one of this race's karts. */
    boolean owns(UUID chassis) {
        for (Racer r : members.values()) if (r.kart != null && r.kart.uniqueId().equals(chassis)) return true;
        return false;
    }

    Racer ownerOf(Vehicle v) {
        for (Racer r : members.values()) if (r.kart != null && r.kart.uniqueId().equals(v.uniqueId())) return r;
        return null;
    }

    // ---- joining and leaving ---------------------------------------------------------

    /** Into the lobby. False, and nothing touched, when their things could not be saved first. */
    boolean join(Player p) {
        if (!plugin.snapshots().take(p)) return false;
        PlayerSnapshot.reset(p);
        Racer r = new Racer(p);
        r.wanted = plugin.colours().get(p.getUniqueId());
        members.put(p.getUniqueId(), r);
        p.setGameMode(GameMode.ADVENTURE);
        p.teleport(track.lobby());
        boolean running = phase == Phase.GRID || phase == Phase.RACING;
        String hint = running ? "" : p.hasPermission(Perms.START) ? messages().raw("hint-start")
                : messages().raw("hint-wait").replace("{min}", String.valueOf(settings().minPlayers));
        Text.send(p, Text.mm(messages().raw("joined").replace("{prefix}", messages().raw("prefix"))
                .replace("{track}", Text.MM.escapeTags(track.name())).replace("{hint}", hint)));
        if (running) {
            Text.send(p, messages().component("race-running", "track", track.name()));
        }
        return true;
    }

    /** Somebody leaving on their own (/kart leave, quitting): out of their kart, home, with their things. */
    void leave(Player p) {
        Racer r = members.remove(p.getUniqueId());
        if (r == null) return;
        // Somebody home already keeps their place in the results.
        if (r.finished && order.contains(r)) departed.add(r);
        order.remove(r);
        dropKart(r);
        hud.leave(p);
        if (!plugin.snapshots().restore(p)) p.teleport(p.getWorld().getSpawnLocation());
        if (members.isEmpty()) {
            items.clear();
            reset();
            manager.closed(this);
        }
    }

    /** Finishers who left before the results: still in them, in their place. */
    private final List<Racer> departed = new ArrayList<>();

    /** /kart colour: from the next race (a kart already on the grid keeps its paint). */
    public void wantColour(Player p, KartColour c) {
        Racer r = racer(p);
        if (r != null) r.wanted = c;
    }

    // ---- starting ----------------------------------------------------------------------

    /** /kart start: the grid now, whoever is waiting. */
    public boolean start() {
        if (phase == Phase.GRID || phase == Phase.RACING || phase == Phase.RESULTS || members.isEmpty()) return false;
        forced = true;
        grid();
        return true;
    }

    private void grid() {
        resetRace();
        laps = track.laps() > 0 ? track.laps() : settings().laps;
        List<Racer> in = new ArrayList<>(members.values());
        int slots = Math.min(track.grid().size(), KartColour.values().length);
        // More waiting than the grid holds: whoever sat out last time goes
        // first, then in the order they joined. Without it the same eight raced
        // every time and the ninth never did.
        in.sort(Comparator.comparingInt((Racer r) -> -r.satOut));
        List<Racer> chosen = new ArrayList<>(in.subList(0, Math.min(slots, in.size())));
        for (Racer r : in.subList(chosen.size(), in.size())) {
            r.satOut++;
            Player p = r.player();
            if (p != null) Text.send(p, messages().component("grid-full"));
        }
        Set<KartColour> free = EnumSet.allOf(KartColour.class);
        List<Racer> placed = new ArrayList<>();
        for (Racer r : chosen) {
            r.satOut = 0;
            if (r.wanted != null && free.remove(r.wanted)) {
                r.colour = r.wanted;
                placed.add(r);
            }
        }
        for (Racer r : chosen) {
            if (r.colour != null) continue;
            KartColour c = free.iterator().next();
            free.remove(c);
            r.colour = c;
            placed.add(r);
        }
        java.util.Collections.shuffle(placed);
        for (int i = 0; i < placed.size(); i++) {
            Racer r = placed.get(i);
            Player p = r.player();
            if (p == null) continue;
            Location slot = track.grid().get(i).clone();
            slot.setWorld(track.world());
            Optional<Vehicle> made = content().engine().vehicles().spawn(slot, Content.id(r.colour.vehicle()));
            if (made.isEmpty()) {
                plugin.getLogger().warning("RP Engine would not park " + Content.NS + ":" + r.colour.vehicle()
                        + ". Is the gokarts content loaded (/rp reload)?");
                r.colour = null;
                continue;
            }
            Vehicle v = made.get();
            v.setEnabled(false);
            // Theirs before they sit in it: RaceListener lets a racer into their own kart only.
            r.kart = v;
            p.teleport(slot);
            v.seat(p);
            v.holdOccupant(p, true);
            r.racing = true;
            r.lap = 1;
            r.next = 1 % track.checkpoints().size();
            r.last = 0;
            r.lastGateTick = 0;
            r.lapStartTick = 0;
            p.getInventory().clear();
            p.getInventory().setItem(0, content().item("no_item"));
            p.getInventory().setHeldItemSlot(0);
            order.add(r);
            hud.join(p);
            Text.send(p, messages().component("how-to"));
            r.subline(messages().plain("hud-waiting"), GO);
        }
        if (order.isEmpty()) {
            phase = Phase.WAITING;
            return;
        }
        items.start();
        phase = Phase.GRID;
        phaseTicks = 0;
        clock = 0;
    }

    // ---- the tick ------------------------------------------------------------------------

    void tick() {
        phaseTicks++;
        switch (phase) {
            case WAITING -> {
                if (members.size() >= settings().minPlayers) {
                    phase = Phase.STARTING;
                    phaseTicks = 0;
                }
                if (phaseTicks % 20 == 0) lobbyBar(-1);
            }
            case STARTING -> {
                int left = settings().lobbyCountdown * 20 - phaseTicks;
                if (members.size() < settings().minPlayers && !forced) {
                    phase = Phase.WAITING;
                    phaseTicks = 0;
                } else if (left <= 0) {
                    grid();
                } else if (phaseTicks % 20 == 0) {
                    lobbyBar((left + 19) / 20);
                }
            }
            case GRID -> tickGrid();
            case RACING -> tickRacing();
            case RESULTS -> {
                if (phaseTicks >= settings().resultsSeconds * 20) backToLobby();
            }
        }
        if ((phase == Phase.GRID || phase == Phase.RACING) && order.isEmpty()) {
            // Everybody racing has left (the lobby may not have): the results if
            // anybody got home, else straight back to the lobby.
            if (!departed.isEmpty()) {
                end(false);
            } else {
                items.clear();
                broadcast(messages().component("race-abandoned"));
                backToLobby();
            }
        }
        if (phase == Phase.GRID || phase == Phase.RACING) hud.tick();
        if (phase != Phase.RESULTS) holdInLobby();
    }

    /**
     * Nobody waiting wanders off the viewing deck: whoever is in the race but
     * not in a kart (everybody before the grid, and anybody the grid had no
     * slot for) and steps or falls more than {@code race.lobby-radius} from the
     * lobby is put back on it, still looking where they were.
     */
    private void holdInLobby() {
        Location lobby = track.lobby();
        if (lobby == null || lobby.getWorld() == null) return;
        double radius = settings().lobbyRadius;
        for (Racer r : members.values()) {
            if (r.kart != null) continue;
            Player p = r.player();
            if (p == null) continue;
            Location at = p.getLocation();
            boolean out = at.getWorld() != lobby.getWorld()
                    || Math.hypot(at.getX() - lobby.getX(), at.getZ() - lobby.getZ()) > radius
                    || at.getY() < lobby.getY() - 1.5 || at.getY() > lobby.getY() + 6;
            if (!out) continue;
            Location back = lobby.clone();
            back.setYaw(at.getYaw());
            back.setPitch(at.getPitch());
            p.setFallDistance(0);
            p.teleport(back);
        }
    }

    private void lobbyBar(int seconds) {
        String line;
        if (seconds >= 0) {
            line = messages().raw("lobby-count").replace("{track}", track.name())
                    .replace("{n}", String.valueOf(members.size())).replace("{seconds}", String.valueOf(seconds));
        } else {
            line = messages().raw("lobby-wait").replace("{track}", track.name()).replace("{n}", String.valueOf(members.size()));
        }
        for (Racer r : members.values()) {
            Player p = r.player();
            if (p == null) continue;
            String need = p.hasPermission(Perms.START) ? messages().raw("lobby-op")
                    : messages().raw("lobby-need").replace("{more}", String.valueOf(Math.max(0, settings().minPlayers - members.size())));
            Text.actionBar(p, Text.mm(line.replace("{need}", need)));
        }
    }

    private void tickGrid() {
        int t = phaseTicks;
        if (t == LIGHT_1 || t == LIGHT_2 || t == LIGHT_3) {
            for (Racer r : order) {
                Player p = r.player();
                if (p != null) content().sound(p, "beep", 1f, 1f, Sound.BLOCK_NOTE_BLOCK_PLING);
            }
        }
        for (Racer r : order) {
            Player p = r.player();
            if (p == null || r.kart == null) continue;
            keepSeated(r, p);
            boolean w = r.kart.input().forward() || r.kart.input().throttle() > 0.1;
            if (t == LIGHT_2 && w) r.jumped = true;
            if (t == GO) {
                if (r.jumped) {
                    r.frozen = 30;
                    Text.send(p, messages().component("stalled"));
                    p.getWorld().spawnParticle(Particle.LARGE_SMOKE, r.kart.pointOn(0, 0.6, -0.9), 12, 0.2, 0.1, 0.2, 0.02);
                } else {
                    r.kart.setEnabled(true);
                    if (w) {
                        plugin.driving().boost(r.kart, settings().rocketStart);
                        Text.send(p, messages().component("rocket-start"));
                    }
                }
                content().sound(p, "go", 1f, 1f, Sound.BLOCK_NOTE_BLOCK_BELL);
                content().vfx("gk_go", r.kart, p);
                r.subline("", 0);
            }
        }
        if (t >= GO) {
            phase = Phase.RACING;
            phaseTicks = 0;
            clock = 0;
        }
    }

    private void tickRacing() {
        clock++;
        if (feedTicks > 0) feedTicks--;
        List<Track.Checkpoint> gates = track.checkpoints();
        for (Racer r : new ArrayList<>(order)) {
            Player p = r.player();
            if (p == null) continue;
            if (r.kart == null || !r.kart.isValid()) {
                respawn(r);
                continue;
            }
            keepSeated(r, p);
            if (r.frozen > 0 && --r.frozen == 0 && !r.finished) r.kart.setEnabled(true);
            plugin.driving().tick(r.kart, p, false);
            if (r.finished) continue;

            if (r.respawnIn > 0) {
                if (--r.respawnIn == 0) respawn(r);
                continue;
            }
            Location at = r.kart.location();
            Track.Checkpoint gate = gates.get(r.next);
            if (gate.contains(at)) passGate(r, p);
            wrongWay(r, gate, at);
            boolean lost = at.getY() < track.floor() || r.kart.submersion() > 0.4
                    || clock - r.lastGateTick > stuckAllowance(r, gates);
            if (lost) askRespawn(r);
        }
        items.tick();
        rank();

        boolean everybodyHome = !order.isEmpty() && order.stream().allMatch(r -> r.finished);
        if (everybodyHome && clock - lastFinishTick() > 40) {
            end(false);
        } else if (firstFinish >= 0 && clock - firstFinish > settings().finishTimeout * 20L) {
            broadcast(messages().component("timeout"));
            end(false);
        } else if (clock > settings().maxSeconds * 20L) {
            broadcast(messages().component("timeout"));
            end(false);
        }
    }

    private long lastFinish;

    /**
     * How long a racer may go without a gate before they are put back: the
     * configured time, plus the time a slow kart takes to cover the stretch to
     * the next gate. Gates are left out wherever a branch stands in for the
     * loop, so one stretch can be ninety blocks, which a flat twelve seconds
     * called "stuck" for anybody taking it at a cautious pace.
     */
    private long stuckAllowance(Racer r, List<Track.Checkpoint> gates) {
        Track.Checkpoint from = gates.get(r.last), to = gates.get(r.next);
        double gap = Math.hypot(to.x() - from.x(), to.z() - from.z());
        return settings().stuckTicks + Math.round(gap / 5.0 * 20);
    }

    private long lastFinishTick() {
        return lastFinish;
    }

    /** A racer who got out of their kart (sneak, when it was nearly stopped) is put straight back in. */
    private void keepSeated(Racer r, Player p) {
        if (r.kart == null || !r.kart.isValid()) return;
        if (r.kart.seatOf(p).isPresent()) return;
        if (!p.getWorld().equals(r.kart.location().getWorld())) return;
        r.kart.seat(p);
        r.kart.holdOccupant(p, true);
    }

    private void passGate(Racer r, Player p) {
        int n = track.checkpoints().size();
        r.last = r.next;
        r.lastGateTick = clock;
        if (r.next == 0) {
            long lapTicks = clock - r.lapStartTick;
            if (r.bestLap < 0 || lapTicks < r.bestLap) r.bestLap = lapTicks;
            if (fastestLap < 0 || lapTicks < fastestLap) {
                fastestLap = lapTicks;
                fastestName = r.name;
            }
            boolean personalBest = r.bestLap == lapTicks && r.lap > 1;
            r.lap++;
            r.lapStartTick = clock;
            if (r.lap > laps) {
                finish(r, p);
            } else {
                content().sound(p, "lap", 1f, 1f, Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
                content().vfx("gk_lap", r.kart, p);
                if (r.lap == laps) r.banner("banner_final", 50);
                else if (personalBest) r.banner("banner_best", 40);
                else r.banner("banner_lap", 30);
            }
        }
        r.next = (r.next + 1) % n;
    }

    private void finish(Racer r, Player p) {
        r.finished = true;
        r.place = ++finishers;
        r.finishTicks = clock;
        lastFinish = clock;
        if (firstFinish < 0) {
            firstFinish = clock;
            broadcast(messages().component("winner", "name", r.name, "track", track.name(), "time", Text.raceClock(clock)));
        } else {
            broadcast(messages().component("finished", "name", r.name, "place", Hud.ordinal(r.place), "time", Text.raceClock(clock)));
        }
        r.kart.setEnabled(false);
        r.item = null;
        r.roulette = 0;
        content().sound(p, "finish", 1f, 1f, Sound.UI_TOAST_CHALLENGE_COMPLETE);
        content().vfx("gk_confetti", r.kart, p);
        r.kart.dress(p, "gokarts_" + r.colour.id() + "_victory");
        feed(messages().plain("feed-finish", "name", r.name, "place", Hud.ordinal(r.place)));
        r.banner("banner_finish", 80);
        r.subline(messages().plain("hud-finished-place", "place", Hud.ordinal(r.place)), 400);
        p.getWorld().spawnParticle(Particle.FIREWORK, r.kart.pointOn(0, 1.2, 0), 40, 0.6, 0.6, 0.6, 0.08);
    }

    /** Off the track, in water, or stuck: a moment's pause and then back on at the last gate. */
    void askRespawn(Racer r) {
        if (r.finished || r.respawnIn > 0 || phase != Phase.RACING) return;
        r.respawnIn = 20;
        r.subline(messages().plain("hud-respawning"), 30);
        if (r.kart != null && r.kart.isValid()) r.kart.setEnabled(false);
    }

    /** Puts a racer's kart down again at the last gate they passed, facing the next one. */
    void respawn(Racer r) {
        Player p = r.player();
        if (p == null) return;
        List<Track.Checkpoint> gates = track.checkpoints();
        Track.Checkpoint from = gates.get(r.last);
        Track.Checkpoint to = gates.get((r.last + 1) % gates.size());
        Location at = onGround(from.at(track.world()));
        Vector dir = new Vector(to.x() - from.x(), 0, to.z() - from.z());
        if (dir.lengthSquared() > 1e-6) at.setDirection(dir);
        at.setPitch(0);
        dropKart(r);
        Optional<Vehicle> made = content().engine().vehicles().spawn(at, Content.id(r.colour.vehicle()));
        if (made.isEmpty()) return;
        Vehicle v = made.get();
        v.setEnabled(false);
        r.kart = v;
        p.teleport(at);
        v.seat(p);
        v.holdOccupant(p, true);
        r.frozen = 20;
        r.lastGateTick = clock;
        r.subline(messages().plain("hud-respawning"), 20);
        p.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 0.6, 0), 20, 0.5, 0.3, 0.5, 0.02);
        content().vfx("gk_respawn", v, p);
        Text.send(p, messages().component("respawned"));
    }

    /**
     * A gate's position stood on whatever is really there: the top of the
     * highest solid block from a block and a half over it to two under.
     *
     * <p>A gate's height is where the racing line is, and the road under it was
     * built to the nearest half block, so it can be a little ABOVE the gate. A
     * kart put down there started inside the road, fell through it, landed
     * under the track's floor and was put back at the same gate: round and
     * round, never racing again.
     */
    static Location onGround(Location at) {
        if (at.getWorld() == null) return at;
        int bx = at.getBlockX(), bz = at.getBlockZ();
        for (int y = (int) Math.floor(at.getY() + 1.5); y >= (int) Math.floor(at.getY() - 2); y--) {
            org.bukkit.block.Block b = at.getWorld().getBlockAt(bx, y, bz);
            if (b.getType().isSolid()) {
                Location out = at.clone();
                out.setY(b.getBoundingBox().getMaxY() + 0.02);
                return out;
            }
        }
        return at;
    }

    private void dropKart(Racer r) {
        if (r.kart == null) return;
        plugin.driving().forget(r.kart);
        if (r.kart.isValid()) {
            r.kart.ejectAll();
            r.kart.remove();
        }
        r.kart = null;
    }

    /** The line across the top of everybody's screen for a few seconds. */
    void feed(String text) {
        feed = text;
        feedTicks = 70;
    }

    /**
     * WRONG WAY: going away from the next gate, at speed, for a second and a
     * half. Measured against where the racer was a second ago, so a hairpin
     * (which leads away from the gate for a moment, on purpose) does not count.
     */
    private void wrongWay(Racer r, Track.Checkpoint gate, Location at) {
        if (clock % 20 != 0) return;
        double gap = gate.distance(at);
        boolean away = !Double.isNaN(r.lastGap) && gap > r.lastGap + 3 && r.kart.groundSpeed() > 3;
        r.lastGap = gap;
        r.wrongTicks = away ? r.wrongTicks + 20 : 0;
        if (r.wrongTicks >= 40) r.banner("banner_wrong", 22);
    }

    /** Race order: whoever is home in the order they got there, then whoever has passed more gates, then whoever is nearer the next. */
    private void rank() {
        int n = track.checkpoints().size();
        order.sort(Comparator
                .comparing((Racer r) -> !r.finished)
                .thenComparingInt(r -> r.finished ? r.place : 0)
                .thenComparingInt(r -> -progress(r, n))
                .thenComparingDouble(r -> r.kart == null ? Double.MAX_VALUE
                        : track.checkpoints().get(r.next).distance(r.kart.location())));
        for (int i = 0; i < order.size(); i++) order.get(i).position = i + 1;
    }

    private static int progress(Racer r, int n) {
        int passed = r.next == 0 ? n : r.next;
        return (r.lap - 1) * n + passed;
    }

    // ---- ending --------------------------------------------------------------------------

    void end(boolean stopped) {
        if (phase != Phase.RACING && phase != Phase.GRID) return;
        if (stopped) broadcast(messages().component("stopped"));
        rank();
        List<Racer> all = new ArrayList<>(order);
        all.addAll(departed);
        // Finishers by the place they finished in (chat already told them it),
        // then everybody still out there in the order they were running.
        List<Racer> home = new ArrayList<>(all.stream().filter(r -> r.finished).toList());
        home.sort(Comparator.comparingInt(r -> r.place));
        List<Racer> ranked = new ArrayList<>(home);
        for (Racer r : all) if (!r.finished) ranked.add(r);
        List<Result> results = new ArrayList<>();
        int place = 0;
        for (Racer r : ranked) {
            place++;
            results.add(new Result(r.id, r.name, r.colour, r.finished ? r.place : place,
                    r.finished ? r.finishTicks : -1, r.bestLap, !r.finished));
        }
        lastResults = List.copyOf(results);
        if (fastestName != null && !stopped) {
            broadcast(messages().component("fastest-lap", "name", fastestName, "time", Text.raceClock(fastestLap)));
        }
        items.clear();
        for (Racer r : order) {
            if (r.kart != null && r.kart.isValid()) r.kart.setEnabled(false);
            Player p = r.player();
            if (p != null) hud.leave(p);
        }
        Records.Entry lapBefore = plugin.records().lap(track.id());
        // A race somebody stopped proves nothing about anybody's pace.
        if (!stopped) plugin.records().record(track, lastResults, fastestName, fastestLap);
        manager.finished(this);
        if (fastestName != null && !stopped && (lapBefore == null || fastestLap < lapBefore.ticks())) {
            broadcast(messages().component("record", "name", fastestName, "time", Text.raceClock(fastestLap)));
        }
        phase = Phase.RESULTS;
        phaseTicks = 0;
        for (Racer r : members.values()) {
            Player p = r.player();
            if (p != null) plugin.results().open(p, this);
        }
    }

    private void backToLobby() {
        for (Racer r : new ArrayList<>(members.values())) {
            dropKart(r);
            Player p = r.player();
            if (p == null) continue;
            p.getInventory().clear();
            p.teleport(track.lobby());
        }
        resetRace();
        phase = Phase.WAITING;
        phaseTicks = 0;
        forced = false;
    }

    /** /kart stop: a race is ended (results, then the lobby); a lobby is sent home. */
    void stop() {
        if (phase == Phase.GRID || phase == Phase.RACING) {
            end(true);
            return;
        }
        close();
    }

    /** Everybody home, now: an arena closing, the server stopping. */
    void close() {
        items.clear();
        for (Racer r : new ArrayList<>(members.values())) {
            dropKart(r);
            Player p = r.player();
            if (p != null) {
                Text.send(p, messages().component("race-closed", "track", track.name()));
                hud.leave(p);
                manager.forget(p);
                if (!plugin.snapshots().restore(p)) p.teleport(p.getWorld().getSpawnLocation());
            }
        }
        members.clear();
        reset();
        manager.closed(this);
    }

    private void resetRace() {
        order.clear();
        departed.clear();
        finishers = 0;
        firstFinish = -1;
        lastFinish = 0;
        fastestLap = -1;
        fastestName = null;
        clock = 0;
        for (Racer r : members.values()) {
            r.racing = false;
            r.colour = null;
            r.kart = null;
            r.lap = 0;
            r.next = 0;
            r.last = 0;
            r.bestLap = -1;
            r.finished = false;
            r.place = 0;
            r.position = 0;
            r.item = null;
            r.itemUses = 0;
            r.overdriveUntil = 0;
            r.roulette = 0;
            r.frozen = 0;
            r.respawnIn = 0;
            r.jumped = false;
            r.bannerTicks = 0;
            r.sublineTicks = 0;
        }
    }

    private void reset() {
        resetRace();
        phase = Phase.WAITING;
        phaseTicks = 0;
        forced = false;
    }

    void broadcast(net.kyori.adventure.text.Component c) {
        for (Racer r : members.values()) {
            Player p = r.player();
            if (p != null) Text.send(p, c);
        }
    }

    /** A racer's item, used: F, a click, or anything else that means "now". */
    void useItem(Player p) {
        Racer r = racer(p);
        if (r == null || !r.racing || r.finished || phase != Phase.RACING) return;
        // A click on a block is an interact AND an arm swing: one press, one use.
        if (plugin.ticks() - r.lastUse < 6) return;
        r.lastUse = plugin.ticks();
        items.use(r);
    }

    /**
     * /kart item: a racer's item set by hand, as if a box had just given it.
     * False when {@code key} is no item or the player is not racing here.
     */
    public boolean giveItem(Player p, String key) {
        Item item = Item.parse(key);
        Racer r = racer(p);
        if (item == null || r == null || !r.racing || r.finished) return false;
        r.item = item;
        r.itemUses = item.uses;
        r.roulette = 0;
        r.overdriveUntil = 0;
        showItem(r);
        return true;
    }

    /** Every item's name, as /kart item and items.odds take it. */
    public static List<String> itemKeys() {
        List<String> out = new ArrayList<>();
        for (Item i : Item.values()) out.add(i.key);
        return out;
    }

    /** The hotbar slot a racer's item is shown in. */
    void showItem(Racer r) {
        Player p = r.player();
        if (p == null) return;
        String id = r.item == null || r.roulette > 0 ? "no_item" : r.item.look;
        ItemStack stack = content().item(id);
        if (r.item != null && r.item.uses > 1 && r.roulette == 0) stack.setAmount(Math.max(1, r.itemUses));
        p.getInventory().setItem(0, stack);
    }
}
