package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.engine.api.Vehicle;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * One player in a race's lobby, and, while a race is on, their kart and how far
 * round they are. Or a computer driver, which is in the race only: {@link #bot}
 * is set and {@link #player} is null.
 */
final class Racer {
    final UUID id;
    final String name;
    /** The computer driving, for a bot; null for a player. */
    final Bot bot;
    /** The colour they asked for with /kart colour, or null for any. */
    KartColour wanted;
    /** Races in a row this one sat out because the grid was full: they go first next time. */
    int satOut;

    // ---- this race (all reset by Race.resetRace) --------------------------------

    /** In the race being run, rather than waiting for the next one. */
    boolean racing;
    KartColour colour;
    Vehicle kart;
    /** The lap they are on, from 1. Past the race's laps once they have finished. */
    int lap;
    /** The checkpoint they have to pass next. Checkpoint 0 is the line. */
    int next;
    /** The checkpoint they passed last, where a respawn puts them. */
    int last;
    long lastGateTick;
    long lapStartTick;
    long bestLap = -1;
    boolean finished;
    long finishTicks;
    int place;
    /** Where they are in the race right now, from 1. */
    int position;

    Item item;
    int itemUses;
    /** Ticks in a row the kart has been all but standing still: stuck, for Q. */
    int stillTicks;
    /** When this racer's overdrive runs out, in the items' ticks; 0 when it is not running. */
    long overdriveUntil;
    /** Ticks left of the roulette; the item is theirs once it reaches 0. */
    int roulette;

    /** Ticks left before a respawned or stalled kart may move. */
    int frozen;
    /** Ticks left of a pending respawn (the "hold on" before it happens). */
    int respawnIn;
    /** The plugin tick an item was last used on, so one press is one use. */
    long lastUse = -100;
    /** Whether W was down when the second light came on: a stall at GO. */
    boolean jumped;

    /** The banner in the HUD's middle (a glyph id: banner_final, banner_finish...), and for how many more ticks. */
    String banner;
    int bannerTicks;
    /** For WRONG WAY: how far from the next gate, a second ago. */
    double lastGap = Double.NaN;
    int wrongTicks;
    String subline = "";
    int sublineTicks;

    Racer(Player p) {
        this(p.getUniqueId(), p.getName(), null);
    }

    Racer(UUID id, String name, Bot bot) {
        this.id = id;
        this.name = name;
        this.bot = bot;
    }

    /** The player, if this is one and they are online. Always null for a bot. */
    Player player() {
        return bot == null ? Bukkit.getPlayer(id) : null;
    }

    boolean isBot() {
        return bot != null;
    }

    void banner(String glyph, int ticks) {
        banner = glyph;
        bannerTicks = ticks;
    }

    void subline(String text, int ticks) {
        subline = text;
        sublineTicks = ticks;
    }
}
