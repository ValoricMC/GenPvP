package me.revqz.genPvP.Teams;

public record TeamInvite(String teamName, String inviterName, long expiresAt) {

    public static final long INVITE_DURATION_MS = 60_000L;

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}
