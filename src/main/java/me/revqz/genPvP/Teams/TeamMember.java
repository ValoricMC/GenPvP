package me.revqz.genPvP.Teams;

import java.util.UUID;

public class TeamMember {

    private final UUID uuid;
    private String name;
    private TeamRole role;
    private final long joinedAt;

    public TeamMember(UUID uuid, String name, TeamRole role, long joinedAt) {
        this.uuid     = uuid;
        this.name     = name;
        this.role     = role;
        this.joinedAt = joinedAt;
    }

    public UUID getUuid() { return uuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TeamRole getRole() { return role; }
    public void setRole(TeamRole role) { this.role = role; }

    public long getJoinedAt() { return joinedAt; }
}
