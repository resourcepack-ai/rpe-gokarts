package ai.resourcepack.addons.gokarts.track;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One circuit: where its racers wait, where they line up, the gates they have
 * to pass in order, and where the item boxes float.
 *
 * <p><b>Checkpoint 0 is the start/finish line.</b> A lap is every checkpoint
 * in order and then that one again, so a racer who cuts across the infield
 * reaches the line with a gate still to pass and is not given the lap. The
 * gaps between them are also what the race order is measured on: whoever has
 * passed more gates is ahead, and between two racers on the same gate, the
 * one nearer the next.
 */
public final class Track {
    /** A gate: a point on the racing line and how far either side of it counts as through. */
    public record Checkpoint(double x, double y, double z, double radius) {
        public Location at(World w) {
            return new Location(w, x, y, z);
        }

        /** Whether a point is through this gate: within its radius across the ground, and not far above or below it. */
        public boolean contains(Location l) {
            double dx = l.getX() - x, dz = l.getZ() - z;
            return dx * dx + dz * dz <= radius * radius && Math.abs(l.getY() - y) <= 4;
        }

        public double distance(Location l) {
            double dx = l.getX() - x, dz = l.getZ() - z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private final String id;
    private String name;
    private boolean enabled;
    private String world;
    private Location lobby;
    private final List<Location> grid = new ArrayList<>();
    private final List<Checkpoint> checkpoints = new ArrayList<>();
    private final List<Vector> boxes = new ArrayList<>();
    /** Generated into a world of its own ({@link TrackBuilder#ownWorld}), which the plugin loads on start. */
    private boolean ownWorld;
    /** The prefab it was generated from, or null for a track built by hand. */
    private String prefab;
    /** Where the prefab's (u, v) frame is in the world, for the minimap: {ox, oz, fx, fz, u0, v0}. */
    private double[] mapFrame;
    private int mapBaseY;
    private double mapStartH;
    /** Its own lap count, or 0 for config.yml's. */
    private int laps;
    /** Below this a kart has fallen off the circuit; NaN is 8 below the lowest gate. */
    private double floor = Double.NaN;

    public Track(String id) {
        this.id = id;
        this.name = id;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public boolean enabled() {
        return enabled;
    }

    public void enabled(boolean enabled) {
        this.enabled = enabled;
    }

    public World world() {
        return world == null ? null : Bukkit.getWorld(world);
    }

    public String worldName() {
        return world;
    }

    public void world(World w) {
        this.world = w.getName();
    }

    /** The lobby, in the track's world as it is loaded now (a track's own world loads after tracks.yml is read). */
    public Location lobby() {
        if (lobby == null) return null;
        Location l = lobby.clone();
        World w = world();
        if (w != null) l.setWorld(w);
        return l;
    }

    public boolean ownWorld() {
        return ownWorld;
    }

    public void ownWorld(boolean own) {
        this.ownWorld = own;
    }

    public void lobby(Location l) {
        this.lobby = l.clone();
        if (world == null) world(l.getWorld());
    }

    public List<Location> grid() {
        return grid;
    }

    public List<Checkpoint> checkpoints() {
        return checkpoints;
    }

    public List<Vector> boxes() {
        return boxes;
    }

    public String prefab() {
        return prefab;
    }

    public void prefab(String id) {
        this.prefab = id;
    }

    void mapFrame(double[] frame, int baseY, double startH) {
        this.mapFrame = frame;
        this.mapBaseY = baseY;
        this.mapStartH = startH;
    }

    /** A world position as the prefab's (u, v), for the minimap; null for a track with no prefab. */
    public double[] mapPosition(double x, double z) {
        if (mapFrame == null) return null;
        return Frame.load(mapFrame, mapBaseY).local(x, z);
    }

    public int laps() {
        return laps;
    }

    public void laps(int laps) {
        this.laps = Math.max(0, laps);
    }

    public double floor() {
        if (!Double.isNaN(floor)) return floor;
        double low = Double.MAX_VALUE;
        for (Checkpoint c : checkpoints) low = Math.min(low, c.y());
        for (Location l : grid) low = Math.min(low, l.getY());
        return low == Double.MAX_VALUE ? -64 : low - 8;
    }

    public void floor(double y) {
        this.floor = y;
    }

    /** What still has to be set before a race can be held here, in the words /kart track list uses. */
    public List<String> missing() {
        List<String> out = new ArrayList<>();
        if (world() == null) out.add("a loaded world (" + world + ")");
        if (lobby == null) out.add("a lobby (setlobby)");
        if (grid.isEmpty()) out.add("a grid slot (addgrid)");
        if (checkpoints.size() < 3) out.add("at least three checkpoints, the line first (addcheckpoint)");
        return out;
    }

    // ---- tracks.yml --------------------------------------------------------------

    public void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("enabled", enabled);
        s.set("world", world);
        s.set("own-world", ownWorld ? true : null);
        s.set("laps", laps == 0 ? null : laps);
        s.set("prefab", prefab);
        if (mapFrame != null) {
            s.set("map.frame", java.util.Arrays.stream(mapFrame).boxed().toList());
            s.set("map.base-y", mapBaseY);
            s.set("map.start-h", mapStartH);
        }
        s.set("floor", Double.isNaN(floor) ? null : floor);
        s.set("lobby", lobby == null ? null : pos(lobby));
        List<List<Double>> g = new ArrayList<>();
        for (Location l : grid) g.add(pos(l));
        s.set("grid", g);
        List<List<Double>> c = new ArrayList<>();
        for (Checkpoint cp : checkpoints) c.add(List.of(cp.x, cp.y, cp.z, cp.radius));
        s.set("checkpoints", c);
        List<List<Double>> b = new ArrayList<>();
        for (Vector v : boxes) b.add(List.of(v.getX(), v.getY(), v.getZ()));
        s.set("boxes", b);
    }

    public static Track load(String id, ConfigurationSection s) {
        Track t = new Track(id);
        t.name = s.getString("name", id);
        t.enabled = s.getBoolean("enabled", false);
        t.world = s.getString("world");
        t.ownWorld = s.getBoolean("own-world", false);
        t.laps = s.getInt("laps", 0);
        t.prefab = s.getString("prefab");
        double[] frame = nums(s.getList("map.frame"));
        if (frame.length == 6) {
            t.mapFrame = frame;
            t.mapBaseY = s.getInt("map.base-y");
            t.mapStartH = s.getDouble("map.start-h");
        }
        t.floor = s.isSet("floor") ? s.getDouble("floor") : Double.NaN;
        World w = t.world();
        t.lobby = loc(w, s.getList("lobby"));
        for (Object o : list(s, "grid")) {
            Location l = loc(w, o instanceof List<?> l2 ? l2 : null);
            if (l != null) t.grid.add(l);
        }
        for (Object o : list(s, "checkpoints")) {
            double[] d = nums(o);
            if (d.length >= 4) t.checkpoints.add(new Checkpoint(d[0], d[1], d[2], d[3]));
        }
        for (Object o : list(s, "boxes")) {
            double[] d = nums(o);
            if (d.length >= 3) t.boxes.add(new Vector(d[0], d[1], d[2]));
        }
        return t;
    }

    private static List<?> list(ConfigurationSection s, String key) {
        List<?> l = s.getList(key);
        return l == null ? List.of() : l;
    }

    private static List<Double> pos(Location l) {
        return List.of(round(l.getX()), round(l.getY()), round(l.getZ()), round(l.getYaw()), round(l.getPitch()));
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double[] nums(Object o) {
        if (!(o instanceof List<?> l)) return new double[0];
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) {
            Object v = l.get(i);
            if (!(v instanceof Number n)) return new double[0];
            out[i] = n.doubleValue();
        }
        return out;
    }

    private static Location loc(World w, List<?> raw) {
        double[] d = nums(raw);
        if (d.length < 3) return null;
        return new Location(w, d[0], d[1], d[2], d.length > 3 ? (float) d[3] : 0, d.length > 4 ? (float) d[4] : 0);
    }

    /** For the placeholders and the list: a compact summary. */
    public Map<String, Object> summary() {
        return Map.of("grid", grid.size(), "checkpoints", checkpoints.size(), "boxes", boxes.size());
    }
}
