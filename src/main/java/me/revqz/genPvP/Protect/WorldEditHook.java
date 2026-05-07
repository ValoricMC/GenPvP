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

public class WorldEditHook {

    private final GenPvP plugin;
    private final Set<Location> creativePlacedBlocks;

    public WorldEditHook(GenPvP plugin, Set<Location> creativePlacedBlocks) {
        this.plugin              = plugin;
        this.creativePlacedBlocks = creativePlacedBlocks;

        WorldEdit.getInstance().getEventBus().register(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        
        event.setExtent(new SchematicTrackerExtent(event.getExtent(), event));
    }

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
                    
                    creativePlacedBlocks.add(new Location(bukkitWorld,
                            location.x(), location.y(), location.z()));
                }
            }

            return result;
        }
    }
}
