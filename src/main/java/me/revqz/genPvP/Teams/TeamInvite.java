package me.revqz.genPvP.Teams;

/**
 * An invite pending for a specific player.
 *
 * @param teamName    the team they were invited to
 * @param inviterName the IGN of whoever sent the invite
 * @param expiresAt   epoch millis when this invite expires
 */
public record TeamInvite(String teamName, String inviterName, long expiresAt) {

    /** 60-second hard-coded expiry. */
    public static final long INVITE_DURATION_MS = 60_000L;

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}
