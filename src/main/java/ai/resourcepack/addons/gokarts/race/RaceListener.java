package ai.resourcepack.addons.gokarts.race;

import ai.resourcepack.addons.gokarts.GoKartsPlugin;
import ai.resourcepack.addons.gokarts.command.Perms;
import ai.resourcepack.addons.gokarts.text.Text;
import ai.resourcepack.engine.api.Vehicle;
import ai.resourcepack.engine.api.event.VehicleEnterEvent;
import ai.resourcepack.engine.api.event.VehicleImpactEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/**
 * Keeps a race a race: nobody in one is hurt, goes hungry, breaks anything or
 * shuffles their hotbar, and the keys that mean something in a kart mean it.
 *
 * <ul>
 *   <li><b>F</b>, a left click or a right click use your item.</li>
 *   <li><b>Q</b> puts you back on the track at the last gate.</li>
 *   <li>Nobody can climb into somebody else's race kart.</li>
 *   <li>Two karts that touch make a noise about it, raced or not.</li>
 * </ul>
 */
public final class RaceListener implements Listener {
    private final GoKartsPlugin plugin;

    public RaceListener(GoKartsPlugin plugin) {
        this.plugin = plugin;
    }

    private Race race(Player p) {
        return plugin.races().of(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.races().leave(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Back from a crash or a restart mid-race: their things, and where they were.
        Player p = e.getPlayer();
        if (plugin.snapshots().has(p.getUniqueId())) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && race(p) == null) plugin.snapshots().restore(p);
            }, 5L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && race(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && race(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (race(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (race(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && race(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && race(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player p && race(p) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Race r = race(e.getPlayer());
        if (r != null && r.racer(e.getPlayer()) != null && r.racer(e.getPlayer()).racing) e.setCancelled(true);
    }

    /** Q: back on the track. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDrop(PlayerDropItemEvent e) {
        Race race = race(e.getPlayer());
        if (race == null) return;
        e.setCancelled(true);
        Racer r = race.racer(e.getPlayer());
        if (r != null && r.racing) race.askRespawn(r);
    }

    /** F: use the item. */
    @EventHandler(priority = EventPriority.LOW)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Race race = race(e.getPlayer());
        if (race == null) return;
        e.setCancelled(true);
        race.useItem(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Race race = race(e.getPlayer());
        if (race == null) return;
        if (e.getHand() == EquipmentSlot.OFF_HAND) return;
        Action a = e.getAction();
        if (a == Action.PHYSICAL) return;
        e.setCancelled(true);
        race.useItem(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Race race = race(e.getPlayer());
        if (race == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) race.useItem(e.getPlayer());
    }

    /** A left click in the air arrives only as an arm swing. */
    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(PlayerAnimationEvent e) {
        Race race = race(e.getPlayer());
        if (race != null) race.useItem(e.getPlayer());
    }

    /** Nobody gets into a race kart but its racer, and a racer gets into nothing else. */
    @EventHandler(ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent e) {
        Player p = e.getPlayer();
        Race race = race(p);
        Vehicle v = e.vehicle();
        if (race != null) {
            Racer r = race.racer(p);
            if (r == null || r.kart == null || !r.kart.uniqueId().equals(v.uniqueId())) e.setCancelled(true);
            return;
        }
        if (plugin.races().isRaceKart(v.uniqueId())) e.setCancelled(true);
    }

    /** Two karts touching: a rubbery bonk and a puff where they met. */
    @EventHandler
    public void onImpact(VehicleImpactEvent e) {
        if (e.cause() != VehicleImpactEvent.Cause.VEHICLE || e.second() == null) return;
        if (!Driving.isKart(e.first().vehicle()) && !Driving.isKart(e.second().vehicle())) return;
        double closing = e.first().closingSpeed();
        if (closing < 2) return;
        Location at = e.point();
        if (at == null) at = e.first().vehicle().location();
        float volume = (float) Math.min(1.0, 0.4 + closing / 12);
        plugin.content().sound(at, "bump", volume, 1f, Sound.ENTITY_SLIME_SQUISH);
        at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 0.5, 0), 8, 0.2, 0.2, 0.2, 0.2);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (race(p) == null || p.hasPermission(Perms.BYPASS_COMMANDS)) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (label.equals("kart") || label.equals("gokart") || label.equals("karts") || label.equals("gk")) return;
        if (plugin.settings().allowedCommands.contains(label)) return;
        e.setCancelled(true);
        Text.send(p, plugin.messages().component("command-blocked"));
    }
}
