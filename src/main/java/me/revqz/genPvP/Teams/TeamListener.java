package me.revqz.genPvP.Teams;

import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import io.papermc.paper.event.player.AsyncChatEvent;

public class TeamListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final TeamManager teamManager;
    private final TeamCommand teamCommand;

    public TeamListener(TeamManager teamManager, TeamCommand teamCommand) {
        this.teamManager = teamManager;
        this.teamCommand = teamCommand;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        java.util.UUID uuid = player.getUniqueId();

        if (teamManager.hasPendingSearch(uuid)) {
            event.setCancelled(true);
            String searchName = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            String teamName   = teamManager.getPendingSearch(uuid);
            teamManager.clearPendingSearch(uuid);

            Team team = teamManager.getTeamByName(teamName);
            if (team == null) {
                player.sendMessage(LEGACY.deserialize(ColorUtil.colorize("&cTeam no longer exists.")));
                return;
            }

            TeamMember found = null;
            for (TeamMember m : team.getMemberList()) {
                if (m.getName().equalsIgnoreCase(searchName)) {
                    found = m;
                    break;
                }
            }

            if (found != null) {
                boolean online = Bukkit.getPlayer(found.getUuid()) != null;
                String status = online
                        ? ColorUtil.colorize("&#7AFB00Online")
                        : ColorUtil.colorize("&#FC0000Offline");
                player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                        "&#00A4FB" + found.getName() + " &#A7A7A7is in team &#00A4FB" + teamName +
                        " &7(" + status + "&7) &7Role: &#00A4FB" + found.getRole().name())));
            } else {
                player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                        "&#FC0000" + searchName + " &#A7A7A7was not found in team &#00A4FB" + teamName)));
            }

            Bukkit.getScheduler().runTask(teamManager.getPlugin(), () -> {
                Team t = teamManager.getTeamByName(teamName);
                if (t != null) player.openInventory(TeamGUI.buildTeamInfo(t));
            });
            return;
        }

        if (!teamManager.isTeamChatEnabled(uuid)) return;

        Team team = teamManager.getTeam(uuid);
        if (team == null) {
            teamManager.toggleTeamChat(uuid);
            player.sendMessage(LEGACY.deserialize(ColorUtil.colorize("&cYou do not have a team.")));
            player.sendActionBar(LEGACY.deserialize(ColorUtil.colorize("&cYou do not have a team.")));
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        teamCommand.broadcastTeamChat(team, player.getName(), message);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = null;

        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }

        if (attacker == null) return;
        if (attacker.equals(victim)) return;

        if (teamManager.shouldBlockDamage(attacker.getUniqueId(), victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        teamManager.onPlayerDeath(victim.getUniqueId());

        if (killer != null) {
            teamManager.onPlayerKill(killer.getUniqueId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH)
    public void onTeamGUIDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (title.contains(TeamGUI.TEAM_GUI_TITLE_CHECK) || title.contains("ᴅɪꜱʙᴀɴᴅɪɴɢ")) {
            event.setCancelled(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onTeamGUIClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.contains(TeamGUI.TEAM_GUI_TITLE_CHECK)) return;
        if (title.contains("ᴅɪꜱʙᴀɴᴅɪɴɢ")) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        if (slot < 0 || slot > 35) return;

        switch (slot) {
            case 27 -> {
                
                Team team = teamManager.getTeam(player.getUniqueId());
                if (team != null) {
                    teamManager.setPendingSearch(player.getUniqueId(), team.getName());
                    player.closeInventory();
                    player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                            "&#00A4FB✎ &#A7A7A7Type a player name in chat to search:")));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                }
            }
            case 28 -> {
                
                Team team = teamManager.getTeam(player.getUniqueId());
                if (team == null) {
                    
                    for (Team t : getAllTeamsByBruteSearch(title)) {
                        player.openInventory(TeamGUI.buildTeamInfo(t, true));
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                        return;
                    }
                } else {
                    player.openInventory(TeamGUI.buildTeamInfo(team, true));
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                }
            }
            case 31 -> {
                
                player.performCommand("team info");
            }
            case 35 -> {
                
                Team team = teamManager.getTeam(player.getUniqueId());
                if (team == null) return;
                if (!team.isOwner(player.getUniqueId())) {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    player.closeInventory();
                    player.sendMessage(LEGACY.deserialize(ColorUtil.colorize("&cOnly the team owner can toggle PvP.")));
                    return;
                }
                boolean newState = teamManager.togglePvp(team.getName());
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                
                player.openInventory(TeamGUI.buildTeamInfo(team));
                String stateMsg = newState
                        ? ColorUtil.colorize("&#7AFB00Team PvP enabled.")
                        : ColorUtil.colorize("&#FC0000Team PvP disabled.");
                player.sendMessage(LEGACY.deserialize(stateMsg));
                player.sendActionBar(LEGACY.deserialize(stateMsg));
            }
            default -> {
                if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.PLAYER_HEAD) {
                    player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
                    player.closeInventory();
                    player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                            "&cThis feature is currently unavailable and will be added at the next update.")));
                    player.sendActionBar(LEGACY.deserialize(ColorUtil.colorize(
                            "&cThis feature is currently unavailable and will be added at the next update.")));
                }
                if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.GRAY_STAINED_GLASS_PANE) {
                    player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
                    player.closeInventory();
                    player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(
                            "&#A7A7A7Type /team invite (name) to invite a player")));
                    player.sendActionBar(LEGACY.deserialize(ColorUtil.colorize(
                            "&#A7A7A7Type /team invite (name) to invite a player")));
                }
            }
        }
    }

    private java.util.List<Team> getAllTeamsByBruteSearch(String title) {
        java.util.List<Team> results = new java.util.ArrayList<>();
        for (String name : teamManager.getAllTeamNames()) {
            String smallCaps = TeamGUI.toSmallCaps(name);
            if (title.contains(smallCaps)) {
                Team t = teamManager.getTeamByName(name);
                if (t != null) results.add(t);
            }
        }
        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    
    // ═══════════════════════════════════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onDisbandGUIClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (!title.contains("ᴅɪꜱʙᴀɴᴅɪɴɢ")) return;

        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();

        if (slot == 15) {
            player.performCommand("team disband confirm");
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
            player.closeInventory();
        } else if (slot == 11) {
            player.playSound(player.getLocation(), Sound.BLOCK_DISPENSER_FAIL, 1f, 1f);
            player.closeInventory();
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onDisbandGUIClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        if (!title.contains("ᴅɪꜱʙᴀɴᴅɪɴɢ")) return;
        Player player = (Player) event.getPlayer();
        teamManager.clearPendingDisband(player.getUniqueId());
    }
}
