package me.revqz.genPvP.PvPRooms;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PvPRoomState {

    private final String roomIdentifier;
    private final String gateIdentifier;

    private PvPPhase currentPhase = PvPPhase.WAITING;

    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();

    private int lootingCountdown = 0;

    private int cachedInsideCount = 0;

    public PvPRoomState(String roomIdentifier, String gateIdentifier) {
        this.roomIdentifier = roomIdentifier;
        this.gateIdentifier = gateIdentifier;
    }

    public String getRoomIdentifier() { return roomIdentifier; }
    public String getGateIdentifier() { return gateIdentifier; }

    public PvPPhase getCurrentPhase()         { return currentPhase; }
    public void setCurrentPhase(PvPPhase p)   { this.currentPhase = p; }

    public Set<UUID> getParticipants()        { return Collections.unmodifiableSet(participants); }
    public boolean   isParticipant(UUID uuid) { return participants.contains(uuid); }

    public void addParticipant(Player player)    { participants.add(player.getUniqueId()); }
    public void removeParticipant(Player player) { participants.remove(player.getUniqueId()); }
    public void clearParticipants()              { participants.clear(); }

    public int  getCachedInsideCount()      { return cachedInsideCount; }
    public void setCachedInsideCount(int n) { cachedInsideCount = n; }

    public int  getLootingCountdown()          { return lootingCountdown; }
    public void setLootingCountdown(int ticks) { this.lootingCountdown = ticks; }
    public void decrementCountdown()           { lootingCountdown--; }
}
