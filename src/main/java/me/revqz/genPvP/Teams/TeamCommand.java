package me.revqz.genPvP.Teams;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

public class TeamCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final GenPvP plugin;
    private final TeamManager teamManager;

    public TeamCommand(GenPvP plugin, TeamManager teamManager) {
        this.plugin      = plugin;
        this.teamManager = teamManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (args.length == 0) {
            handleNoArgs(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "create"  -> handleCreate(player, args);
            case "join"    -> handleJoin(player, args);
            case "leave"   -> handleLeave(player);
            case "invite"  -> handleInvite(player, args);
            case "disband" -> handleDisband(player, args);
            case "chat"    -> handleChat(player, args);
            case "info"    -> handleInfo(player, args);
            case "kick"    -> handleKick(player, args);
            case "top"     -> handleTop(player);
            default -> {
                msg(player, "&cUnknown subcommand.");
                playNo(player);
            }
        }
        return true;
    }

    private void handleNoArgs(Player player) {
        if (!teamManager.isInTeam(player.getUniqueId())) {
            msg(player, cfgMsg("no-team", "&cYou don't have a team. Type /team create (name) to create a team."));
            actionBar(player, "&cYou don't have a team.");
            playNo(player);
        } else {
            Team team = teamManager.getTeam(player.getUniqueId());
            if (team != null) {
                player.openInventory(TeamGUI.buildTeamInfo(team));
            }
        }
    }

    private static final java.util.regex.Pattern VALID_TEAM_NAME = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]+$");

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            msg(player, "&cUsage: /team create <name>");
            return;
        }
        String name = args[1];

        if (name.length() < 3) {
            msg(player, "&cTeam name must be at least 3 characters long.");
            actionBar(player, "&cTeam name must be at least 3 characters long.");
            playNo(player);
            return;
        }

        if (!VALID_TEAM_NAME.matcher(name).matches()) {
            msg(player, "&cTeam name can only contain letters, numbers, and underscores.");
            actionBar(player, "&cNo special characters or color codes allowed.");
            playNo(player);
            return;
        }

        if (teamManager.isInTeam(player.getUniqueId())) {
            msg(player, cfgMsg("already-in-team", "&cYou are already in a team."));
            actionBar(player, cfgMsg("already-in-team", "&cYou are already in a team."));
            playNo(player);
            return;
        }
        if (teamManager.getTeamByName(name) != null) {
            msg(player, cfgMsg("name-taken", "&cYou cannot create a team with this name, it is already taken."));
            actionBar(player, cfgMsg("name-taken", "&cYou cannot create a team with this name, it is already taken."));
            playNo(player);
            return;
        }

        teamManager.createTeam(player, name);
        msg(player, cfgMsg("team-created", "&#7AFB00Team created."));
        actionBar(player, cfgMsg("team-created", "&#7AFB00Team created."));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    private void handleJoin(Player player, String[] args) {
        if (args.length < 2) return;

        String teamName = args[1];
        UUID uuid = player.getUniqueId();

        if (teamManager.isInTeam(uuid)) {
            msg(player, cfgMsg("already-in-team", "&cYou are already in a team."));
            actionBar(player, cfgMsg("already-in-team", "&cYou are already in a team."));
            playNo(player);
            return;
        }

        Team team = teamManager.getTeamByName(teamName);
        if (team == null) {
            msg(player, cfgMsg("team-not-found", "&cUser/team does not exist."));
            actionBar(player, cfgMsg("team-not-found", "&cUser/team does not exist."));
            playNo(player);
            return;
        }

        if (!teamManager.hasInviteFor(uuid, teamName)) {
            msg(player, cfgMsg("not-invited", "&cYou are not invited to this team."));
            actionBar(player, cfgMsg("not-invited", "&cYou are not invited to this team."));
            playNo(player);
            return;
        }

        String joinedMsg = cfgMsg("player-joined", "&#A7A7A7User &#00A4FB%player% &#A7A7A7joined the team.")
                .replace("%player%", player.getName());
        for (UUID memberUUID : team.getMemberUUIDs()) {
            Player member = Bukkit.getPlayer(memberUUID);
            if (member != null) { msg(member, joinedMsg); actionBar(member, joinedMsg); }
        }

        teamManager.addMember(uuid, player.getName(), teamName);
        teamManager.clearInvite(uuid);

        msg(player, cfgMsg("you-joined", "&#7AFB00You joined the team."));
        msg(player, cfgMsg("joined-team-name", "&#A7A7A7You joined the team &#00A4FB%team%")
                .replace("%team%", teamName));
        actionBar(player, cfgMsg("you-joined", "&#7AFB00You joined the team."));
    }

    private void handleLeave(Player player) {
        UUID uuid = player.getUniqueId();
        if (!teamManager.isInTeam(uuid)) {
            msg(player, cfgMsg("not-in-team", "&cYou do not have a team."));
            actionBar(player, cfgMsg("not-in-team", "&cYou do not have a team."));
            playNo(player);
            return;
        }

        Team team = teamManager.getTeam(uuid);
        if (team == null) return;

        if (team.isOwner(uuid)) {
            msg(player, cfgMsg("cannot-leave-owner", "&cYou cannot leave the team, you can only disband it."));
            actionBar(player, cfgMsg("cannot-leave-owner", "&cYou cannot leave the team, you can only disband it."));
            playNo(player);
            return;
        }

        String leftMsg = cfgMsg("player-left", "&#A7A7A7The user &#00A4FB%player% &#A7A7A7left the team")
                .replace("%player%", player.getName());
        for (UUID memberUUID : team.getMemberUUIDs()) {
            if (memberUUID.equals(uuid)) continue;
            Player member = Bukkit.getPlayer(memberUUID);
            if (member != null) { msg(member, leftMsg); actionBar(member, leftMsg); }
        }

        teamManager.removeMember(uuid);
        msg(player, cfgMsg("left-team", "&#A7A7A7You left the team"));
        actionBar(player, cfgMsg("left-team", "&#A7A7A7You left the team"));
    }

    private void handleInvite(Player player, String[] args) {
        if (args.length < 2) { msg(player, "&cUsage: /team invite <player>"); return; }

        UUID uuid = player.getUniqueId();
        Team team = teamManager.getTeam(uuid);
        if (team == null) { msg(player, cfgMsg("not-in-team", "&cYou do not have a team.")); playNo(player); return; }
        if (!team.isOwner(uuid)) { msg(player, "&cOnly the team owner can invite players."); playNo(player); return; }
        if (team.size() >= teamManager.getMaxTeamSize()) {
            msg(player, cfgMsg("team-full", "&cYou have max players in your team."));
            actionBar(player, cfgMsg("team-full", "&cYou have max players in your team."));
            playNo(player);
            return;
        }

        String targetName = args[1];
        if (targetName.equalsIgnoreCase(player.getName())) {
            msg(player, cfgMsg("already-has-team", "&cThis user already has a team."));
            playNo(player);
            return;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            msg(player, cfgMsg("user-offline", "&cThis user is offline."));
            actionBar(player, cfgMsg("user-offline", "&cThis user is offline."));
            playNo(player);
            return;
        }
        if (teamManager.isInTeam(target.getUniqueId())) {
            msg(player, cfgMsg("already-has-team", "&cThis user already has a team."));
            actionBar(player, cfgMsg("already-has-team", "&cThis user already has a team."));
            playNo(player);
            return;
        }

        String invitedMsg = cfgMsg("invited", "&#A7A7A7You invited &#00A4FB%player% &#A7A7A7to the team")
                .replace("%player%", target.getName());
        msg(player, invitedMsg);
        actionBar(player, invitedMsg);

        String receivedMsg = cfgMsg("invite-received", "&#00A4FB%player% &#A7A7A7invited you to the team &#00A4FB%team%")
                .replace("%player%", player.getName())
                .replace("%team%", team.getName());
        msg(target, receivedMsg);
        actionBar(target, receivedMsg);

        String clickRaw = cfgMsg("invite-click", "&#00A4FB[CLICK TO ACCEPT] &r&#A7A7A7or type &#00A4FB/team join %team%")
                .replace("%team%", team.getName());
        Component clickMsg = LEGACY.deserialize(ColorUtil.colorize(clickRaw))
                .clickEvent(ClickEvent.runCommand("/team join " + team.getName()));
        target.sendMessage(clickMsg);

        teamManager.invitePlayer(target.getUniqueId(), team.getName(), player.getName());
    }

    private void handleDisband(Player player, String[] args) {
        UUID uuid = player.getUniqueId();

        if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {
            if (teamManager.isPendingDisbandValid(uuid)) {
                String teamName = teamManager.getPendingDisband(uuid);
                Team team = teamManager.getTeamByName(teamName);
                if (team != null) teamManager.disbandTeam(teamName);
                teamManager.clearPendingDisband(uuid);
                msg(player, cfgMsg("disbanded", "&#A8A8A8You disbanded your team"));
                actionBar(player, cfgMsg("disbanded", "&#A8A8A8You disbanded your team"));
            }
            return;
        }

        Team team = findOwnedTeam(player);
        if (team == null) {
            msg(player, cfgMsg("not-in-team", "&cYou do not have a team."));
            actionBar(player, cfgMsg("not-in-team", "&cYou do not have a team."));
            playNo(player);
            return;
        }
        if (teamManager.hasPendingDisband(uuid)) {
            msg(player, cfgMsg("pending-disband", "&cYou already have a pending request."));
            return;
        }

        player.openInventory(TeamGUI.buildDisbandConfirmation());
        teamManager.setPendingDisband(uuid, team.getName());
    }

    private void handleChat(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        Team team = teamManager.getTeam(uuid);
        if (team == null) {
            msg(player, cfgMsg("not-in-team", "&cYou do not have a team."));
            actionBar(player, cfgMsg("not-in-team", "&cYou do not have a team."));
            playNo(player);
            return;
        }

        if (args.length < 2) {
            boolean enabled = teamManager.toggleTeamChat(uuid);
            if (enabled) {
                msg(player, cfgMsg("team-chat-enabled", "&#A8A8A8You enabled team chat."));
                actionBar(player, cfgMsg("team-chat-enabled", "&#A8A8A8You enabled team chat."));
            } else {
                msg(player, cfgMsg("team-chat-disabled", "&#A8A8A8You disabled team chat."));
                actionBar(player, cfgMsg("team-chat-disabled", "&#A8A8A8You disabled team chat."));
            }
        } else {
            String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
            broadcastTeamChat(team, player.getName(), message);
        }
    }

    private void handleInfo(Player player, String[] args) {
        Team team;
        if (args.length >= 2) {
            team = teamManager.getTeamByName(args[1]);
            if (team == null) { msg(player, cfgMsg("team-not-found", "&cUser/team does not exist.")); playNo(player); return; }
        } else {
            team = teamManager.getTeam(player.getUniqueId());
            if (team == null) { msg(player, cfgMsg("not-in-team", "&cYou do not have a team.")); playNo(player); return; }
        }
        player.openInventory(TeamGUI.buildTeamInfo(team));
    }

    private void handleKick(Player player, String[] args) {
        if (args.length < 2) { msg(player, "&cUsage: /team kick <player>"); return; }

        UUID uuid = player.getUniqueId();
        Team team = teamManager.getTeam(uuid);
        if (team == null) { msg(player, cfgMsg("not-in-team", "&cYou do not have a team.")); playNo(player); return; }
        if (!team.isOwnerOrMod(uuid)) { msg(player, "&cYou do not have permission to kick players."); playNo(player); return; }

        String targetName = args[1];
        TeamMember targetMember = null;
        for (TeamMember m : team.getMemberList()) {
            if (m.getName().equalsIgnoreCase(targetName)) { targetMember = m; break; }
        }

        if (targetMember == null) {
            msg(player, cfgMsg("not-in-your-team", "&cUser does not seem to be in your team."));
            actionBar(player, cfgMsg("not-in-your-team", "&cUser does not seem to be in your team."));
            playNo(player);
            return;
        }

        if (team.isOwner(targetMember.getUuid())) {
            if (team.isOwner(uuid)) {
                msg(player, cfgMsg("cannot-kick-self", "&cYou cannot kick yourself."));
                actionBar(player, cfgMsg("cannot-kick-self", "&cYou cannot kick yourself."));
            } else {
                msg(player, cfgMsg("cannot-kick-owner", "&cYou cannot kick your team owner."));
                actionBar(player, cfgMsg("cannot-kick-owner", "&cYou cannot kick your team owner."));
            }
            playNo(player);
            return;
        }

        UUID targetUUID = targetMember.getUuid();
        String kickedMsg = cfgMsg("kicked", "&#FC0000%player% got kicked from your team.")
                .replace("%player%", targetMember.getName());
        for (UUID memberUUID : team.getMemberUUIDs()) {
            Player member = Bukkit.getPlayer(memberUUID);
            if (member != null) { msg(member, kickedMsg); actionBar(member, kickedMsg); }
        }

        teamManager.removeMember(targetUUID);
        Player target = Bukkit.getPlayer(targetUUID);
        if (target != null) {
            msg(target, cfgMsg("you-kicked", "&#FC0000You got kicked from your team."));
            actionBar(target, cfgMsg("you-kicked", "&#FC0000You got kicked from your team."));
        }
    }

    private void handleTop(Player player) {
        String header = cfgMsg("top-header",
                "&8&m                    &r &#FCD05C&lTOP TEAMS &8&m                    ");
        String entryFmt = cfgMsg("top-entry",
                " &e#%rank% &7- &#00A4FB%team% &8(&#7AFB00%points% points&8)");
        String footer = cfgMsg("top-footer",
                "&8&m                                                    ");
        String empty  = cfgMsg("top-empty", "&cNo teams found.");

        List<Team> topTeams = teamManager.getTopTeams(10);

        msg(player, header);
        if (topTeams.isEmpty()) {
            msg(player, empty);
        } else {
            int rank = 1;
            for (Team team : topTeams) {
                String line = entryFmt
                        .replace("%rank%",   String.valueOf(rank))
                        .replace("%team%",   team.getName())
                        .replace("%points%", String.valueOf(team.getPoints()));
                msg(player, line);
                rank++;
            }
        }
        msg(player, footer);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        UUID uuid = player.getUniqueId();
        boolean inTeam = teamManager.isInTeam(uuid);

        if (args.length == 1) {
            List<String> subs = inTeam
                    ? List.of("leave", "chat", "invite", "disband", "info", "kick", "top")
                    : List.of("create", "join", "top");
            return filter(subs, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            return switch (sub) {
                case "invite" -> Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> startsWith(n, args[1]))
                        .collect(Collectors.toList());
                case "join", "info" -> teamManager.getAllTeamNames().stream()
                        .filter(n -> startsWith(n, args[1]))
                        .collect(Collectors.toList());
                case "create" -> List.of("(name)");
                case "kick" -> {
                    Team team = teamManager.getTeam(uuid);
                    if (team == null) yield Collections.<String>emptyList();
                    yield team.getMemberList().stream()
                            .map(TeamMember::getName)
                            .filter(n -> startsWith(n, args[1]))
                            .collect(Collectors.toList());
                }
                default -> Collections.emptyList();
            };
        }

        return Collections.emptyList();
    }

    public void broadcastTeamChat(Team team, String senderName, String message) {
        String formatted = ColorUtil.colorize("&#00A4FB[TEAM] " + senderName + ": &f" + message);
        Component comp = LEGACY.deserialize(formatted);
        for (UUID memberUUID : team.getMemberUUIDs()) {
            Player member = Bukkit.getPlayer(memberUUID);
            if (member != null) member.sendMessage(comp);
        }
    }

    private Team findOwnedTeam(Player player) {
        Team team = teamManager.getTeam(player.getUniqueId());
        if (team != null && team.isOwner(player.getUniqueId())) return team;
        return null;
    }

    private String cfgMsg(String key, String fallback) {
        return plugin.getConfig().getString("teams.messages." + key, fallback);
    }

    private void msg(Player player, String raw) {
        player.sendMessage(LEGACY.deserialize(ColorUtil.colorize(raw)));
    }

    private void actionBar(Player player, String raw) {
        player.sendActionBar(LEGACY.deserialize(ColorUtil.colorize(raw)));
    }

    private void playNo(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(s -> s.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }

    private static boolean startsWith(String value, String prefix) {
        return value.toLowerCase().startsWith(prefix.toLowerCase());
    }
}
