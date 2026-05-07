package me.revqz.genPvP.DevilFruits;

public enum FruitType {

    PARAMECIA("Paramecia"),
    ZOAN("Zoan"),
    LOGIA("Logia");

    private final String displayName;

    FruitType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
