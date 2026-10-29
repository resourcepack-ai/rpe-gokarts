package ai.resourcepack.addons.gokarts.track;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One of the tracks {@code /kart track generate} builds: a JSON file in the jar
 * ({@code prefabs/<id>.json}), read here and by {@code tools/prefabs.py}, which
 * draws its minimap and checks its layout.
 *
 * <ul>
 *   <li>{@code main}: the loop, as (u, v, h) control points.</li>
 *   <li>{@code branches}: alternative routes, each leaving the loop at one
 *   fraction of a lap and rejoining it at another, through its own points. A
 *   branch can be a {@code tunnel} or have an {@code ice} surface.</li>
 *   <li>{@code ramps}: the road rises to a lip and stops; {@code gap} blocks
 *   later it starts again. What is under the gap is {@code under} (a river, a
 *   canyon...), and a kart that comes up short is in it.</li>
 *   <li>{@code tunnels}, {@code pads} (boost), {@code boxes} (item box rows),
 *   {@code start} (the line), {@code lobby} and {@code laps}.</li>
 * </ul>
 *
 * Every place on a road is a fraction of that road's length, so a layout can
 * be redrawn without moving its features by hand.
 */
public final class Prefab {
    public static final List<String> IDS = List.of("meadow", "desert", "frost", "neon");

    public record Ramp(int on, double lip, double length, double rise, double gap, String under) {
    }

    public record Span(int on, double from, double to) {
    }

    public record Spot(int on, double frac) {
    }

    public record Branch(String name, double from, double to, List<double[]> points, boolean tunnel, boolean ice) {
    }

    /** What one sample of a road is, once the features are applied. */
    public record Point(double h, boolean gap, boolean lip, boolean tunnel, boolean ice, String under) {
    }

    public final String id;
    public final String name;
    public final Theme theme;
    public final double half;
    public final double start;
    public final int laps;
    public final double[] lobby;
    public final List<Branch> branches = new ArrayList<>();
    public final List<Ramp> ramps = new ArrayList<>();
    public final List<Span> tunnels = new ArrayList<>();
    public final List<Spot> pads = new ArrayList<>();
    public final List<Spot> boxes = new ArrayList<>();
    /** Road 0 is the loop; road n is branch n. */
    public final List<Road> roads = new ArrayList<>();

    private Prefab(JsonObject j) {
        id = j.get("id").getAsString();
        name = j.get("name").getAsString();
        theme = Theme.valueOf(j.get("theme").getAsString().toUpperCase(Locale.ROOT));
        half = j.has("half") ? j.get("half").getAsDouble() : 5;
        start = j.has("start") ? j.get("start").getAsDouble() : 0;
        laps = j.has("laps") ? j.get("laps").getAsInt() : 3;
        lobby = nums(j.getAsJsonArray("lobby"));
        Road main = Road.through(points(j.getAsJsonArray("main")), true);
        roads.add(main);
        for (JsonElement e : array(j, "branches")) {
            JsonObject b = e.getAsJsonObject();
            Branch branch = new Branch(b.get("name").getAsString(), b.get("from").getAsDouble(), b.get("to").getAsDouble(),
                    points(b.getAsJsonArray("points")), b.has("tunnel") && b.get("tunnel").getAsBoolean(),
                    b.has("surface") && b.get("surface").getAsString().equals("ice"));
            branches.add(branch);
            roads.add(branchRoad(main, branch));
        }
        for (JsonElement e : array(j, "ramps")) {
            JsonObject r = e.getAsJsonObject();
            ramps.add(new Ramp(r.has("on") ? r.get("on").getAsInt() : 0, r.get("lip").getAsDouble(), r.get("length").getAsDouble(),
                    r.get("rise").getAsDouble(), r.get("gap").getAsDouble(), r.has("under") ? r.get("under").getAsString() : "void"));
        }
        for (JsonElement e : array(j, "tunnels")) {
            JsonObject t = e.getAsJsonObject();
            tunnels.add(new Span(t.has("on") ? t.get("on").getAsInt() : 0, t.get("from").getAsDouble(), t.get("to").getAsDouble()));
        }
        for (JsonElement e : array(j, "pads")) pads.add(spot(e));
        for (JsonElement e : array(j, "boxes")) boxes.add(spot(e));
    }

    /** A branch's road: from the loop at {@code from}, through its points, back onto the loop at {@code to}. */
    private static Road branchRoad(Road main, Branch b) {
        double a = b.from() * main.length(), z = b.to() * main.length();
        List<double[]> pts = new ArrayList<>();
        pts.add(xyz(main.at(a - 6)));
        pts.add(xyz(main.at(a)));
        pts.addAll(b.points());
        pts.add(xyz(main.at(z)));
        pts.add(xyz(main.at(z + 6)));
        return Road.through(pts, false);
    }

    private static double[] xyz(Road.Sample s) {
        return new double[]{s.u(), s.v(), s.h()};
    }

    public static Prefab load(String id) throws IOException {
        if (!IDS.contains(id)) throw new IOException("No prefab called " + id);
        try (InputStream in = Prefab.class.getClassLoader().getResourceAsStream("prefabs/" + id + ".json")) {
            if (in == null) throw new IOException("prefabs/" + id + ".json is not in the jar");
            return new Prefab(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    /**
     * One sample of road {@code on}, with the ramps, gaps, tunnels and ice
     * applied: the same rules as {@code features} in {@code tools/prefabs.py}.
     */
    public Point point(int on, Road.Sample p) {
        Road r = roads.get(on);
        double h = p.h();
        boolean gap = false, lip = false;
        String under = null;
        for (Ramp ramp : ramps) {
            if (ramp.on() != on) continue;
            double ahead = r.offset(ramp.lip() * r.length(), p.s());
            if (ahead >= -ramp.length() && ahead <= 0) {
                h += ramp.rise() * smoothstep((ahead + ramp.length()) / ramp.length());
                if (ahead >= -1.75) lip = true;
            } else if (ahead > 0 && ahead <= ramp.gap()) {
                gap = true;
                under = ramp.under();
            }
        }
        boolean tunnel = false;
        for (Span t : tunnels) {
            if (t.on() == on && p.s() >= t.from() * r.length() && p.s() <= t.to() * r.length()) tunnel = true;
        }
        boolean ice = false;
        if (on > 0) {
            Branch b = branches.get(on - 1);
            if (b.tunnel()) tunnel = p.s() > 6 && p.s() < r.length() - 6;
            ice = b.ice();
        }
        return new Point(h, gap, lip, tunnel, ice, under);
    }

    /** The stretches of the loop a branch stands in for: no gate goes there, or a racer on the branch would miss it. */
    public boolean coveredByBranch(double frac) {
        for (Branch b : branches) {
            double from = b.from() - 0.01, to = b.to() + 0.01;
            if (from <= to ? frac >= from && frac <= to : frac >= from || frac <= to) return true;
        }
        return false;
    }

    public Road.Sample spot(Spot s) {
        Road r = roads.get(s.on());
        return r.at(s.frac() * r.length());
    }

    static double smoothstep(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    private static Spot spot(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new Spot(a.get(0).getAsInt(), a.get(1).getAsDouble());
    }

    private static JsonArray array(JsonObject j, String key) {
        return j.has(key) ? j.getAsJsonArray(key) : new JsonArray();
    }

    private static List<double[]> points(JsonArray a) {
        List<double[]> out = new ArrayList<>();
        for (JsonElement e : a) out.add(nums(e.getAsJsonArray()));
        return out;
    }

    private static double[] nums(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }
}
