package me.revqz.genPvP.Teams;

import java.util.*;

public class Team {

    private final String name;
    private final UUID ownerUUID;
    private final String ownerName;
    private final long createdAt;
    private final Map<UUID, TeamMember> members = new LinkedHashMap<>();

    private boolean pvpEnabled;
    private int points;

    public Team(String name, UUID ownerUUID, String ownerName, long createdAt) {
        this.name       = name;
        this.ownerUUID  = ownerUUID;
        this.ownerName  = ownerName;
        this.createdAt  = createdAt;
        this.pvpEnabled = false;
        this.points     = 0;
    }

    public String getName() { return name; }

    public UUID getOwnerUUID() { return ownerUUID; }

    public String getOwnerName() { return ownerName; }

    public long getCreatedAt() { return createdAt; }

    public Map<UUID, TeamMember> getMembers() { return members; }

    public int size() { return members.size(); }

    public boolean containsPlayer(UUID uuid) { return members.containsKey(uuid); }

    public boolean isOwner(UUID uuid) { return ownerUUID.equals(uuid); }

    public boolean isMod(UUID uuid) {
        TeamMember m = members.get(uuid);
        return m != null && m.getRole() == TeamRole.MOD;
    }

    public boolean isOwnerOrMod(UUID uuid) {
        TeamMember m = members.get(uuid);
        return m != null && (m.getRole() == TeamRole.OWNER || m.getRole() == TeamRole.MOD);
    }

    public void addMember(TeamMember member) {
        members.put(member.getUuid(), member);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    /** Returns all member UUIDs (including the owner). */
    public Set<UUID> getMemberUUIDs() {
        return Collections.unmodifiableSet(members.keySet());
    }

    /** Returns all members as an ordered list (insertion order). */
    public List<TeamMember> getMemberList() {
        return new ArrayList<>(members.values());
    }

    /** Returns members sorted by join date (oldest first). */
    public List<TeamMember> getMembersSortedByJoinDate() {
        List<TeamMember> sorted = new ArrayList<>(members.values());
        sorted.sort(Comparator.comparingLong(TeamMember::getJoinedAt));
        return sorted;
    }

    // ── PvP ───────────────────────────────────────────────────────────────────

    public boolean isPvpEnabled() { return pvpEnabled; }

    public void setPvpEnabled(boolean pvpEnabled) { this.pvpEnabled = pvpEnabled; }

    public void togglePvp() { this.pvpEnabled = !this.pvpEnabled; }

    // ── Points ────────────────────────────────────────────────────────────────

    public int getPoints() { return points; }

    public void setPoints(int points) { this.points = points; }

    public void addPoints(int amount) { this.points += amount; }
}
