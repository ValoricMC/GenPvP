package me.revqz.genPvP.Protect.flags;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionTypeTest {

    @Test
    void spawnAllowsOnlyInteract() {
        var rules = RegionType.SPAWN.getAllowedRules();
        assertTrue(rules.contains(RegionRule.ALLOW_INTERACT));
        assertFalse(rules.contains(RegionRule.ALLOW_DAMAGE));
        assertFalse(rules.contains(RegionRule.ALLOW_BREAK));
        assertFalse(rules.contains(RegionRule.ALLOW_PLACE));
        assertFalse(rules.contains(RegionRule.ALLOW_MOB_SPAWN));
        assertFalse(rules.contains(RegionRule.ALLOW_KNOCKBACK));
        assertFalse(rules.contains(RegionRule.ALLOW_FLINT_STEEL));
    }

    @Test
    void gensAllowsBreakAndInteract() {
        var rules = RegionType.GENS.getAllowedRules();
        assertTrue(rules.contains(RegionRule.ALLOW_BREAK));
        assertTrue(rules.contains(RegionRule.ALLOW_INTERACT));
        assertFalse(rules.contains(RegionRule.ALLOW_DAMAGE));
        assertFalse(rules.contains(RegionRule.ALLOW_PLACE));
    }

    @Test
    void opminesAllowsFightMobsKnockbackOnly() {
        var rules = RegionType.OPMINES.getAllowedRules();
        assertTrue(rules.contains(RegionRule.ALLOW_DAMAGE));
        assertTrue(rules.contains(RegionRule.ALLOW_MOB_SPAWN));
        assertTrue(rules.contains(RegionRule.ALLOW_KNOCKBACK));
        assertFalse(rules.contains(RegionRule.ALLOW_BREAK));
        assertFalse(rules.contains(RegionRule.ALLOW_PLACE));
        assertFalse(rules.contains(RegionRule.ALLOW_FLINT_STEEL));
    }

    @Test
    void kothAllowsPlaceNotBreak() {
        var rules = RegionType.KOTH.getAllowedRules();
        assertTrue(rules.contains(RegionRule.ALLOW_PLACE));
        assertFalse(rules.contains(RegionRule.ALLOW_BREAK));
    }

    @Test
    void kothcaptureAllowsNeitherPlaceNorBreak() {
        var rules = RegionType.KOTHCAPTURE.getAllowedRules();
        assertFalse(rules.contains(RegionRule.ALLOW_PLACE));
        assertFalse(rules.contains(RegionRule.ALLOW_BREAK));
        assertTrue(rules.contains(RegionRule.ALLOW_DAMAGE));
        assertTrue(rules.contains(RegionRule.ALLOW_KNOCKBACK));
    }

    @Test
    void pitAllowsDamageAndKnockbackOnly() {
        var rules = RegionType.PIT.getAllowedRules();
        assertTrue(rules.contains(RegionRule.ALLOW_DAMAGE));
        assertTrue(rules.contains(RegionRule.ALLOW_KNOCKBACK));
        assertFalse(rules.contains(RegionRule.ALLOW_BREAK));
        assertFalse(rules.contains(RegionRule.ALLOW_PLACE));
        assertFalse(rules.contains(RegionRule.ALLOW_MOB_SPAWN));
        assertFalse(rules.contains(RegionRule.ALLOW_FLINT_STEEL));
    }

    @Test
    void allTypesAreDefined() {
        assertEquals(11, RegionType.values().length);
    }
}
