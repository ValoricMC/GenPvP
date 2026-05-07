package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.util.ColorUtil;

public enum DevilFruit {

    MERA_MERA  ("mera_mera",   "&#FF4500&lMera Mera no Mi",   FruitType.LOGIA),
    MAGU_MAGU  ("magu_magu",   "&#EE5A24&lMagu Magu no Mi",   FruitType.LOGIA),
    GORO_GORO  ("goro_goro",   "&#FFD700&lGoro Goro no Mi",   FruitType.LOGIA),
    HIE_HIE    ("hie_hie",     "&#00FFFF&lHie Hie no Mi",     FruitType.LOGIA),
    YAMI_YAMI  ("yami_yami",   "&#2C003E&lYami Yami no Mi",   FruitType.LOGIA),

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

    public String getKey() { return key; }

    public String getDisplayName() { return ColorUtil.colorize(rawName); }

    public FruitType getType() { return type; }

    public static DevilFruit fromKey(String key) {
        if (key == null) return null;
        String lower = key.toLowerCase();
        for (DevilFruit f : values()) {
            if (f.key.equals(lower)) return f;
        }
        return null;
    }
}
