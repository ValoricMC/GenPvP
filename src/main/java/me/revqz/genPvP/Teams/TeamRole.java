package me.revqz.genPvP.Teams;

public enum TeamRole {
    OWNER,
    MOD,
    MEMBER;

    public static TeamRole fromString(String s) {
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return MEMBER;
        }
    }
}
