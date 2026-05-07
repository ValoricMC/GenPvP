package me.revqz.genPvP.AutoPickup;

import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;

public class AutoPickupListener implements Listener {

    private final RegionManager regionManager;
    private final AutoSmeltManager autoSmeltManager;

    public AutoPickupListener(RegionManager regionManager, AutoSmeltManager autoSmeltManager) {
        this.regionManager    = regionManager;
        this.autoSmeltManager = autoSmeltManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (!isAutoPickupRegion(loc)) return;

        Player player = event.getPlayer();

        ItemStack tool = player.getInventory().getItemInMainHand();
        Collection<ItemStack> drops = event.getBlock().getDrops(tool);
        if (drops.isEmpty()) return;

        event.setDropItems(false);

        boolean smelt = autoSmeltManager.isEnabled(player.getUniqueId());

        for (ItemStack drop : drops) {
            ItemStack item = smelt ? autoSmeltManager.smelt(drop) : drop;
            
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack leftover : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private boolean isAutoPickupRegion(Location loc) {
        for (ProtectRegion region : regionManager.getRegionsAt(loc)) {
            RegionType t = region.getType();
            if (t == RegionType.SPAWN || t == RegionType.GENS || t == RegionType.OPMINESGENS || t == RegionType.OPMINES) {
                return true;
            }
        }
        return false;
    }
}
