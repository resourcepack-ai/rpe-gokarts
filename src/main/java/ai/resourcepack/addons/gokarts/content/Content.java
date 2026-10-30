package ai.resourcepack.addons.gokarts.content;

import ai.resourcepack.engine.RPEnginePlugin;
import ai.resourcepack.engine.api.ContentId;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The gokarts content folder, as the engine holds it: items, icon glyphs and
 * sounds by id. Each lookup has a vanilla fallback, so a pack that failed to
 * build costs looks, never a broken race.
 */
public final class Content {
    public static final String NS = "gokarts";

    private final RPEnginePlugin engine;

    public Content(RPEnginePlugin engine) {
        this.engine = engine;
    }

    public RPEnginePlugin engine() {
        return engine;
    }

    public static ContentId id(String path) {
        return ContentId.parse(NS + ":" + path).orElseThrow();
    }

    private final java.util.Set<String> missing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A fresh stack of one of our items, or paper if the engine does not have
     * it, which is said once in the log: a race handed plain paper is
     * otherwise a mystery.
     */
    public ItemStack item(String path) {
        return engine.items().create(id(path)).orElseGet(() -> {
            if (missing.add(path)) {
                org.bukkit.Bukkit.getLogger().warning("[RPEGoKarts] RP Engine has no item " + NS + ":" + path
                        + ". Is the gokarts content installed (plugins/RPEngine/content/gokarts) and loaded (/rp reload)?");
            }
            return new ItemStack(Material.PAPER);
        });
    }

    public boolean is(ItemStack stack, String path) {
        return stack != null && engine.items().is(stack, id(path));
    }

    /** The id of one of our items, without the namespace, or null. */
    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return null;
        return engine.items().idOf(stack)
                .filter(id -> id.namespace().equals(NS))
                .map(ContentId::path)
                .orElse(null);
    }

    /** An icon's character, or the fallback when the pack has none. */
    public String icon(String path, String fallback) {
        return engine.icons().character(id(path)).orElse(fallback);
    }

    /**
     * One of the content folder's VFX (Studio's recipes, vfx/*.json), at a
     * place and facing its yaw, following {@code follow} when it is a player in
     * a kart. Nothing at all where the server cannot play VFX (below 1.21.4).
     */
    public void vfx(String path, Location at, org.bukkit.entity.Player follow) {
        try {
            if (follow != null) {
                engine.vfx().play(id(path), at, follow, ai.resourcepack.engine.api.VfxOptions.builder().follow(follow).build());
            } else {
                engine.vfx().play(id(path), at);
            }
        } catch (RuntimeException e) {
            if (missing.add("vfx:" + path)) {
                org.bukkit.Bukkit.getLogger().warning("[RPEGoKarts] Could not play the VFX " + NS + ":" + path + ": " + e.getMessage());
            }
        }
    }

    /**
     * Plays an effect on a kart: it rides along with the kart and turns with
     * it. It follows the chassis, the entity that holds the kart's heading,
     * rather than the driver, whose yaw is wherever they are looking: an
     * exhaust that follows the camera blows out of the side of the kart.
     */
    public void vfx(String path, ai.resourcepack.engine.api.Vehicle kart, org.bukkit.entity.Player driver) {
        org.bukkit.entity.Entity chassis = kart == null ? null : kart.chassis();
        if (chassis == null || !chassis.isValid()) {
            if (kart != null) vfx(path, kart.location(), driver);
            return;
        }
        try {
            engine.vfx().play(id(path), kart.location(), driver,
                    ai.resourcepack.engine.api.VfxOptions.builder().follow(chassis).build());
        } catch (RuntimeException e) {
            if (missing.add("vfx:" + path)) {
                org.bukkit.Bukkit.getLogger().warning("[RPEGoKarts] Could not play the VFX " + NS + ":" + path + ": " + e.getMessage());
            }
        }
    }

    public void sound(Location at, String path, float volume, float pitch, Sound fallback) {
        if (!engine.sounds().playAt(at, id(path), volume, pitch) && fallback != null) {
            at.getWorld().playSound(at, fallback, SoundCategory.PLAYERS, volume, pitch);
        }
    }

    public void sound(Player to, String path, float volume, float pitch, Sound fallback) {
        if (!engine.sounds().play(to, id(path), volume, pitch) && fallback != null) {
            to.playSound(to.getLocation(), fallback, SoundCategory.PLAYERS, volume, pitch);
        }
    }
}
