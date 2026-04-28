package me.revqz.genPvP.Protect;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import me.revqz.genPvP.GenPvP;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Set;

/**
 * Hooks into the WorldEdit / FastAsyncWorldEdit extent pipeline.
 *
 * Every block set operation that passes through a WorldEdit EditSession
 * (paste, schematic load, //set, etc.) is intercepted here. The location
 * is stamped into {@code creativePlacedBlocks} so {@link ProtectListener}
 * treats those blocks the same as blocks placed manually in creative mode:
 * they can only be broken in regions that explicitly allow ALLOW_BREAK.
 */
public class WorldEditHook {

    private final GenPvP plugin;
    private final Set<Location> creativePlacedBlocks;

    public WorldEditHook(GenPvP plugin, Set<Location> creativePlacedBlocks) {
        this.plugin              = plugin;
        this.creativePlacedBlocks = creativePlacedBlocks;

        // Register with WorldEdit's event bus — this receives ALL edit sessions,
        // including those triggered by FAWE (which wraps the same bus).
        WorldEdit.getInstance().getEventBus().register(this);
    }

    /**
     * Called by WorldEdit before every edit session.
     * We inject a tracking extent into the chain that records every block-set location.
     */
    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        // WorldEdit fires this event once per stage (BEFORE_HISTORY, BEFORE_REORDER, BEFORE_CHANGE).
        // We inject at every stage; the Set backing creativePlacedBlocks deduplicates automatically,
        // so adding the same Location multiple times is harmless.
        event.setExtent(new SchematicTrackerExtent(event.getExtent(), event));
    }

    // ── Inner extent ──────────────────────────────────────────────────────────

    /**
     * Delegates all operations to the underlying extent but records the world
     * position of every block that is set.
     */
    private class SchematicTrackerExtent extends AbstractDelegateExtent {

        private final EditSessionEvent sessionEvent;

        SchematicTrackerExtent(com.sk89q.worldedit.extent.Extent delegate, EditSessionEvent event) {
            super(delegate);
            this.sessionEvent = event;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block)
                throws com.sk89q.worldedit.WorldEditException {

            boolean result = super.setBlock(location, block);

            if (result && sessionEvent.getWorld() != null) {
                String worldName = sessionEvent.getWorld().getName();
                World bukkitWorld = plugin.getServer().getWorld(worldName);
                if (bukkitWorld != null) {
                    // Location.hashCode() / equals() use block coordinates — safe as map key
                    creativePlacedBlocks.add(new Location(bukkitWorld,
                            location.x(), location.y(), location.z()));
                }
            }

            return result;
        }
    }
}
