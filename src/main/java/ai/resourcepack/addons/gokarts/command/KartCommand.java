package ai.resourcepack.addons.gokarts.command;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.race.KartColour;
import ai.resourcepack.addons.gokarts.race.Race;
import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.addons.gokarts.track.Track;
import ai.resourcepack.addons.gokarts.track.TrackBuilder;
import ai.resourcepack.addons.gokarts.track.BlockUndo;
import ai.resourcepack.addons.gokarts.track.TrackStore;
import ai.resourcepack.addons.gokarts.track.TrackUndo;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /kart}: everything a racer and a member of staff does. Every
 * subcommand checks its own permission ({@link Perms}); {@code /kart leave}
 * needs none, so nobody can be stuck in a race.
 */
public final class KartCommand implements TabExecutor {
    private static final List<String> TRACK_SUBS = List.of("list", "generate", "undo", "create", "setlobby", "addgrid", "cleargrid",
            "addcheckpoint", "clearcheckpoints", "addbox", "clearboxes", "laps", "enable", "disable", "tp", "name", "delete");

    private final GoKartsPlugin plugin;

    public KartCommand(GoKartsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (sub) {
            case "join" -> join(sender, rest);
            case "leave" -> leave(sender);
            case "colour", "color" -> colour(sender, rest);
            case "results" -> results(sender);
            case "start" -> start(sender, rest);
            case "stop" -> stop(sender, rest);
            case "give" -> give(sender, rest);
            case "reload" -> reload(sender);
            case "as" -> as(sender, rest);
            case "track", "tracks" -> track(sender, rest);
            default -> help(sender);
        }
        return true;
    }

