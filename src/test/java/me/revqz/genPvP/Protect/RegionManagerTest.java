package me.revqz.genPvP.Protect;

import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RegionManagerTest {

    private World world;
    private RegionManager manager;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        manager = new RegionManager();
    }

    @Test
    void defineRegionStoresItInMemory() {
        manager.defineRegion("spawn", RegionType.SPAWN, "world", 0, 0, 0, 10, 10, 10);
        List<ProtectRegion> result = manager.getRegionsAt(new Location(world, 5, 5, 5));
        assertEquals(1, result.size());
        assertEquals("spawn", result.get(0).getName());
    }

    @Test
    void getRegionsAtReturnsEmptyWhenNoRegions() {
        assertTrue(manager.getRegionsAt(new Location(world, 5, 5, 5)).isEmpty());
    }

    @Test
    void getRegionsAtReturnsOnlyMatchingRegions() {
        manager.defineRegion("r1", RegionType.SPAWN, "world", 0, 0, 0, 10, 10, 10);
        manager.defineRegion("r2", RegionType.GENS, "world", 20, 0, 20, 30, 10, 30);
        assertEquals(1, manager.getRegionsAt(new Location(world, 5, 5, 5)).size());
        assertEquals(0, manager.getRegionsAt(new Location(world, 50, 5, 50)).size());
    }

    @Test
    void defineRegionOverwritesExistingByName() {
        manager.defineRegion("r1", RegionType.SPAWN, "world", 0, 0, 0, 10, 10, 10);
        manager.defineRegion("r1", RegionType.GENS, "world", 0, 0, 0, 10, 10, 10);
        List<ProtectRegion> result = manager.getRegionsAt(new Location(world, 5, 5, 5));
        assertEquals(1, result.size());
        assertEquals(RegionType.GENS, result.get(0).getType());
    }
}
