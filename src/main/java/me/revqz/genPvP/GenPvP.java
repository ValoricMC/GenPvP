package me.revqz.genPvP;

import me.revqz.genPvP.Protect.BlockTimerManager;
import me.revqz.genPvP.Protect.ProtectCommand;
import me.revqz.genPvP.Protect.ProtectListener;
import me.revqz.genPvP.Protect.RegionManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class GenPvP extends JavaPlugin {

    private BlockTimerManager blockTimerManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegionManager regionManager = new RegionManager(this);
        blockTimerManager = new BlockTimerManager(this);
        ProtectCommand protectCommand = new ProtectCommand(regionManager);

        var regionCmd = getCommand("region");
        if (regionCmd != null) regionCmd.setExecutor(protectCommand);

        getServer().getPluginManager().registerEvents(
                new ProtectListener(regionManager, blockTimerManager, protectCommand), this
        );
    }

    @Override
    public void onDisable() {
        if (blockTimerManager != null) {
            blockTimerManager.cancelAll();
        }
    }
}
