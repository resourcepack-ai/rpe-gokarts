package ai.resourcepack.addons.gokarts.race;

/** What an item box can give. Each has a card on the HUD and a line under it (config.yml's {@code item-*}). */
enum Item {
    BANANA("banana", 1),
    NITRO("nitro", 1),
    TRIPLE_NITRO("triple-nitro", 3),
    ROCKET("rocket", 1);

    /** Its name in config.yml's {@code items.odds} and {@code messages.item-*}. */
    final String key;
    /** How many times it can be used before it is gone. */
    final int uses;

    Item(String key, int uses) {
        this.key = key;
        this.uses = uses;
    }

    static Item parse(String key) {
        for (Item i : values()) if (i.key.equals(key)) return i;
        return null;
    }
}
