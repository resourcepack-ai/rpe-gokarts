package ai.resourcepack.addons.gokarts.race;

/** What an item box can give. Each has a card on the HUD and a line under it (config.yml's {@code item-*}). */
enum Item {
    BANANA("banana", 1, "banana"),
    BANANA_BUNCH("banana-bunch", 3, "banana"),
    DECOY("decoy", 1, "decoy_box"),
    ORB("orb", 1, "orb"),
    NITRO("nitro", 1, "nitro"),
    TRIPLE_NITRO("triple-nitro", 3, "nitro"),
    ROCKET("rocket", 1, "rocket"),
    DRONE("drone", 1, "drone"),
    OVERDRIVE("overdrive", 1, "overdrive"),
    SHIELD("shield", 1, "shield"),
    SMOKE("smoke", 1, "smoke_bomb"),
    HORN("horn", 1, "horn"),
    SURGE("surge", 1, "surge");

    /** Its name in config.yml's {@code items.odds} and {@code messages.item-*}. */
    final String key;
    /** How many times it can be used before it is gone. */
    final int uses;
    /**
     * The content item that shows it in the hotbar, and the HUD window that
     * shows it top left ({@code item_<look>}, or for an item used more than
     * once, one window per count left).
     */
    final String look;

    Item(String key, int uses, String look) {
        this.key = key;
        this.uses = uses;
        this.look = look;
    }

    static Item parse(String key) {
        for (Item i : values()) if (i.key.equals(key)) return i;
        return null;
    }
}
