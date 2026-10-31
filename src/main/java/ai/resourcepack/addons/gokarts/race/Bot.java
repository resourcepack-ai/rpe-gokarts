package ai.resourcepack.addons.gokarts.race;

import java.util.UUID;

/**
 * A computer driver in a race: who it looks like, how well it drives, and
 * where it has got to. Its kart and its place in the race are its
 * {@link Racer}'s, like anybody's; this is only what a player would have
 * kept in their head. See {@link Bots}.
 */
final class Bot {
    /** One of the game's own player skins, as a texture path under entity/player/. */
    final String skin;
    /** 0.85 to 1: how far it looks ahead, how often it drifts, how quickly it fires. */
    final double skill;
    /** Blocks to one side of the road's centre it likes to drive, so eight bots are not one queue. */
    final double lane;

    /** The mannequin in its seat, once there is one. */
    UUID figure;
    /** The scoreboard tag its mannequin is summoned with, and how often, and the ticks left to wait for it. */
    String tag;
    int summons;
    int summonWait;
    /** Where on the racing line it was last tick, or -1 to look for itself. */
    int index = -1;

    boolean drifting;
    int driftTicks;
    double driftSide;
    int reverseTicks;
    int airTicks;
    boolean tricking;
    /** The race tick it will use its item on, or -1 while it has none to use. */
    long itemAt = -1;

    Bot(String skin, double skill, double lane) {
        this.skin = skin;
        this.skill = skill;
        this.lane = lane;
    }

    /** Forgets where it was and what it was doing: a new kart, somewhere else. */
    void reset() {
        index = -1;
        drifting = false;
        driftTicks = 0;
        reverseTicks = 0;
        airTicks = 0;
        tricking = false;
    }
}
