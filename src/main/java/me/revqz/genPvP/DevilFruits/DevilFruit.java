package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.util.ColorUtil;

/**
 * Registry of all known Devil Fruits.
 *
 * Add new entries here as each fruit is implemented.
 * The {@link #key} is the internal DB/config identifier (snake_case).
 * Raw display names use {@code &#RRGGBB} hex codes; {@link #getDisplayName()} returns
 * them already translated so they render correctly wherever inserted.
 */
public enum DevilFruit {

    // ── Logia ─────────────────────────────────────────────────────────────────
    MERA_MERA  ("mera_mera",   "&#FF4500&lMera Mera no Mi",   FruitType.LOGIA),
    MAGU_MAGU  ("magu_magu",   "&#EE5A24&lMagu Magu no Mi",   FruitType.LOGIA),
    YAMI_YAMI  ("yami_yami",   "&#2C003E&lYami Yami no Mi",   FruitType.LOGIA),

    // ── Paramecia ─────────────────────────────────────────────────────────────
    ZUSHI_ZUSHI ("zushi_zushi",  "&#7B68EE&lZushi Zushi no Mi",  FruitType.PARAMECIA),
    DOKU_DOKU   ("doku_doku",    "&#4B0082&lDoku Doku no Mi",    FruitType.PARAMECIA),
    NIKYU_NIKYU ("nikyu_nikyu",  "&#FFA500&lNikyu Nikyu no Mi",  FruitType.PARAMECIA),
    SUBE_SUBE   ("sube_sube",    "&#ADD8E6&lSube Sube no Mi",    FruitType.PARAMECIA),
    SUKE_SUKE   ("suke_suke",    "&#E0E0E0&lSuke Suke no Mi",    FruitType.PARAMECIA),
    BANE_BANE   ("bane_bane",    "&#90EE90&lBane Bane no Mi",    FruitType.PARAMECIA),
    SUPA_SUPA   ("supa_supa",    "&#C0C0C0&lSupa Supa no Mi",    FruitType.PARAMECIA),
    BOMU_BOMU   ("bomu_bomu",    "&#FF6347&lBomu Bomu no Mi",    FruitType.PARAMECIA),
    BARI_BARI   ("bari_bari",    "&#87CEEB&lBari Bari no Mi",    FruitType.PARAMECIA),
    FUWA_FUWA   ("fuwa_fuwa",    "&#FFFACD&lFuwa Fuwa no Mi",    FruitType.PARAMECIA),
    GURA_GURA   ("gura_gura",    "&#8B4513&lGura Gura no Mi",    FruitType.PARAMECIA),

    // ── Zoan ──────────────────────────────────────────────────────────────────
    TORI_TORI_FALCON    ("tori_tori_falcon",    "&#F5DEB3&lTori Tori no Mi: Falcon",    FruitType.ZOAN),
    KUMO_KUMO_TARANTULA ("kumo_kumo_tarantula", "&#8B0000&lKumo Kumo no Mi: Tarantula", FruitType.ZOAN),
    ZOU_ZOU_MAMMOTH     ("zou_zou_mammoth",     "&#8B4513&lZou Zou no Mi: Mammoth",     FruitType.ZOAN),
    NEKO_NEKO_LEOPARD   ("neko_neko_leopard",   "&#FFD700&lNeko Neko no Mi: Leopard",   FruitType.ZOAN),
    HEBI_HEBI_COBRA     ("hebi_hebi_cobra",     "&#228B22&lHebi Hebi no Mi: Cobra",     FruitType.ZOAN),
    INU_INU_WOLF        ("inu_inu_wolf",        "&#A9A9A9&lInu Inu no Mi: Wolf",        FruitType.ZOAN),
    ;

    private final String key;
    private final String rawName;
    private final FruitType type;

    DevilFruit(String key, String rawName, FruitType type) {
        this.key     = key;
        this.rawName = rawName;
        this.type    = type;
    }

    /** Internal identifier stored in the database (e.g. {@code "mera_mera"}). */
    public String getKey() { return key; }

    /**
     * Coloured display name — hex codes pre-translated so it can be safely
     * inserted into any string without a second colourize pass.
     */
    public String getDisplayName() { return ColorUtil.colorize(rawName); }

    /** The category this fruit belongs to (Logia, Paramecia, or Zoan). */
    public FruitType getType() { return type; }

    /**
     * Looks up a fruit by its key (case-insensitive).
     *
     * @return the matching {@link DevilFruit}, or {@code null} if not found.
     */
    public static DevilFruit fromKey(String key) {
        if (key == null) return null;
        String lower = key.toLowerCase();
        for (DevilFruit f : values()) {
            if (f.key.equals(lower)) return f;
        }
        return null;
    }
}
