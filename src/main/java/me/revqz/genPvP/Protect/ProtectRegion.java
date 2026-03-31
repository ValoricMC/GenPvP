package me.revqz.genPvP.Protect;

import me.revqz.genPvP.Protect.flags.RegionRule;
import me.revqz.genPvP.Protect.flags.RegionType;
import org.bukkit.Location;

public class ProtectRegion {

    private final String name;
    private final RegionType type;
    private final String world;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    public ProtectRegion(String name, RegionType type, String world,
                         int x1, int y1, int z1,
                         int x2, int y2, int z2) {
        this.name = name;
        this.type = type;
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null || !world.equals(loc.getWorld().getName())) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean hasRule(RegionRule rule) {
        return type.getAllowedRules().contains(rule);
    }

    public String getName()  { return name; }
    public RegionType getType() { return type; }
    public String getWorld() { return world; }
    public int getMinX()     { return minX; }
    public int getMinY()     { return minY; }
    public int getMinZ()     { return minZ; }
    public int getMaxX()     { return maxX; }
    public int getMaxY()     { return maxY; }
    public int getMaxZ()     { return maxZ; }
}
