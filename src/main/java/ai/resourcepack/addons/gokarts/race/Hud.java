package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.engine.api.Overlays;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything on a racer's screen: the content folder's {@code huds/hud.json},
 * a full-screen shader object made in ResourcePack AI Studio
 * ({@code dialogs-src/hud.json}, exported by Studio's
 * {@code scripts/export-dialog-content.mjs}), which RP Engine draws in the
 * action bar. This class only writes its values, and only the ones that
 * changed.
 *
 * <p>A run is one colour, and that colour is its address in the HUD's shader,
 * so anything in more than one colour is a picture that arrives coloured in a
 * white run: the item card, the place, the lap, the start lights and the big
 * words in the middle are glyphs from {@code tools/hud_art.py}. The standings
 * are plain text, and your own line switches to yellow by naming a colour
 * state ({@code gk_p<n>_c = me}) rather than by colouring the text.
 *
 * <table>
 *   <caption>Values</caption>
 *   <tr><td>{@code gk_item}, {@code gk_item_name}</td><td>the item card (top left) and what it does</td></tr>
 *   <tr><td>{@code gk_lap}, {@code gk_time}, {@code gk_best}</td><td>top right</td></tr>
 *   <tr><td>{@code gk_p1}..{@code gk_p8}, {@code gk_p<n>_c}</td><td>the standings (right)</td></tr>
 *   <tr><td>{@code gk_speed}, {@code gk_drift}, {@code gk_pos}</td><td>bottom right: speed, the drift charge bar (0-100), the place</td></tr>
 *   <tr><td>{@code gk_lights}, {@code gk_center}, {@code gk_center2}</td><td>the middle</td></tr>
 * </table>
 */
final class Hud {
    static final List<String> VALUES;

    static {
        List<String> v = new java.util.ArrayList<>(List.of("gk_item", "gk_item_name", "gk_lap", "gk_time", "gk_best",
                "gk_speed", "gk_drift", "gk_pos", "gk_lights", "gk_center", "gk_center2"));
        for (int i = 1; i <= 8; i++) {
            v.add("gk_p" + i);
            v.add("gk_p" + i + "_c");
        }
        VALUES = List.copyOf(v);
    }

    /** The cards the roulette flicks through. */
    private static final String[] ROULETTE = {"card_box", "card_banana", "card_nitro", "card_rocket"};

    private final Race race;
    private final Map<UUID, Map<String, String>> drawn = new HashMap<>();
    private final Map<String, String> glyphs = new HashMap<>();

    Hud(Race race) {
        this.race = race;
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
            if (changed && !overlays().isShowing(p, ai.resourcepack.engine.api.ContentId.parse(hudId()).orElseThrow())) {
                overlays().show(p, hudId());
            }
            if (r.bannerTicks > 0) r.bannerTicks--;
            if (r.sublineTicks > 0) r.sublineTicks--;
        }
    }

    private Map<String, String> values(Racer r) {
        var m = race.messages();
        Map<String, String> v = new LinkedHashMap<>();
        Race.Phase phase = race.phase;

        // The item card.
        if (r.roulette > 0) {
            v.put("gk_item", icon(ROULETTE[(r.roulette / 3) % ROULETTE.length]));
            v.put("gk_item_name", m.plain("item-roulette"));
        } else if (r.item == null) {
            v.put("gk_item", icon("card_empty"));
            v.put("gk_item_name", m.plain("item-none"));
        } else {
            String card = switch (r.item) {
                case BANANA -> "card_banana";
                case NITRO -> "card_nitro";
                case ROCKET -> "card_rocket";
                case TRIPLE_NITRO -> r.itemUses >= 3 ? "card_triple_3" : r.itemUses == 2 ? "card_triple_2" : "card_nitro";
            };
            v.put("gk_item", icon(card));
            v.put("gk_item_name", m.plain("item-" + r.item.key, "left", String.valueOf(r.itemUses)));
        }

        // Top right.
        int lap = Math.min(r.lap, race.laps);
        v.put("gk_lap", letters(m.plain("hud-lap") + " ", "grey", 2) + letters(Math.max(1, lap) + "/" + race.laps, "white", 3));
        long shown = r.finished ? r.finishTicks : race.clock;
        v.put("gk_time", phase == Race.Phase.RACING ? Text.raceClock(shown) : Text.raceClock(0));
        v.put("gk_best", r.bestLap < 0 ? "" : m.plain("hud-best", "time", Text.raceClock(r.bestLap)));

        // The standings.
        List<Racer> order = race.order;
        for (int i = 1; i <= 8; i++) {
            Racer o = i <= order.size() ? order.get(i - 1) : null;
            v.put("gk_p" + i, o == null ? "" : i + ". " + o.name);
            v.put("gk_p" + i + "_c", o == r ? "me" : "");
        }

        // Bottom right.
        double speed = r.kart == null ? 0 : r.kart.groundSpeed();
        v.put("gk_speed", m.plain("hud-speed", "speed", String.valueOf(Math.round(Math.abs(speed) * 3.6))));
        v.put("gk_drift", String.valueOf(r.kart == null ? 0 : race.plugin.driving().driftDisplay(r.kart)));
        v.put("gk_pos", place(r.finished ? r.place : Math.max(1, r.position), order.size()));

        // The middle.
        v.put("gk_lights", lights(phase));
        v.put("gk_center", r.bannerTicks > 0 ? letters(r.banner, r.bannerSheet, r.bannerScale) : "");
        v.put("gk_center2", r.sublineTicks > 0 ? r.subline : "");
        return v;
    }

    /** The start lights: three red, lighting one a second, then all green for a moment. */
    private String lights(Race.Phase phase) {
        int t = race.phaseTicks;
        if (phase == Race.Phase.GRID) {
            StringBuilder s = new StringBuilder();
            int lit = t >= Race.LIGHT_3 ? 3 : t >= Race.LIGHT_2 ? 2 : t >= Race.LIGHT_1 ? 1 : 0;
            for (int i = 0; i < 3; i++) {
                if (i > 0) s.append(overlays().space(4));
                s.append(icon(i < lit ? "light_red" : "light_off"));
            }
            return s.toString();
        }
        if (phase == Race.Phase.RACING && race.clock < 30) {
            String g = icon("light_green");
            return g + overlays().space(4) + g + overlays().space(4) + g;
        }
        return "";
    }

    /** "1ST" big, in gold, silver or bronze for the podium, then "/8" small. */
    private String place(int place, int of) {
        String sheet = switch (place) {
            case 1 -> "gold";
            case 2 -> "silver";
            case 3 -> "bronze";
            default -> "white";
        };
        return letters(ordinal(place), sheet, 4) + overlays().space(2) + letters("/" + of, "grey", 2);
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

    /**
     * Text spelled out of the HUD's lettering: one glyph per character, a
     * space where there is one, anything the sheets do not have left out.
     */
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
