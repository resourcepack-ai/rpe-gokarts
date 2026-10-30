package ai.resourcepack.addons.gokarts.command;

import java.util.List;

/**
 * Every permission node the plugin checks, in one place.
 *
 * <p>One node per thing a server might want to hand out on its own.
 * {@code gokarts.play} and {@code gokarts.admin} are in {@code plugin.yml} only
 * as convenience parents: granting one grants every node below it, and nothing
 * in the code checks either of them. {@code PermsTest} holds this class and
 * {@code plugin.yml} to each other.
 */
public final class Perms {
    private Perms() {
    }

    // ---- players (children of gokarts.play, everyone by default) -----------------

    /** {@code /kart join}: into a track's lobby. */
    public static final String JOIN = "gokarts.join";
    /** {@code /kart colour}: the kart you would like to drive. */
    public static final String COLOUR = "gokarts.colour";
    /** {@code /kart results}: the last race's results. */
    public static final String RESULTS = "gokarts.results";
    /** Getting into a kart at all, a parked one included. Checked by RP Engine as the vehicles' {@code permission:}. */
    public static final String RIDE = "gokarts.ride";

    // ---- staff (children of gokarts.admin, ops by default) -----------------------

    /** {@code /kart start}, and being told in the lobby that you can. */
    public static final String START = "gokarts.start";
    /** {@code /kart stop}. */
    public static final String STOP = "gokarts.stop";
    /** {@code /kart reload}. */
    public static final String RELOAD = "gokarts.reload";
    /** {@code /kart give}: a kart to park and drive anywhere. */
    public static final String GIVE = "gokarts.give";
    /** {@code /kart give <colour> <player>}: one for somebody else. */
    public static final String GIVE_OTHERS = "gokarts.give.others";
    /** {@code /kart item <item> [player]}: a racer's item, by hand, for testing and events. */
    public static final String ITEM = "gokarts.item";
    /** {@code /kart as <player> ...}: run a subcommand as somebody else. */
    public static final String AS = "gokarts.as";
    /** {@code /kart track} setup (list, create, lobby, grid, checkpoints, boxes, laps, open/close, tp, rename). */
    public static final String TRACK = "gokarts.track";
    /** {@code /kart track generate}, which replaces every block in a large box. */
    public static final String TRACK_GENERATE = "gokarts.track.generate";
    /** {@code /kart track undo}, which puts back what a generate replaced, or deletes the world it made. */
    public static final String TRACK_UNDO = "gokarts.track.undo";
    /** {@code /kart track delete}, which also ends that track's race. */
    public static final String TRACK_DELETE = "gokarts.track.delete";
    /** Running any command during a race, not only the short list a racer may. */
    public static final String BYPASS_COMMANDS = "gokarts.bypass.commands";

    /** What {@code gokarts.play} grants. */
    public static final List<String> PLAY = List.of(JOIN, COLOUR, RESULTS, RIDE);
    /** What {@code gokarts.admin} grants. */
    public static final List<String> ADMIN = List.of(START, STOP, RELOAD, GIVE, GIVE_OTHERS, ITEM, AS, TRACK, TRACK_GENERATE,
            TRACK_UNDO, TRACK_DELETE, BYPASS_COMMANDS);
}
