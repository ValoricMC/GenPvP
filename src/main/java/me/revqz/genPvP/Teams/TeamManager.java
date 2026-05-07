package me.revqz.genPvP.Teams;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.GenPvP;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static com.mongodb.client.model.Filters.eq;

public class TeamManager implements Listener {

    private final GenPvP plugin;
    private final MongoDatabase db;

    public GenPvP getPlugin() { return plugin; }

    private final Map<String, Team> teamsByName = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTeam  = new ConcurrentHashMap<>();

    private final Map<UUID, TeamInvite> pendingInvites = new ConcurrentHashMap<>();
    private final Set<UUID> teamChatEnabled = ConcurrentHashMap.newKeySet();

    private final Map<UUID, String> pendingDisband     = new ConcurrentHashMap<>();
    private final Map<UUID, Long>   pendingDisbandTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingSearch = new ConcurrentHashMap<>();

    public TeamManager(GenPvP plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.db     = databaseManager.isMongoConnected() ? databaseManager.getDatabase() : null;
        if (db != null) {
            loadAll();
        }
    }

    private void loadAll() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MongoCollection<Document> teamsColl   = db.getCollection("teams");
                MongoCollection<Document> membersColl = db.getCollection("team_members");

                for (Document doc : teamsColl.find()) {
                    String name      = doc.getString("_id");
                    UUID   ownerUUID = UUID.fromString(doc.getString("owner_uuid"));
                    String ownerName = doc.getString("owner_name");
                    long   createdAt = doc.getLong("created_at");
                    Team   team      = new Team(name, ownerUUID, ownerName, createdAt);
                    team.setPvpEnabled(doc.getBoolean("pvp_enabled", false));
                    team.setPoints(doc.getInteger("points", 0));
                    teamsByName.put(name, team);
                }

                for (Document doc : membersColl.find().sort(new Document("joined_at", 1))) {
                    UUID     uuid     = UUID.fromString(doc.getString("_id"));
                    String   name     = doc.getString("player_name");
                    String   teamName = doc.getString("team_name");
                    TeamRole role     = TeamRole.fromString(doc.getString("role"));
                    long     joinedAt = doc.getLong("joined_at");
                    Team team = teamsByName.get(teamName);
                    if (team != null) {
                        team.addMember(new TeamMember(uuid, name, role, joinedAt));
                        playerTeam.put(uuid, teamName);
                    }
                }

