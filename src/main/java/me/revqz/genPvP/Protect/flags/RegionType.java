package me.revqz.genPvP.Protect.flags;

import java.util.EnumSet;
import java.util.Set;

public enum RegionType {
    SPAWN(EnumSet.of(RegionRule.ALLOW_INTERACT)),
    GENS(EnumSet.of(RegionRule.ALLOW_BREAK, RegionRule.ALLOW_INTERACT)),
    OPMINES(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    OPMINESGENS(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_BREAK, RegionRule.ALLOW_PLACE, RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    KOTH(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    KOTHCAPTURE(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    PVPROOM1(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    PVPROOM2(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    PVPROOMGATE1(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_PEARL)),
    PVPROOMGATE2(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_PEARL)),
    PIT(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK, RegionRule.ALLOW_WIND_CHARGE)),
    
    SHULKERROOMS(EnumSet.of(RegionRule.ALLOW_INTERACT)),
    
    ANVIL(EnumSet.of(RegionRule.ALLOW_INTERACT, RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_PEARL, RegionRule.ALLOW_WIND_CHARGE)),
    
    PITNETHERITE(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN,
            RegionRule.ALLOW_KNOCKBACK, RegionRule.ALLOW_BREAK,
            RegionRule.ALLOW_WIND_CHARGE, RegionRule.ALLOW_PEARL)),
    
    GLOBAL(EnumSet.allOf(RegionRule.class));

    private final Set<RegionRule> allowedRules;

    RegionType(Set<RegionRule> allowedRules) {
        this.allowedRules = allowedRules;
    }

    public Set<RegionRule> getAllowedRules() {
        return allowedRules;
    }
}