    // ---- players -------------------------------------------------------------------------

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        Text.send(s, plugin.messages().component("player-only"));
        return null;
    }

    private boolean allowed(CommandSender s, String node) {
        if (s.hasPermission(node)) return true;
        Text.send(s, plugin.messages().component("no-permission"));
        return false;
    }

    private void join(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null || !allowed(p, Perms.JOIN)) return;
        Track t = a.length > 0 ? plugin.tracks().get(a[0]) : plugin.races().defaultTrack();
        if (a.length > 0 && t == null) {
            Text.send(p, Text.error("No track called " + Text.MM.escapeTags(a[0]) + "."));
            return;
        }
        plugin.races().join(p, t);
    }

    private void leave(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        if (plugin.races().of(p) == null) {
            Text.send(p, plugin.messages().component("not-in"));
            return;
        }
        plugin.races().leave(p);
        Text.send(p, plugin.messages().component("left"));
    }

    private void colour(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null || !allowed(p, Perms.COLOUR)) return;
        String list = Arrays.stream(KartColour.values()).map(KartColour::id).collect(Collectors.joining(", "));
        KartColour c = a.length > 0 ? KartColour.parse(a[0]) : null;
        if (c == null) {
            Text.send(p, plugin.messages().component("colour-unknown", "colour", a.length > 0 ? a[0] : "?", "list", list));
            return;
        }
        plugin.colours().put(p.getUniqueId(), c);
        Race r = plugin.races().of(p);
        if (r != null) r.wantColour(p, c);
        Text.send(p, Text.mm(plugin.messages().raw("colour-set").replace("{prefix}", plugin.messages().raw("prefix"))
                .replace("{hex}", c.hex()).replace("{colour}", c.id())));
    }

    private void results(CommandSender s) {
        Player p = player(s);
        if (p == null || !allowed(p, Perms.RESULTS)) return;
        Race r = plugin.races().of(p);
        if (r == null) {
            Track t = plugin.races().defaultTrack();
            r = t == null ? null : plugin.races().existing(t.id());
        }
        if (r == null || r.lastResults().isEmpty()) {
            Text.send(p, Text.error("There are no results yet."));
            return;
        }
        plugin.results().open(p, r);
    }

    // ---- staff -------------------------------------------------------------------------

    private void start(CommandSender s, String[] a) {
        if (!allowed(s, Perms.START)) return;
        Race r = null;
        if (s instanceof Player p) r = plugin.races().of(p);
        if (a.length > 0) {
            Track t = plugin.tracks().get(a[0]);
            if (t == null) {
                Text.send(s, Text.error("No track called " + Text.MM.escapeTags(a[0]) + "."));
                return;
            }
            r = plugin.races().existing(t.id());
            if (r == null && s instanceof Player p && plugin.races().of(p) == null) {
                if (!plugin.races().join(p, t)) return;
                r = plugin.races().of(p);
            }
        } else if (r == null && s instanceof Player p) {
            if (!plugin.races().join(p, plugin.races().defaultTrack())) return;
            r = plugin.races().of(p);
        }
        if (r == null) {
            Text.send(s, Text.error("Nobody is waiting to race. /kart start <track> from in its lobby."));
            return;
        }
        if (!r.start()) Text.send(s, Text.error("That race can't start now (" + r.phase().name().toLowerCase(Locale.ROOT) + ")."));
    }

    private void stop(CommandSender s, String[] a) {
        if (!allowed(s, Perms.STOP)) return;
        Race r = null;
        if (a.length > 0) {
            Track t = plugin.tracks().get(a[0]);
            r = t == null ? null : plugin.races().existing(t.id());
        } else if (s instanceof Player p) {
            r = plugin.races().of(p);
        }
        if (r == null) {
            Text.send(s, Text.error("No race to stop."));
            return;
        }
        plugin.races().stop(r);
        Text.send(s, Text.msg("Stopped."));
    }

    private void give(CommandSender s, String[] a) {
        if (!allowed(s, Perms.GIVE)) return;
        KartColour c = a.length > 0 ? KartColour.parse(a[0]) : KartColour.RED;
        if (c == null) {
            String list = Arrays.stream(KartColour.values()).map(KartColour::id).collect(Collectors.joining(", "));
            Text.send(s, plugin.messages().component("colour-unknown", "colour", a[0], "list", list));
            return;
        }
        Player to;
        if (a.length > 1) {
            if (!allowed(s, Perms.GIVE_OTHERS)) return;
            to = Bukkit.getPlayerExact(a[1]);
            if (to == null) {
                Text.send(s, plugin.messages().component("unknown-player", "player", a[1]));
                return;
            }
        } else {
            to = player(s);
            if (to == null) return;
        }
        ItemStack kart = plugin.content().item(c.item());
        to.getInventory().addItem(kart).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
        Text.send(to, Text.mm(plugin.messages().raw("given").replace("{prefix}", plugin.messages().raw("prefix"))
                .replace("{hex}", c.hex()).replace("{colour}", c.id())));
        if (to != s) {
            Text.send(s, Text.mm(plugin.messages().raw("given-other").replace("{prefix}", plugin.messages().raw("prefix"))
                    .replace("{player}", Text.MM.escapeTags(to.getName())).replace("{hex}", c.hex()).replace("{colour}", c.id())));
        }
    }

    private void reload(CommandSender s) {
        if (!allowed(s, Perms.RELOAD)) return;
        boolean tracks = plugin.reloadSettings();
        Text.send(s, plugin.messages().component("reloaded", "tracks",
                plugin.messages().raw(tracks ? "reloaded-tracks" : "reloaded-not-tracks")));
    }

    /** /kart as <player> <subcommand...>: for NPCs and the console. Checked against the target's permissions. */
    private void as(CommandSender s, String[] a) {
        if (!allowed(s, Perms.AS)) return;
        if (a.length < 2) {
            Text.send(s, Text.error("/kart as <player> <subcommand...>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(a[0]);
        if (target == null) {
            Text.send(s, plugin.messages().component("unknown-player", "player", a[0]));
            return;
        }
        if (a[1].equalsIgnoreCase("as")) return;
        onCommand(target, null, "kart", Arrays.copyOfRange(a, 1, a.length));
    }

    // ---- tracks ---------------------------------------------------------------------------

    private void track(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        String node = switch (sub) {
            case "generate" -> Perms.TRACK_GENERATE;
            case "undo" -> Perms.TRACK_UNDO;
            case "delete" -> Perms.TRACK_DELETE;
            default -> Perms.TRACK;
        };
        if (!allowed(s, node)) return;
        if (sub.equals("list")) {
            listTracks(s);
            return;
        }
        if (a.length < 2) {
            Text.send(s, Text.error("/kart track " + sub + " <id> ..."));
            return;
        }
        String id = TrackStore.normalise(a[1]);
        if (!TrackStore.validId(id)) {
            Text.send(s, Text.error("A track id is lowercase letters, digits, - and _."));
            return;
        }
        if (sub.equals("generate")) {
            generate(s, id, Arrays.copyOfRange(a, 2, a.length));
            return;
        }
        if (sub.equals("undo")) {
            undo(s, id, Arrays.copyOfRange(a, 2, a.length));
            return;
        }
        if (sub.equals("create")) {
            if (plugin.tracks().get(id) != null) {
                Text.send(s, Text.error("There is already a track called " + id + "."));
                return;
            }
            Track t = plugin.tracks().create(id);
            if (s instanceof Player p) t.world(p.getWorld());
            plugin.tracks().save();
            Text.send(s, Text.msg("Created " + id + ". Now: setlobby, addgrid (each slot), addcheckpoint (the line first, then round the lap), addbox, enable."));
            return;
        }
        Track t = plugin.tracks().get(id);
        if (t == null) {
            Text.send(s, Text.error("No track called " + id + "."));
            return;
        }
        Location here = s instanceof Player p ? p.getLocation() : null;
        switch (sub) {
            case "setlobby" -> {
                if (here == null) { player(s); return; }
                t.lobby(here);
                done(s, "Lobby set.");
            }
            case "addgrid" -> {
                if (here == null) { player(s); return; }
                if (!here.getWorld().getName().equals(t.worldName())) t.world(here.getWorld());
                t.grid().add(here.clone());
                done(s, "Grid slot " + t.grid().size() + " added, facing the way you face.");
            }
            case "cleargrid" -> {
                t.grid().clear();
                done(s, "Grid cleared.");
            }
            case "addcheckpoint" -> {
                if (here == null) { player(s); return; }
                double radius = a.length > 2 ? parse(a[2], 6) : 6;
                t.checkpoints().add(new Track.Checkpoint(here.getX(), here.getY(), here.getZ(), radius));
                int n = t.checkpoints().size() - 1;
                done(s, "Checkpoint " + n + (n == 0 ? " (the start/finish line)" : "") + " added, radius " + radius + ".");
            }
            case "clearcheckpoints" -> {
                t.checkpoints().clear();
                done(s, "Checkpoints cleared.");
            }
            case "addbox" -> {
                if (here == null) { player(s); return; }
                t.boxes().add(new Vector(here.getX(), here.getY() + 0.9, here.getZ()));
                done(s, "Item box " + t.boxes().size() + " added.");
            }
            case "clearboxes" -> {
                t.boxes().clear();
                done(s, "Item boxes cleared.");
            }
            case "laps" -> {
                int laps = a.length > 2 ? (int) parse(a[2], 0) : 0;
                t.laps(laps);
                done(s, laps <= 0 ? "Laps back to config.yml's." : "Laps: " + laps + ".");
            }
            case "enable" -> {
                if (!t.missing().isEmpty()) {
                    Text.send(s, Text.error(t.id() + " still needs " + String.join(", ", t.missing()) + "."));
                    return;
                }
                t.enabled(true);
                done(s, t.id() + " is open.");
            }
            case "disable" -> {
                t.enabled(false);
                plugin.races().shut(t);
                done(s, t.id() + " is closed.");
            }
            case "tp" -> {
                Player p = player(s);
                if (p == null) return;
                Location to = t.lobby() != null ? t.lobby() : !t.grid().isEmpty() ? t.grid().get(0) : null;
                if (to == null || t.world() == null) {
                    Text.send(s, Text.error(t.id() + " has nowhere to go to yet."));
                    return;
                }
                to.setWorld(t.world());
                p.teleport(to);
            }
            case "name" -> {
                if (a.length < 3) {
                    Text.send(s, Text.error("/kart track name <id> <name...>"));
                    return;
                }
                t.name(String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                done(s, "Named " + Text.MM.escapeTags(t.name()) + ".");
            }
            case "delete" -> {
                if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
                    Text.send(s, Text.error("/kart track delete " + id + " confirm - this ends its race and forgets the track (the blocks stay)."));
                    return;
                }
                plugin.races().shut(t);
                plugin.tracks().delete(id);
                plugin.tracks().save();
                Text.send(s, Text.msg("Deleted " + id + "."));
            }
            default -> help(s);
        }
    }

    private void done(CommandSender s, String text) {
        plugin.tracks().save();
        Text.send(s, Text.msg(Text.MM.escapeTags(text)));
    }

    private void listTracks(CommandSender s) {
        if (plugin.tracks().all().isEmpty()) {
            Text.send(s, Text.msg("No tracks yet. <#FFD23F>/kart track generate main</#FFD23F> builds one in a world of its own."));
            return;
        }
        for (Track t : plugin.tracks().all()) {
            List<String> missing = t.missing();
            String state = !missing.isEmpty() ? "<#FF5C5C>needs " + Text.MM.escapeTags(String.join(", ", missing)) + "</#FF5C5C>"
                    : t.enabled() ? "<#5BE37D>open</#5BE37D>" : "<#9AA0A6>closed</#9AA0A6>";
            Text.send(s, Text.msg("<#FFD23F>" + t.id() + "</#FFD23F> <#9AA0A6>" + Text.MM.escapeTags(t.name()) + " - "
                    + t.grid().size() + " grid, " + t.checkpoints().size() + " gates, " + t.boxes().size() + " boxes -</#9AA0A6> " + state));
        }
    }

    /**
     * /kart track generate <id> [name]: in a new world of its own, the default,
     * since it replaces nothing. /kart track generate <id> here confirm [name]:
     * where you stand, facing the way you face, replacing what is there. From
     * the console, /kart track generate <id> at <world> <x> <y> <z> <yaw> [name]
     * builds in place too. Every one can be taken back with /kart track undo.
     */
    private void generate(CommandSender s, String id, String[] a) {
        Location where;
        String name;
        boolean ownWorld = false;
        if (a.length >= 6 && a[0].equalsIgnoreCase("at")) {
            World w = TrackBuilder.world(a[1]);
            if (w == null) {
                Text.send(s, Text.error("No world called " + Text.MM.escapeTags(a[1]) + " is loaded."));
                return;
            }
            where = new Location(w, parse(a[2], 0), parse(a[3], 64), parse(a[4], 0), (float) parse(a[5], 0), 0);
            name = joined(a, 6);
        } else if (a.length >= 1 && a[0].equalsIgnoreCase("here")) {
            Player p = player(s);
            if (p == null) return;
            if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) {
                int[] size = TrackBuilder.footprint();
                Text.send(s, Text.msg("This builds the circuit, " + size[2] + " blocks a lap, starting under your feet and running "
                        + "forward and to your right. It replaces every block in a box about " + size[0] + " by " + size[1]
                        + ", from two below your feet to fourteen above (<#FFD23F>/kart track undo " + id
                        + "</#FFD23F> puts them back, though not what chests and signs held). Run <#FFD23F>/kart track generate "
                        + id + " here confirm [name]</#FFD23F> to build it. Without <#FFD23F>here</#FFD23F> it gets a world of its own."));
                return;
            }
            where = p.getLocation();
            name = joined(a, 2);
        } else {
            // A world of its own. "confirm" is still taken, from before there was a choice.
            int from = a.length >= 1 && a[0].equalsIgnoreCase("confirm") ? 1 : 0;
            name = joined(a, from);
            String worldName = freeWorldName(id);
            Text.send(s, Text.msg("Making a new world for it, <#FFD23F>" + worldName + "</#FFD23F>..."));
            World w = TrackBuilder.ownWorld(worldName);
            if (w == null) {
                Text.send(s, Text.error("The server would not make the world " + worldName + "."));
                return;
            }
            where = TrackBuilder.ownWorldOrigin(w);
            ownWorld = true;
        }
        Track existing = plugin.tracks().get(id);
        if (existing != null) plugin.races().shut(existing);
        YamlConfiguration before = TrackUndo.snapshot(existing);
        Track t = existing != null ? existing : plugin.tracks().create(id);
        if (name != null) t.name(name);
        t.enabled(false);
        t.ownWorld(ownWorld);
        BlockUndo undo = ownWorld ? null : new BlockUndo(where.getWorld().getName());
        boolean own = ownWorld;
        Text.send(s, Text.msg("Building " + id + "..."));
        TrackBuilder.build(plugin, t, where, undo,
                percent -> Text.send(s, Text.msg("<#9AA0A6>" + percent + "%</#9AA0A6>")),
                () -> {
                    plugin.tracks().save();
                    plugin.undo().remember(id, own, where.getWorld().getName(), before, undo);
                    Text.send(s, Text.msg("<#5BE37D>" + id + " is built and open.</#5BE37D> <#FFD23F>/kart join " + id
                            + "</#FFD23F>, then <#FFD23F>/kart start</#FFD23F>. <#9AA0A6>Changed your mind? /kart track undo "
                            + id + "</#9AA0A6>"));
                    if (own && s instanceof Player p && p.isOnline() && plugin.races().of(p) == null) p.teleport(t.lobby());
                });
    }

    /**
     * gokarts_<id>, or gokarts_<id>_2 and up when that is taken: generating a
     * track again leaves its old world alone, so undoing goes back to it.
     */
    private static String freeWorldName(String id) {
        String base = TrackBuilder.worldName(id);
        String name = base;
        for (int n = 2; TrackBuilder.worldFolder(name) != null; n++) {
            name = base + "_" + n;
        }
        return name;
    }

    private static String joined(String[] a, int from) {
        return a.length > from ? String.join(" ", Arrays.copyOfRange(a, from, a.length)) : null;
    }

    /**
     * /kart track undo <id> confirm: takes back the last generate. A track in a
     * world of its own takes the world with it; one built in place puts back
     * every block it replaced. The track is then what it was before, or gone.
     */
    private void undo(CommandSender s, String id, String[] a) {
        TrackUndo.Entry e = plugin.undo().get(id);
        if (e == null) {
            Text.send(s, Text.error("Nothing to undo for " + id + ": only the last /kart track generate can be taken back."));
            return;
        }
        String after = e.previous() == null ? id + " is forgotten" : id + " goes back to what it was before";
        if (a.length < 1 || !a[0].equalsIgnoreCase("confirm")) {
            String what = e.ownWorld()
                    ? "deletes the world <#FFD23F>" + Text.MM.escapeTags(e.world()) + "</#FFD23F> and everything in it"
                    : "puts back the " + e.blocks() + " blocks it replaced in <#FFD23F>" + Text.MM.escapeTags(e.world()) + "</#FFD23F>";
            Text.send(s, Text.msg("Undoing " + id + " " + what + ", and " + after + ". Run <#FFD23F>/kart track undo " + id
                    + " confirm</#FFD23F> to do it."));
            return;
        }
        Track t = plugin.tracks().get(id);
        if (t != null) plugin.races().shut(t);
        Runnable finish = () -> {
            if (e.previous() != null) plugin.tracks().put(e.previous());
            else plugin.tracks().delete(id);
            plugin.tracks().save();
            plugin.undo().forget(id);
            Text.send(s, Text.msg("<#5BE37D>Undone.</#5BE37D> " + Text.MM.escapeTags(after) + "."));
        };
        if (e.ownWorld()) {
            if (deleteOwnWorld(s, e.world())) finish.run();
            return;
        }
        BlockUndo blocks;
        try {
            blocks = plugin.undo().loadBlocks(id);
        } catch (java.io.IOException err) {
            Text.send(s, Text.error("Could not read the undo for " + id + ": " + Text.MM.escapeTags(String.valueOf(err.getMessage()))));
            return;
        }
        Text.send(s, Text.msg("Putting back " + blocks.size() + " blocks..."));
        blocks.restore(plugin, percent -> Text.send(s, Text.msg("<#9AA0A6>" + percent + "%</#9AA0A6>")), restored -> finish.run());
    }

    /**
     * Unloads and deletes a world this plugin made for a track. Refuses any
     * other name, so an undo can never take a world somebody built.
     */
    private boolean deleteOwnWorld(CommandSender s, String name) {
        if (name == null || !name.startsWith("gokarts_")) {
            Text.send(s, Text.error(Text.MM.escapeTags(String.valueOf(name)) + " was not made by Go Karts; not deleting it."));
            return false;
        }
        java.io.File folder = TrackBuilder.worldFolder(name);
        World w = Bukkit.getWorld(name);
        if (w != null) {
            World home = Bukkit.getWorlds().get(0);
            for (Player p : w.getPlayers()) p.teleport(home.getSpawnLocation());
            if (!Bukkit.unloadWorld(w, false)) {
                Text.send(s, Text.error("The server would not unload " + name + ". Is it the main world, or still in use?"));
                return false;
            }
        }
        // Only a folder named for the world: never the main world's, whatever the layout says.
        if (folder == null || !folder.getName().equals(name)) return true;
        try {
            if (folder.exists()) {
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(folder.toPath())) {
                    for (java.nio.file.Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        java.nio.file.Files.delete(p);
                    }
                }
            }
        } catch (java.io.IOException err) {
            Text.send(s, Text.error("Unloaded " + name + " but could not delete its folder: "
                    + Text.MM.escapeTags(String.valueOf(err.getMessage()))));
        }
        return true;
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void help(CommandSender s) {
        List<String> lines = new ArrayList<>();
        lines.add("<#FFD23F>/kart join</#FFD23F> [track] <#9AA0A6>- into the lobby</#9AA0A6>");
        lines.add("<#FFD23F>/kart leave</#FFD23F> <#9AA0A6>- out, with your things back</#9AA0A6>");
        if (s.hasPermission(Perms.COLOUR)) lines.add("<#FFD23F>/kart colour</#FFD23F> <colour> <#9AA0A6>- the kart you'd like</#9AA0A6>");
        if (s.hasPermission(Perms.RESULTS)) lines.add("<#FFD23F>/kart results</#FFD23F> <#9AA0A6>- the last race's results</#9AA0A6>");
        if (s.hasPermission(Perms.START)) lines.add("<#FFD23F>/kart start</#FFD23F> [track] <#9AA0A6>- start now, alone is fine</#9AA0A6>");
        if (s.hasPermission(Perms.STOP)) lines.add("<#FFD23F>/kart stop</#FFD23F> [track]");
        if (s.hasPermission(Perms.GIVE)) lines.add("<#FFD23F>/kart give</#FFD23F> [colour] [player] <#9AA0A6>- a kart to drive anywhere</#9AA0A6>");
        if (s.hasPermission(Perms.TRACK_GENERATE)) lines.add("<#FFD23F>/kart track generate</#FFD23F> <id> [here confirm] [name] <#9AA0A6>- build a circuit, in a new world unless here</#9AA0A6>");
        if (s.hasPermission(Perms.TRACK_UNDO)) lines.add("<#FFD23F>/kart track undo</#FFD23F> <id> confirm <#9AA0A6>- take the last generate back</#9AA0A6>");
        if (s.hasPermission(Perms.TRACK)) lines.add("<#FFD23F>/kart track</#FFD23F> list|create|setlobby|addgrid|addcheckpoint|addbox|laps|enable|disable|tp|name");
        if (s.hasPermission(Perms.RELOAD)) lines.add("<#FFD23F>/kart reload</#FFD23F>");
        if (s.hasPermission(Perms.AS)) lines.add("<#FFD23F>/kart as</#FFD23F> <player> <subcommand...>");
        for (String line : lines) Text.send(s, Text.msg(line));
    }

    // ---- tab completion ----------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender s, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("join", "leave"));
            if (s.hasPermission(Perms.COLOUR)) out.add("colour");
            if (s.hasPermission(Perms.RESULTS)) out.add("results");
            if (s.hasPermission(Perms.START)) out.add("start");
            if (s.hasPermission(Perms.STOP)) out.add("stop");
            if (s.hasPermission(Perms.GIVE)) out.add("give");
            if (s.hasPermission(Perms.RELOAD)) out.add("reload");
            if (s.hasPermission(Perms.AS)) out.add("as");
            if (s.hasPermission(Perms.TRACK) || s.hasPermission(Perms.TRACK_GENERATE)) out.add("track");
        } else if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "join", "start", "stop" -> plugin.tracks().all().forEach(t -> out.add(t.id()));
                case "colour", "color", "give" -> Arrays.stream(KartColour.values()).forEach(c -> out.add(c.id()));
                case "track" -> {
                    for (String sub : TRACK_SUBS) {
                        String node = sub.equals("generate") ? Perms.TRACK_GENERATE : sub.equals("undo") ? Perms.TRACK_UNDO
                                : sub.equals("delete") ? Perms.TRACK_DELETE : Perms.TRACK;
                        if (s.hasPermission(node)) out.add(sub);
                    }
                }
                case "as" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                default -> {
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("track") && !args[1].equalsIgnoreCase("list")) {
            plugin.tracks().all().forEach(t -> out.add(t.id()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give") && s.hasPermission(Perms.GIVE_OTHERS)) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (args.length == 4 && args[0].equalsIgnoreCase("track")
                && (args[1].equalsIgnoreCase("undo") || args[1].equalsIgnoreCase("delete"))) {
            out.add("confirm");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("track") && args[1].equalsIgnoreCase("generate")) {
            out.add("here");
            out.add("at");
        } else if (args.length == 5 && args[0].equalsIgnoreCase("track") && args[1].equalsIgnoreCase("generate")
                && args[3].equalsIgnoreCase("here")) {
            out.add("confirm");
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
