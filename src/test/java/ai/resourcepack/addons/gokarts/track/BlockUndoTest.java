package ai.resourcepack.addons.gokarts.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The undo file: every position comes back where it was, negative ones included, and the first block seen wins. */
class BlockUndoTest {
    @Test
    void positionsPackBothWays() {
        int[][] points = {{0, 0, 0}, {-1, -64, -1}, {29_999_999, 319, -29_999_999}, {-30_000_000, -2048, 12345}, {7, 2047, -8}};
        for (int[] p : points) {
            long k = BlockUndo.pack(p[0], p[1], p[2]);
            assertEquals(p[0], BlockUndo.unpackX(k));
            assertEquals(p[1], BlockUndo.unpackY(k));
            assertEquals(p[2], BlockUndo.unpackZ(k));
        }
    }

    @Test
    void savesAndLoads(@TempDir Path dir) throws Exception {
        BlockUndo u = new BlockUndo("world");
        u.remember(-5, -61, 12, "minecraft:grass_block[snowy=false]");
        u.remember(-5, -61, 12, "minecraft:gray_concrete");
        u.remember(3, 70, -40, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
        u.remember(4, 70, -40, "minecraft:grass_block[snowy=false]");
        File f = dir.resolve("t.blocks.gz").toFile();
        u.save(f);
        BlockUndo back = BlockUndo.load(f);
        assertEquals("world", back.world());
        assertEquals(3, back.size());
        assertEquals("minecraft:grass_block[snowy=false]", back.at(-5, -61, 12));
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]", back.at(3, 70, -40));
        assertNull(back.at(0, 0, 0));
    }
}
