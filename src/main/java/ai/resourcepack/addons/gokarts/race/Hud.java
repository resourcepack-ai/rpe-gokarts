package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.engine.api.ContentId;
import ai.resourcepack.engine.api.Overlays;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything on a racer's screen: the content folder's {@code huds/hud.json},
 * a full-screen shader object kept as a Studio document in
 * {@code dialogs-src/hud.json} and built by Studio's exporter (linked with the
 * other addons' HUDs, see {@code rpe-addons/link-huds.mjs}). This class only
 * writes its values, and only the ones that changed.
 *
 * <p>A run is one colour, and that colour is its address in the HUD's shader,
 * so everything in more than one colour is a picture that arrives coloured, in
 * a white run: the item window, the minimap and its dots, the lap panel, the
 * gauge, the place, the lights, the countdown and the banners, the speed lines.
 * They are {@code tools/hud_art.py}'s, by the ids used here.
 *
 * <table>
 *   <caption>Values</caption>
 *   <tr><td>{@code gk_item}, {@code gk_item_name}, {@code gk_map}</td><td>top left: the item, what it does, the minimap</td></tr>
 *   <tr><td>{@code gk_lap}, {@code gk_time}, {@code gk_best}, {@code gk_feed}</td><td>top right, and top centre</td></tr>
 *   <tr><td>{@code gk_p1}..{@code gk_p8}, {@code gk_p<n>_c}</td><td>the standings (right)</td></tr>
 *   <tr><td>{@code gk_gauge}, {@code gk_pos}</td><td>bottom right</td></tr>
 *   <tr><td>{@code gk_lights}, {@code gk_center}, {@code gk_center2}, {@code gk_lines_l}, {@code gk_lines_r}</td><td>the middle, and the sides</td></tr>
 * </table>
 */
final class Hud {
    static final List<String> VALUES;

    static {
        List<String> v = new java.util.ArrayList<>(List.of("gk_item", "gk_item_name", "gk_map", "gk_lap", "gk_time", "gk_best",
                "gk_feed", "gk_gauge", "gk_pos", "gk_lights", "gk_center", "gk_center2", "gk_lines_l", "gk_lines_r"));
        for (int i = 1; i <= 8; i++) {
            v.add("gk_p" + i);
            v.add("gk_p" + i + "_c");
        }
        VALUES = List.copyOf(v);
    }

    /** The gauge's steps, and its width in GUI pixels (tools/hud_art.py's GAUGE_STEPS and gauge()). */
    private static final int GAUGE_STEPS = 12, GAUGE_W = 72;
    /** The minimap's size and its dots' (hud_art.MAP, DOT, DOT_STEP). */
    private static final int MAP = 96, DOT = 6, DOT_ME = 8;
    private static final int ROLL_FRAMES = 8;
    /** The cruise speed's share of the gauge: a boost takes it to the end. */
    private static final double GAUGE_TOP_KMH = 70;

    /** Each prefab's minimap frame, from prefabs/minimap.json: (u, v) to a pixel of the map. */
    private record MapFrame(double u0, double v1, double k, double ox, double oy, int step) {
    }

    private static final Map<String, MapFrame> MAPS = loadMaps();

    private final Race race;
    private final Map<UUID, Map<String, String>> drawn = new HashMap<>();
    private final Map<String, String> glyphs = new HashMap<>();

    Hud(Race race) {
        this.race = race;
    }

