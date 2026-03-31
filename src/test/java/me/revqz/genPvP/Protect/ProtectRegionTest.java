package me.revqz.genPvP.Protect;

import me.revqz.genPvP.Protect.flags.RegionRule;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProtectRegionTest {

    private World world;
    private ProtectRegion region;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        // Region from (0,0,0) to (10,10,10) — corners intentionally reversed to test normalization
        region = new ProtectRegion("test", RegionType.SPAWN, "world", 10, 10, 10, 0, 0, 0);
    }

    @Test
    void constructorNormalizesMinMax() {
        assertEquals(0, region.getMinX());
        assertEquals(0, region.getMinY());
        assertEquals(0, region.getMinZ());
        assertEquals(10, region.getMaxX());
        assertEquals(10, region.getMaxY());
        assertEquals(10, region.getMaxZ());
    }

    @Test
    void containsLocationInsideRegion() {
        assertTrue(region.contains(new Location(world, 5, 5, 5)));
    }

    @Test
    void containsLocationOnBorder() {
        assertTrue(region.contains(new Location(world, 0, 0, 0)));
        assertTrue(region.contains(new Location(world, 10, 10, 10)));
    }

    @Test
    void doesNotContainLocationOutside() {
        assertFalse(region.contains(new Location(world, 11, 5, 5)));
        assertFalse(region.contains(new Location(world, 5, -1, 5)));
    }

    @Test
    void doesNotContainLocationInDifferentWorld() {
        World other = mock(World.class);
        when(other.getName()).thenReturn("nether");
        assertFalse(region.contains(new Location(other, 5, 5, 5)));
    }

    @Test
    void hasRuleDelegatesToRegionType() {
        assertTrue(region.hasRule(RegionRule.ALLOW_INTERACT));
        assertFalse(region.hasRule(RegionRule.ALLOW_DAMAGE));
    }
}
