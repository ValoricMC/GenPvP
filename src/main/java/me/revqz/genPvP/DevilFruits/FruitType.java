package me.revqz.genPvP.DevilFruits;

public enum FruitType {

    PARAMECIA("Paramecia"),
    ZOAN("Zoan"),
    LOGIA("Logia");

    private final String displayName;

    FruitType(String displayName) {
        this.displayName = displayName;
    }

    /** Player-facing type name, also matches the YAML filename (e.g. "Logia.yml"). */
    public String getDisplayName() { return displayName; }
}