    private static Map<String, MapFrame> loadMaps() {
        Map<String, MapFrame> out = new HashMap<>();
        try (InputStream in = Hud.class.getClassLoader().getResourceAsStream("prefabs/minimap.json")) {
            if (in == null) return out;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String id : root.keySet()) {
                JsonObject f = root.getAsJsonObject(id);
                out.put(id, new MapFrame(f.get("u0").getAsDouble(), f.get("v1").getAsDouble(), f.get("k").getAsDouble(),
                        f.get("ox").getAsDouble(), f.get("oy").getAsDouble(), f.get("dotStep").getAsInt()));
            }
        } catch (Exception e) {
            // No minimaps: the HUD draws without one.
        }
        return out;
    }

    private Overlays overlays() {
        return race.content().engine().overlays();
    }

    private String hudId() {
        return race.settings().hud;
    }

    void join(Player p) {
        drawn.remove(p.getUniqueId());
        for (String key : VALUES) overlays().set(p, key, "");
        overlays().show(p, hudId());
    }

    void leave(Player p) {
        overlays().hide(p, hudId());
        for (String key : VALUES) overlays().set(p, key, null);
        drawn.remove(p.getUniqueId());
    }

    void tick() {
        for (Racer r : race.order) {
            Player p = r.player();
            if (p == null) continue;
            Map<String, String> v = values(r);
            Map<String, String> last = drawn.computeIfAbsent(r.id, k -> new HashMap<>());
            boolean changed = false;
            for (Map.Entry<String, String> e : v.entrySet()) {
                if (!e.getValue().equals(last.get(e.getKey()))) {
                    overlays().set(p, e.getKey(), e.getValue());
                    last.put(e.getKey(), e.getValue());
                    changed = true;
                }
            }
            if (changed && !overlays().isShowing(p, ContentId.parse(hudId()).orElseThrow())) overlays().show(p, hudId());
            if (r.bannerTicks > 0) r.bannerTicks--;
            if (r.sublineTicks > 0) r.sublineTicks--;
        }
    }

    private Map<String, String> values(Racer r) {
        var m = race.messages();
        Map<String, String> v = new LinkedHashMap<>();
        Race.Phase phase = race.phase;
        long tick = race.plugin.ticks();

        // ---- top left: the item window, and the minimap.
        if (r.roulette > 0) {
            v.put("gk_item", icon("item_roll_" + (r.roulette / 2) % ROLL_FRAMES));
            v.put("gk_item_name", m.plain("item-roulette"));
        } else if (r.item == null) {
            v.put("gk_item", icon("item_empty"));
            v.put("gk_item_name", m.plain("item-none"));
        } else {
            String window = switch (r.item) {
                case BANANA -> "item_banana";
                case NITRO -> "item_nitro";
                case ROCKET -> "item_rocket";
                case TRIPLE_NITRO -> r.itemUses >= 3 ? "item_triple_3" : r.itemUses == 2 ? "item_triple_2" : "item_nitro";
            };
            v.put("gk_item", icon(window));
            v.put("gk_item_name", m.plain("item-" + r.item.key, "left", String.valueOf(r.itemUses)));
        }
        v.put("gk_map", minimap(r));

        // ---- top right, and the feed.
        int lap = Math.max(1, Math.min(r.lap, race.laps));
        v.put("gk_lap", race.laps <= 5 ? icon("lap_" + lap + "_" + race.laps)
                : letters(m.plain("hud-lap") + " ", "grey", 2) + letters(lap + "/" + race.laps, "white", 3));
        long shown = r.finished ? r.finishTicks : race.clock;
        v.put("gk_time", Text.raceClock(phase == Race.Phase.RACING ? shown : 0));
        v.put("gk_best", r.bestLap < 0 ? "" : m.plain("hud-best", "time", Text.raceClock(r.bestLap)));
        v.put("gk_feed", race.feedTicks > 0 ? race.feed : "");

        // ---- the standings.
        List<Racer> order = race.order;
        for (int i = 1; i <= 8; i++) {
            Racer o = i <= order.size() ? order.get(i - 1) : null;
            v.put("gk_p" + i, o == null ? "" : icon("chip_" + o.colour.id()) + overlays().space(3) + i + ". " + o.name);
            v.put("gk_p" + i + "_c", o == r ? "me" : "");
        }

        // ---- bottom right: the gauge and the place.
        v.put("gk_gauge", gauge(r));
        v.put("gk_pos", icon("place_" + Math.max(1, Math.min(8, r.finished ? r.place : Math.max(1, r.position)))));

        // ---- the middle.
        v.put("gk_lights", lights(phase));
        v.put("gk_center", center(r, phase));
        v.put("gk_center2", r.sublineTicks > 0 ? r.subline : "");
        boolean boosting = r.kart != null && race.plugin.driving().boosting(r.kart);
        int frame = (int) ((tick / 2) % 3);
        v.put("gk_lines_l", boosting ? icon("lines_l_" + frame) : "");
        v.put("gk_lines_r", boosting ? icon("lines_r_" + frame) : "");
        return v;
    }

    /** The gauge for this speed, drift and boost, and the km/h centred inside it. */
    private String gauge(Racer r) {
        double speed = r.kart == null ? 0 : Math.abs(r.kart.groundSpeed());
        long kmh = Math.round(speed * 3.6);
        int step = (int) Math.max(0, Math.min(GAUGE_STEPS, Math.round(kmh / GAUGE_TOP_KMH * GAUGE_STEPS)));
        int drift = r.kart == null ? 0 : race.plugin.driving().driftLevel(r.kart);
        boolean boost = r.kart != null && race.plugin.driving().boosting(r.kart) && drift == 0;
        String digits = String.valueOf(kmh);
        StringBuilder out = new StringBuilder(icon("gauge_" + step + "_" + drift + "_" + (boost ? 1 : 0)));
        // The gauge moved the cursor its width and the gap; come back to centre the digits in it.
        int digitsWidth = digits.length() * 10;
        out.append(overlays().space(GAUGE_W / 2 - digitsWidth / 2 - (GAUGE_W + 1)));
        for (char c : digits.toCharArray()) out.append(icon("g_" + c));
        // And on past the gauge's end, so the run measures as the gauge.
        out.append(overlays().space(GAUGE_W + 1 - (GAUGE_W / 2 - digitsWidth / 2) - digitsWidth));
        return out.toString();
    }

    /**
     * The minimap and a dot for every racer, placed in one run: back to the
     * map's corner after drawing it, then for each dot across to it, the dot
     * (a glyph per row, hung so its centre is on that row), and back again.
     */
    private String minimap(Racer me) {
        String prefab = race.track.prefab();
        MapFrame f = prefab == null ? null : MAPS.get(prefab);
        if (f == null) return "";
        StringBuilder out = new StringBuilder(icon("map_" + prefab));
        out.append(overlays().space(-(MAP + 1)));
        for (int pass = 0; pass < 2; pass++) {
            for (Racer r : race.order) {
                boolean mine = r == me;
                if ((pass == 0) == mine || r.kart == null || !r.kart.isValid() || r.colour == null) continue;
                Location l = r.kart.location();
                double[] uv = race.track.mapPosition(l.getX(), l.getZ());
                if (uv == null) continue;
                double px = (uv[0] - f.u0()) * f.k() + f.ox();
                double py = (f.v1() - uv[1]) * f.k() + f.oy();
                int size = mine ? DOT_ME : DOT;
                int x = (int) Math.round(Math.max(0, Math.min(MAP - size, px - size / 2.0)));
                int row = (int) Math.round(Math.max(0, Math.min(MAP - 1, py)) / f.step());
                row = Math.min(row, MAP / f.step() - 1);
                String dot = icon("dot_" + r.colour.id() + (mine ? "_me" : "") + "_" + row);
                if (dot.isEmpty()) continue;
                out.append(overlays().space(x)).append(dot).append(overlays().space(-(x + size + 1)));
            }
        }
        out.append(overlays().space(MAP + 1));
        return out.toString();
    }

    /** The start lights: red one by one on the grid, all green for a moment at GO. */
    private String lights(Race.Phase phase) {
        int t = race.phaseTicks;
        if (phase == Race.Phase.GRID) {
            int lit = t >= Race.LIGHT_3 ? 3 : t >= Race.LIGHT_2 ? 2 : t >= Race.LIGHT_1 ? 1 : 0;
            return icon("lights_" + lit);
        }
        if (phase == Race.Phase.RACING && race.clock < 30) return icon("lights_go");
        return "";
    }

    /**
     * The middle of the screen: the countdown on the grid, popping in as each
     * light comes on, then whatever banner the racer is being shown.
     */
    private String center(Racer r, Race.Phase phase) {
        int t = race.phaseTicks;
        if (phase == Race.Phase.GRID && t >= Race.LIGHT_1) {
            int since = t >= Race.LIGHT_3 ? t - Race.LIGHT_3 : t >= Race.LIGHT_2 ? t - Race.LIGHT_2 : t - Race.LIGHT_1;
            String n = t >= Race.LIGHT_3 ? "1" : t >= Race.LIGHT_2 ? "2" : "3";
            return icon("count_" + n + "_" + pop(since));
        }
        if (phase == Race.Phase.RACING && race.clock < 24) return icon("count_go_" + pop((int) race.clock));
        if (r.bannerTicks > 0 && r.banner != null) return icon(r.banner);
        return "";
    }

    /** The countdown's pop: big for a moment, then settling. */
    private static int pop(int since) {
        return since < 2 ? 0 : since < 4 ? 1 : 2;
    }

    static String ordinal(int n) {
        int t = n % 100;
        String suffix = (t >= 11 && t <= 13) ? "TH" : switch (n % 10) {
            case 1 -> "ST";
            case 2 -> "ND";
            case 3 -> "RD";
            default -> "TH";
        };
        return n + suffix;
    }

    /** Text spelled out of the HUD's lettering: one glyph per character, a space where there is one. */
    String letters(String text, String sheet, int scale) {
        StringBuilder out = new StringBuilder();
        for (char ch : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ch == ' ') {
                out.append(overlays().space(5 * scale));
                continue;
            }
            String id = charId(ch);
            if (id == null) continue;
            out.append(icon("t_" + sheet + scale + "_" + id));
        }
        return out.toString();
    }

    private static String charId(char ch) {
        if (ch >= 'A' && ch <= 'Z' || ch >= '0' && ch <= '9') return String.valueOf(Character.toLowerCase(ch));
        return switch (ch) {
            case '!' -> "bang";
            case '-' -> "dash";
            case '\'' -> "apos";
            case '/' -> "slash";
            case ':' -> "colon";
            case '.' -> "dot";
            default -> null;
        };
    }

    /** An icon's character. A missing one is asked again next time, since the pack may still be building. */
    private String icon(String id) {
        String c = glyphs.get(id);
        if (c != null) return c;
        c = race.content().icon(id, "");
        if (!c.isEmpty()) glyphs.put(id, c);
        return c;
    }
}
