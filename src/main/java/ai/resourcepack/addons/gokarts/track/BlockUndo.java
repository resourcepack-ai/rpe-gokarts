package ai.resourcepack.addons.gokarts.track;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * What a track generated in place replaced: every block as it was before the
 * builder first touched it, so {@code /kart track undo} can put the land back.
 *
 * <p>Block DATA only (the type and its states, a stair's facing, a slab's
 * half). What a block entity held - a chest's items, a sign's words - is not
 * kept: the builder warns before it builds in place, and a generated world
 * (the default) has nothing to lose.
 *
 * <p>Kept on disk as gzipped text, a palette of block data strings and then
 * one line per block, because it has to survive a restart: a track is often
 * undone the day after somebody looked at it.
 */
public final class BlockUndo {
    private final String world;
    /** Packed position -> the block data string it had. The first write wins: that is what was there. */
    private final Map<Long, String> before = new LinkedHashMap<>();

    public BlockUndo(String world) {
        this.world = world;
    }

    public String world() {
        return world;
    }

    public int size() {
        return before.size();
    }

    /** Remembers a block as it is now, unless it was already remembered. */
    void record(Block b) {
        remember(b.getX(), b.getY(), b.getZ(), b.getBlockData().getAsString());
    }

    void remember(int x, int y, int z, String data) {
        before.putIfAbsent(pack(x, y, z), data);
    }

    /** What was at a position, or null when the build never touched it. */
    String at(int x, int y, int z) {
        return before.get(pack(x, y, z));
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    static int unpackX(long p) {
        return (int) (p >> 38);
    }

    static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }

    // ---- disk ----------------------------------------------------------------------

    public void save(File file) throws IOException {
        file.getParentFile().mkdirs();
        Map<String, Integer> palette = new LinkedHashMap<>();
        for (String data : before.values()) palette.putIfAbsent(data, palette.size());
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(new FileOutputStream(file)), StandardCharsets.UTF_8))) {
            out.write("world " + world + "\n");
            out.write("palette " + palette.size() + "\n");
            for (String data : palette.keySet()) out.write(data + "\n");
            out.write("blocks " + before.size() + "\n");
            for (Map.Entry<Long, String> e : before.entrySet()) {
                long p = e.getKey();
                out.write(unpackX(p) + " " + unpackY(p) + " " + unpackZ(p) + " " + palette.get(e.getValue()) + "\n");
            }
        }
    }

    public static BlockUndo load(File file) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8))) {
            String world = field(in.readLine(), "world");
            BlockUndo undo = new BlockUndo(world);
            int n = Integer.parseInt(field(in.readLine(), "palette"));
            List<String> palette = new ArrayList<>(n);
            for (int i = 0; i < n; i++) palette.add(in.readLine());
            int blocks = Integer.parseInt(field(in.readLine(), "blocks"));
            for (int i = 0; i < blocks; i++) {
                String[] parts = in.readLine().split(" ");
                undo.before.put(pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])),
                        palette.get(Integer.parseInt(parts[3])));
            }
            return undo;
        }
    }

    private static String field(String line, String name) throws IOException {
        if (line == null || !line.startsWith(name + " ")) throw new IOException("expected " + name + ", found " + line);
        return line.substring(name.length() + 1);
    }

    // ---- putting it back -----------------------------------------------------------

    /**
     * Puts every block back, a slice per tick, then runs {@code done} with how
     * many it restored. A block data string this server cannot read (a block
     * from a newer version, say) is left as the track had it, and counted out.
     */
    public void restore(Plugin plugin, Consumer<Integer> progress, Consumer<Integer> done) {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            done.accept(0);
            return;
        }
        List<Map.Entry<Long, String>> all = new ArrayList<>(before.entrySet());
        Map<String, BlockData> parsed = new HashMap<>();
        new BukkitRunnable() {
            int i = 0, restored = 0, lastTenth = -1;

            @Override
            public void run() {
                for (int n = 0; n < 4000 && i < all.size(); n++, i++) {
                    Map.Entry<Long, String> e = all.get(i);
                    BlockData data = parsed.computeIfAbsent(e.getValue(), s -> {
                        try {
                            return Bukkit.createBlockData(s);
                        } catch (IllegalArgumentException bad) {
                            return null;
                        }
                    });
                    if (data == null) continue;
                    long p = e.getKey();
                    w.getBlockAt(unpackX(p), unpackY(p), unpackZ(p)).setBlockData(data, false);
                    restored++;
                }
                int tenth = all.isEmpty() ? 10 : i * 10 / all.size();
                if (tenth != lastTenth && tenth < 10) {
                    lastTenth = tenth;
                    progress.accept(tenth * 10);
                }
                if (i >= all.size()) {
                    cancel();
                    done.accept(restored);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
