package me.revqz.genPvP.PvPRooms;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable state for a single PvP room. Accessed only from the main thread
 * (Bukkit scheduler + event handlers), so no additional synchronisation is needed
 * beyond ConcurrentHashMap.newKeySet() for the participant set.
 */
public class PvPRoomState {

    private final String roomIdentifier;
    private final String gateIdentifier;

    private PvPPhase currentPhase = PvPPhase.WAITING;

    // ConcurrentHashMap-backed set so getParticipants() can be iterated safely
    // even if a reset races with a placeholder API call on another thread.
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();

    private int lootingCountdown = 0;

    /**
     * Cached count of players physically inside the room during WAITING.
     * Updated by the PlayerMoveEvent-driven path and the polling fallback.
     * During FIGHTING / LOOTING the participants set is authoritative instead.
     */
    private int cachedInsideCount = 0;

    public PvPRoomState(String roomIdentifier, String gateIdentifier) {
        this.roomIdentifier = roomIdentifier;
        this.gateIdentifier = gateIdentifier;
    }

    // ── Identifiers ───────────────────────────────────────────────────────────

    public String getRoomIdentifier() { return roomIdentifier; }
    public String getGateIdentifier() { return gateIdentifier; }

    // ── Phase ─────────────────────────────────────────────────────────────────

    public PvPPhase getCurrentPhase()         { return currentPhase; }
    public void setCurrentPhase(PvPPhase p)   { this.currentPhase = p; }

    // ── Participants ──────────────────────────────────────────────────────────

    public Set<UUID> getParticipants()        { return Collections.unmodifiableSet(participants); }
    public boolean   isParticipant(UUID uuid) { return participants.contains(uuid); }

    public void addParticipant(Player player)    { participants.add(player.getUniqueId()); }
    public void removeParticipant(Player player) { participants.remove(player.getUniqueId()); }
    public void clearParticipants()              { participants.clear(); }

    // ── Cached inside count (WAITING phase only) ──────────────────────────────

    public int  getCachedInsideCount()      { return cachedInsideCount; }
    public void setCachedInsideCount(int n) { cachedInsideCount = n; }

    // ── Loot countdown ────────────────────────────────────────────────────────

    public int  getLootingCountdown()          { return lootingCountdown; }
    public void setLootingCountdown(int ticks) { this.lootingCountdown = ticks; }
    public void decrementCountdown()           { lootingCountdown--; }
}