                plugin.getLogger().info("[TeamManager] Loaded " + teamsByName.size() + " teams.");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "[TeamManager] Failed to load teams: " + e.getMessage());
            }
        });
    }

    public Team getTeam(UUID playerUUID) {
        String name = playerTeam.get(playerUUID);
        return name == null ? null : teamsByName.get(name);
    }

    public Team getTeamByName(String name) { return teamsByName.get(name); }
    public boolean isInTeam(UUID playerUUID) { return playerTeam.containsKey(playerUUID); }
    public String getTeamName(UUID playerUUID) { return playerTeam.get(playerUUID); }
    public Collection<String> getAllTeamNames() { return Collections.unmodifiableCollection(teamsByName.keySet()); }
    public int getMaxTeamSize() { return plugin.getConfig().getInt("teams.max-size", 14); }

    public List<Team> getTopTeams(int limit) {
        return teamsByName.values().stream()
                .sorted(Comparator.comparingInt(Team::getPoints).reversed())
                .limit(limit)
                .toList();
    }

    public boolean createTeam(Player player, String name) {
        if (teamsByName.containsKey(name))              return false;
        if (playerTeam.containsKey(player.getUniqueId())) return false;

        UUID   uuid = player.getUniqueId();
        String pName = player.getName();
        long   now   = System.currentTimeMillis();
        Team   team  = new Team(name, uuid, pName, now);
        TeamMember owner = new TeamMember(uuid, pName, TeamRole.OWNER, now);
        team.addMember(owner);

        teamsByName.put(name, team);
        playerTeam.put(uuid, name);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("teams").insertOne(new Document("_id", name)
                        .append("owner_uuid", uuid.toString())
                        .append("owner_name", pName)
                        .append("created_at", now)
                        .append("pvp_enabled", false)
                        .append("points", 0));
                insertMember(uuid, pName, name, TeamRole.OWNER, now);
            } catch (Exception e) {
                plugin.getLogger().warning("[TeamManager] Failed to save team '" + name + "': " + e.getMessage());
            }
        });
        return true;
    }

    public void disbandTeam(String teamName) {
        Team team = teamsByName.remove(teamName);
        if (team == null) return;

        for (UUID uuid : team.getMemberUUIDs()) {
            playerTeam.remove(uuid);
            teamChatEnabled.remove(uuid);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("teams").deleteOne(eq("_id", teamName));
                db.getCollection("team_members").deleteMany(eq("team_name", teamName));
            } catch (Exception e) {
                plugin.getLogger().warning("[TeamManager] Failed to disband team '" + teamName + "': " + e.getMessage());
            }
        });
    }

    public boolean addMember(UUID uuid, String playerName, String teamName) {
        Team team = teamsByName.get(teamName);
        if (team == null) return false;
        if (team.size() >= getMaxTeamSize()) return false;
        if (playerTeam.containsKey(uuid)) return false;

        long now = System.currentTimeMillis();
        TeamMember member = new TeamMember(uuid, playerName, TeamRole.MEMBER, now);
        team.addMember(member);
        playerTeam.put(uuid, teamName);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                insertMember(uuid, playerName, teamName, TeamRole.MEMBER, now);
            } catch (Exception e) {
                plugin.getLogger().warning("[TeamManager] Failed to add member: " + e.getMessage());
            }
        });
        return true;
    }

    public void removeMember(UUID uuid) {
        String teamName = playerTeam.remove(uuid);
        if (teamName == null) return;
        Team team = teamsByName.get(teamName);
        if (team != null) team.removeMember(uuid);
        teamChatEnabled.remove(uuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("team_members").deleteOne(eq("_id", uuid.toString()));
            } catch (Exception e) {
                plugin.getLogger().warning("[TeamManager] Failed to remove member: " + e.getMessage());
            }
        });
    }

    public boolean togglePvp(String teamName) {
        Team team = teamsByName.get(teamName);
        if (team == null) return false;
        team.togglePvp();
        boolean state = team.isPvpEnabled();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("teams").updateOne(
                        eq("_id", teamName),
                        new Document("$set", new Document("pvp_enabled", state)));
            } catch (Exception e) {
                plugin.getLogger().warning("[TeamManager] Failed to update PvP for '" + teamName + "': " + e.getMessage());
            }
        });
        return state;
    }

    public boolean shouldBlockDamage(UUID attacker, UUID victim) {
        String attackerTeam = playerTeam.get(attacker);
        String victimTeam   = playerTeam.get(victim);
        if (attackerTeam == null || victimTeam == null) return false;
        if (!attackerTeam.equals(victimTeam)) return false;
        Team team = teamsByName.get(attackerTeam);
        return team != null && !team.isPvpEnabled();
    }

    public void addPoints(String teamName, int amount) {
        Team team = teamsByName.get(teamName);
        if (team == null) return;
        team.addPoints(amount);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("teams").updateOne(
                        eq("_id", teamName),
                        new Document("$inc", new Document("points", amount)));
            } catch (Exception e) {
                plugin.getLogger().warning("[TeamManager] Failed to update points for '" + teamName + "': " + e.getMessage());
            }
        });
    }

    public void onPlayerKill(UUID killerUUID) {
        String teamName = playerTeam.get(killerUUID);
        if (teamName != null) addPoints(teamName, 1);
    }

    public void onPlayerDeath(UUID victimUUID) {
        String teamName = playerTeam.get(victimUUID);
        if (teamName != null) addPoints(teamName, -1);
    }

    public void invitePlayer(UUID targetUUID, String teamName, String inviterName) {
        long expiresAt = System.currentTimeMillis() + TeamInvite.INVITE_DURATION_MS;
        pendingInvites.put(targetUUID, new TeamInvite(teamName, inviterName, expiresAt));
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            TeamInvite inv = pendingInvites.get(targetUUID);
            if (inv != null && inv.isExpired()) pendingInvites.remove(targetUUID);
        }, 20L * 60);
    }

    public TeamInvite getInvite(UUID targetUUID) {
        TeamInvite inv = pendingInvites.get(targetUUID);
        if (inv == null) return null;
        if (inv.isExpired()) { pendingInvites.remove(targetUUID); return null; }
        return inv;
    }

    public boolean hasInviteFor(UUID targetUUID, String teamName) {
        TeamInvite inv = getInvite(targetUUID);
        return inv != null && inv.teamName().equalsIgnoreCase(teamName);
    }

    public void clearInvite(UUID targetUUID) { pendingInvites.remove(targetUUID); }

    public boolean isTeamChatEnabled(UUID uuid) { return teamChatEnabled.contains(uuid); }

    public boolean toggleTeamChat(UUID uuid) {
        if (teamChatEnabled.contains(uuid)) { teamChatEnabled.remove(uuid); return false; }
        else { teamChatEnabled.add(uuid); return true; }
    }

    public void setPendingSearch(UUID uuid, String teamName) { pendingSearch.put(uuid, teamName); }
    public String getPendingSearch(UUID uuid) { return pendingSearch.get(uuid); }
    public boolean hasPendingSearch(UUID uuid) { return pendingSearch.containsKey(uuid); }
    public void clearPendingSearch(UUID uuid) { pendingSearch.remove(uuid); }

    public void setPendingDisband(UUID uuid, String teamName) {
        pendingDisband.put(uuid, teamName);
        pendingDisbandTime.put(uuid, System.currentTimeMillis());
    }
    public String getPendingDisband(UUID uuid) { return pendingDisband.get(uuid); }
    public boolean hasPendingDisband(UUID uuid) {
        Long time = pendingDisbandTime.get(uuid);
        if (time == null) return false;
        if (System.currentTimeMillis() - time > 60_000L) { clearPendingDisband(uuid); return false; }
        return true;
    }
    public boolean isPendingDisbandValid(UUID uuid) {
        Long time = pendingDisbandTime.get(uuid);
        return time != null && System.currentTimeMillis() - time <= 60_000L;
    }
    public void clearPendingDisband(UUID uuid) {
        pendingDisband.remove(uuid);
        pendingDisbandTime.remove(uuid);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clearPendingDisband(uuid);
        clearPendingSearch(uuid);

        String teamName = playerTeam.get(uuid);
        if (teamName != null) {
            Team team = teamsByName.get(teamName);
            if (team != null) {
                TeamMember m = team.getMembers().get(uuid);
                if (m != null && !event.getPlayer().getName().equals(m.getName())) {
                    m.setName(event.getPlayer().getName());
                    asyncUpdateMemberName(uuid, event.getPlayer().getName());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clearPendingDisband(uuid);
        clearPendingSearch(uuid);
        teamChatEnabled.remove(uuid);
    }

    private void insertMember(UUID uuid, String name, String teamName,
                              TeamRole role, long joinedAt) {
        db.getCollection("team_members").updateOne(
                eq("_id", uuid.toString()),
                new Document("$set", new Document("player_name", name)
                        .append("team_name", teamName)
                        .append("role", role.name())
                        .append("joined_at", joinedAt)),
                new UpdateOptions().upsert(true));
    }

    private void asyncUpdateMemberName(UUID uuid, String name) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                db.getCollection("team_members").updateOne(
                        eq("_id", uuid.toString()),
                        new Document("$set", new Document("player_name", name)));
            } catch (Exception ignored) {}
        });
    }

    public void shutdown() {  }

    public void wipeAllMemory() {
        teamsByName.clear();
        playerTeam.clear();
        pendingInvites.clear();
        teamChatEnabled.clear();
        pendingDisband.clear();
        pendingDisbandTime.clear();
        pendingSearch.clear();
    }
}
