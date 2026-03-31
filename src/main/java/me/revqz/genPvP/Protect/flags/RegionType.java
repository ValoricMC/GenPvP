package me.revqz.genPvP.Protect.flags;

import java.util.EnumSet;
import java.util.Set;

public enum RegionType {
    SPAWN(EnumSet.of(RegionRule.ALLOW_INTERACT)),
    GENS(EnumSet.of(RegionRule.ALLOW_BREAK, RegionRule.ALLOW_INTERACT)),
    OPMINES(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK)),
    OPMINESGENS(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK,
            RegionRule.ALLOW_BREAK, RegionRule.ALLOW_PLACE)),
    KOTH(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK, RegionRule.ALLOW_PLACE)),
    KOTHCAPTURE(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK)),
    PVPROOM1(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK)),
    PVPROOM2(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK)),
    PVPROOMGATE1(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK)),
    PVPROOMGATE2(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_MOB_SPAWN, RegionRule.ALLOW_KNOCKBACK)),
    PIT(EnumSet.of(RegionRule.ALLOW_DAMAGE, RegionRule.ALLOW_KNOCKBACK));

    private final Set<RegionRule> allowedRules;

    RegionType(Set<RegionRule> allowedRules) {
        this.allowedRules = allowedRules;
    }

    public Set<RegionRule> getAllowedRules() {
        return allowedRules;
    }
}
