package ai.resourcepack.addons.gokarts.race;

import java.util.Locale;

/**
 * The eight karts, one per grid slot. Each is its own RP Engine vehicle
 * ({@code gokarts:kart_<id>}) wearing its own item's model
 * ({@code gokarts:kart_<id>_item}), all of them the same Studio kart painted
 * by {@code tools/make_assets.py}, which has the same list and the same colours.
 */
public enum KartColour {
    RED("red", 0xFF4655, 'c'),
    BLUE("blue", 0x2E9BFF, '9'),
    GREEN("green", 0x5BE37D, 'a'),
    YELLOW("yellow", 0xFFD23F, 'e'),
    ORANGE("orange", 0xFF8A3D, '6'),
    PURPLE("purple", 0xA45CFF, '5'),
    PINK("pink", 0xFF5CC8, 'd'),
    CYAN("cyan", 0x3DE0E0, 'b');

    private final String id;
    private final int rgb;
    private final char code;

    KartColour(String id, int rgb, char code) {
        this.id = id;
        this.rgb = rgb;
        this.code = code;
    }

    public String id() {
        return id;
    }

    public int rgb() {
        return rgb;
    }

    /** {@code #rrggbb}, for MiniMessage. */
    public String hex() {
        return String.format("#%06X", rgb);
    }

    /** The legacy colour code nearest it, for a name tag. */
    public char code() {
        return code;
    }

    /** The vehicle's content id, without the namespace. */
    public String vehicle() {
        return "kart_" + id;
    }

    /** The item that parks it, without the namespace. */
    public String item() {
        return "kart_" + id + "_item";
    }

    public static KartColour parse(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.equals("grey") || k.equals("gray")) return null;
        for (KartColour c : values()) if (c.id.equals(k)) return c;
        return null;
    }

    /** Which colour a vehicle id is, or null when it is not one of ours. */
    public static KartColour ofVehicle(String path) {
        if (path == null || !path.startsWith("kart_")) return null;
        return parse(path.substring(5));
    }
}
