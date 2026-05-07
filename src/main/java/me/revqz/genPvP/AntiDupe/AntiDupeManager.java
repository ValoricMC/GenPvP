package me.revqz.genPvP.AntiDupe;

import me.revqz.genPvP.Database.LogManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Webhook.WebhookSender;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiDupeManager {

    private final GenPvP      plugin;
    private final PDCManager  pdcManager;
    private final LogManager  logManager;
    private       AntiDupeConfig config;

    private final ConcurrentHashMap<String, UUID> itemRegistry = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Long> alertCooldown = new ConcurrentHashMap<>();

    private BukkitTask scanTask;

    public AntiDupeManager(GenPvP plugin, LogManager logManager) {
        this.plugin     = plugin;
        this.logManager = logManager;
        this.pdcManager = new PDCManager(plugin);
        this.config     = new AntiDupeConfig(plugin.getConfig());
        startScanTask();
    }

    public void reload() {
        plugin.reloadConfig();
        config = new AntiDupeConfig(plugin.getConfig());
        if (scanTask != null) scanTask.cancel();
        startScanTask();
    }

    private void startScanTask() {
        if (!config.enabled || !config.checkUnstackables) return;
        long interval = Math.max(1, config.scanIntervalTicks);
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::periodicScan, interval, interval);
    }

    public void stampInventory(Player player) {
        if (!config.enabled || !config.checkUnstackables) return;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (!isTrackable(item)) continue;
            String uid = pdcManager.getOrAssignUUID(item);
            if (uid == null) continue; 
            itemRegistry.putIfAbsent(uid, player.getUniqueId());
        }
    }

    public boolean checkItem(Player player, ItemStack item) {
        if (!config.enabled || !config.checkUnstackables) return false;
        if (!isTrackable(item)) return false;

        String uid = pdcManager.getOrAssignUUID(item);
        if (uid == null) return false; 
        UUID   registeredOwner = itemRegistry.get(uid);

        if (registeredOwner != null && !registeredOwner.equals(player.getUniqueId())) {
            Player owner = Bukkit.getPlayer(registeredOwner);
            
            if (owner != null && ownerStillHas(owner, uid)) {
                handleDupe(player, item, uid,
                        "picked up by §e" + player.getName()
                                + "§c while §e" + owner.getName() + "§c still holds the original");
                return true;
            }
        }

        itemRegistry.put(uid, player.getUniqueId());
        return false;
    }

    public void clearPlayer(UUID uuid) {
        alertCooldown.remove(uuid);
        itemRegistry.entrySet().removeIf(e -> e.getValue().equals(uuid));
    }

    public AntiDupeConfig getConfig() { return config; }
    public PDCManager     getPdcManager() { return pdcManager; }

    private void periodicScan() {
        if (!config.enabled || !config.checkUnstackables) return;

        Map<String, Player> seenThisTick = new HashMap<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getStorageContents()) {
                if (!isTrackable(item)) continue;

                String uid = pdcManager.getIfStamped(item);
                if (uid == null) {
                    
                    pdcManager.getOrAssignUUID(item);
                    continue;
                }

                Player first = seenThisTick.get(uid);
                if (first == null) {
                    seenThisTick.put(uid, player);
                    itemRegistry.put(uid, player.getUniqueId()); 
                } else if (!first.equals(player)) {
                    
                    handleDupe(player, item, uid,
                            "found simultaneously in §e" + first.getName()
                                    + "§c and §e" + player.getName());
                    if (config.deleteItem) removeFromInventory(player, uid);
                }
            }
        }
    }

    private void handleDupe(Player suspect, ItemStack item, String uid, String reason) {
        String logLine = "[AntiDupe] §cDupe detected §7| Player: §e" + suspect.getName()
                + " §7| Item: §e" + item.getType().name()
                + " §7| Reason: " + reason;

        if (config.logConsole) {
            plugin.getLogger().warning(ChatColor.stripColor(logLine));
        }

        logManager.logDupe(suspect.getUniqueId(),
                suspect.getName() + " | " + item.getType().name() + " | " + ChatColor.stripColor(reason));

        WebhookSender.sendDupeLog(
                suspect.getName(),
                suspect.getUniqueId().toString(),
                item.getType().name(),
                uid);

        long now  = System.currentTimeMillis();
        Long last = alertCooldown.get(suspect.getUniqueId());
        boolean onCooldown = last != null && (now - last) < config.alertCooldownSeconds * 1000L;

        if (config.notifyOps && !onCooldown) {
            alertCooldown.put(suspect.getUniqueId(), now);
            String opMsg = ChatColor.RED + "[AntiDupe] " + ChatColor.YELLOW + suspect.getName()
                    + ChatColor.GRAY + " has a duplicated " + ChatColor.AQUA + item.getType().name()
                    + ChatColor.GRAY + " — action taken.";
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.getOnlinePlayers().stream()
                            .filter(Player::isOp)
                            .forEach(op -> op.sendMessage(opMsg))
            );
        }

        if (config.deleteItem) {
            Bukkit.getScheduler().runTask(plugin, () -> removeFromInventory(suspect, uid));
        }

        if (config.punishCommand != null && !config.punishCommand.isBlank()) {
            String cmd = config.punishCommand.replace("%player%", suspect.getName());
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            );
        }
    }

    private boolean isTrackable(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getType().getMaxStackSize() == 1;
    }

    private boolean ownerStillHas(Player owner, String uid) {
        for (ItemStack stack : owner.getInventory().getStorageContents()) {
            if (!isTrackable(stack)) continue;
            if (uid.equals(pdcManager.getIfStamped(stack))) return true;
        }
        return false;
    }

    private void removeFromInventory(Player player, String uid) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            if (!isTrackable(contents[i])) continue;
            if (uid.equals(pdcManager.getIfStamped(contents[i]))) {
                contents[i] = null;
                player.getInventory().setStorageContents(contents);
                return;
            }
        }
    }
}
