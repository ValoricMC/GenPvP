# Protect System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a region protection system with hardcoded per-type rules, block decay timers for survival-placed blocks, and MongoDB-backed region persistence.

**Architecture:** A `RegionType` enum carries `EnumSet<RegionRule>` flags; a thin `ProtectListener` checks those flags on Bukkit events. Regions are stored in memory and persisted to MongoDB. Block decay is in-memory only (resets on restart).

**Tech Stack:** Paper 1.21, Java 21, MongoDB driver sync 5.1.1, PlaceholderAPI 2.11.6 (softdepend), JUnit 5, Mockito 5, MockBukkit v4

---

## File Map

| Action | Path | Responsibility |
|---|---|---|
| Modify | `build.gradle` | Add deps + test config |
| Modify | `src/main/resources/plugin.yml` | Add softdepends + region command |
| Create | `src/main/resources/config.yml` | Default config values |
| Modify | `src/main/java/me/revqz/genPvP/GenPvP.java` | Wire up all Protect components |
| Create | `src/main/java/me/revqz/genPvP/protect/flags/RegionRule.java` | Enum of all rule flags |
| Create | `src/main/java/me/revqz/genPvP/protect/flags/RegionType.java` | Enum of region types with allowed rules |
| Create | `src/main/java/me/revqz/genPvP/protect/ProtectRegion.java` | Cuboid region data class |
| Create | `src/main/java/me/revqz/genPvP/protect/RegionManager.java` | In-memory store + MongoDB load/save |
| Create | `src/main/java/me/revqz/genPvP/protect/BlockTimerManager.java` | Survival block decay scheduling |
| Create | `src/main/java/me/revqz/genPvP/protect/ProtectCommand.java` | `/region` command handler |
| Create | `src/main/java/me/revqz/genPvP/protect/ProtectListener.java` | Bukkit event enforcement |
| Create | `src/test/java/me/revqz/genPvP/protect/flags/RegionTypeTest.java` | Flag correctness tests |
| Create | `src/test/java/me/revqz/genPvP/protect/ProtectRegionTest.java` | `contains()` logic tests |
| Create | `src/test/java/me/revqz/genPvP/protect/RegionManagerTest.java` | In-memory region store tests |
| Create | `src/test/java/me/revqz/genPvP/protect/ProtectListenerTest.java` | Event enforcement tests |

---

## Task 1: Build Setup

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/plugin.yml`

- [ ] **Step 1: Replace `build.gradle` with the following**

```groovy
plugins {
    id 'java'
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = 'me.revqz'
version = '1.0'

repositories {
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = "https://repo.papermc.io/repository/maven-public/"
    }
    maven {
        name = "placeholderapi"
        url = "https://repo.extendedclip.com/content/repositories/placeholderapi/"
    }
    maven {
        name = "enginehub"
        url = "https://maven.enginehub.org/repo/"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.11.2")
    implementation("org.mongodb:mongodb-driver-sync:5.1.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.10.0")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.22.0")
}

tasks {
    runServer {
        minecraftVersion("1.21")
    }
    named('test', Test) {
        useJUnitPlatform()
    }
}

def targetJavaVersion = 21
java {
    def javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    if (targetJavaVersion >= 10 || JavaVersion.current().isJava10Compatible()) {
        options.release.set(targetJavaVersion)
    }
}

processResources {
    def props = [version: version]
    inputs.properties props
    filteringCharset 'UTF-8'
    filesMatching('paper-plugin.yml') {
        expand props
    }
}
```

- [ ] **Step 2: Replace `src/main/resources/plugin.yml` with the following**

```yaml
name: GenPvP
version: '1.0'
main: me.revqz.genPvP.GenPvP
author: revqz
website: https://revqz.me
api-version: '1.21'
softdepend:
  - FastAsyncWorldEdit
  - PlaceholderAPI
commands:
  region:
    description: Region management
    usage: /region <pos1|pos2|define <name> <type>|bypass>
```

- [ ] **Step 3: Run `./gradlew dependencies` to confirm deps resolve**

Expected: BUILD SUCCESSFUL (MongoDB driver and PlaceholderAPI appear in output)

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/main/resources/plugin.yml
git commit -m "chore: add MongoDB, PlaceholderAPI, FAWE, and test dependencies"
```

---

## Task 2: Default Config

**Files:**
- Create: `src/main/resources/config.yml`

- [ ] **Step 1: Create `src/main/resources/config.yml`**

```yaml
# MongoDB connection URI for region persistence
mongodb-uri: "mongodb://localhost:27017"

# Seconds before a survival-placed block decays to air (default: 600 = 10 minutes)
block-timer-seconds: 600

# Bukkit Sound played when a block decays (must be a valid Sound enum value)
block-decay-sound: "BLOCK_SAND_BREAK"
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/config.yml
git commit -m "feat: add default config with mongodb-uri and block timer settings"
```

---

## Task 3: RegionRule Enum

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/flags/RegionRule.java`

- [ ] **Step 1: Create the file**

```java
package me.revqz.genPvP.protect.flags;

public enum RegionRule {
    ALLOW_DAMAGE,
    ALLOW_BREAK,
    ALLOW_PLACE,
    ALLOW_MOB_SPAWN,
    ALLOW_KNOCKBACK,
    ALLOW_FLINT_STEEL,
    ALLOW_INTERACT  // anvils, enchant tables, grindstones, crafting tables
}
```

- [ ] **Step 2: Run `./gradlew compileJava` to confirm it compiles**

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/flags/RegionRule.java
git commit -m "feat(protect): add RegionRule flag enum"
```

---

## Task 4: RegionType Enum + Tests

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/flags/RegionType.java`
- Create: `src/test/java/me/revqz/genPvP/protect/flags/RegionTypeTest.java`

- [ ] **Step 1: Create the test file**

```java
package me.revqz.genPvP.protect.flags;

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
        // Ensure no enum value was accidentally removed
        assertEquals(11, RegionType.values().length);
    }
}
```

- [ ] **Step 2: Run `./gradlew test` and confirm it fails**

Expected: compilation error — `RegionType` does not exist yet

- [ ] **Step 3: Create `src/main/java/me/revqz/genPvP/protect/flags/RegionType.java`**

```java
package me.revqz.genPvP.protect.flags;

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
```

- [ ] **Step 4: Run `./gradlew test` and confirm all tests pass**

Expected: BUILD SUCCESSFUL, 7 tests passing in `RegionTypeTest`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/flags/RegionType.java \
        src/test/java/me/revqz/genPvP/protect/flags/RegionTypeTest.java
git commit -m "feat(protect): add RegionType enum with hardcoded rule sets"
```

---

## Task 5: ProtectRegion + Tests

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/ProtectRegion.java`
- Create: `src/test/java/me/revqz/genPvP/protect/ProtectRegionTest.java`

- [ ] **Step 1: Create the test file**

```java
package me.revqz.genPvP.protect;

import me.revqz.genPvP.protect.flags.RegionRule;
import me.revqz.genPvP.protect.flags.RegionType;
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
```

- [ ] **Step 2: Run `./gradlew test` and confirm it fails**

Expected: compilation error — `ProtectRegion` does not exist yet

- [ ] **Step 3: Create `src/main/java/me/revqz/genPvP/protect/ProtectRegion.java`**

```java
package me.revqz.genPvP.protect;

import me.revqz.genPvP.protect.flags.RegionRule;
import me.revqz.genPvP.protect.flags.RegionType;
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
```

- [ ] **Step 4: Run `./gradlew test` and confirm all tests pass**

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/ProtectRegion.java \
        src/test/java/me/revqz/genPvP/protect/ProtectRegionTest.java
git commit -m "feat(protect): add ProtectRegion data class with contains() and hasRule()"
```

---

## Task 6: RegionManager (In-Memory) + Tests

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/RegionManager.java`
- Create: `src/test/java/me/revqz/genPvP/protect/RegionManagerTest.java`

- [ ] **Step 1: Create the test file**

```java
package me.revqz.genPvP.protect;

import me.revqz.genPvP.protect.flags.RegionType;
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
        manager = new RegionManager(); // no-arg constructor for tests
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
```

- [ ] **Step 2: Run `./gradlew test` and confirm it fails**

Expected: compilation error — `RegionManager` and its no-arg constructor don't exist

- [ ] **Step 3: Create `src/main/java/me/revqz/genPvP/protect/RegionManager.java`**

```java
package me.revqz.genPvP.protect;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import me.revqz.genPvP.protect.flags.RegionType;
import org.bson.Document;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class RegionManager {

    private final Map<String, ProtectRegion> regions = new HashMap<>();
    private MongoCollection<Document> collection;
    private boolean connected = false;

    /** Production constructor — connects to MongoDB and loads regions. */
    public RegionManager(JavaPlugin plugin) {
        String uri = plugin.getConfig().getString("mongodb-uri", "mongodb://localhost:27017");
        try {
            MongoClient client = MongoClients.create(uri);
            MongoDatabase db = client.getDatabase("genpvp");
            collection = db.getCollection("regions");
            connected = true;
            loadFromMongo();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to connect to MongoDB — region protection disabled", e);
        }
    }

    /** Test constructor — in-memory only, no MongoDB. */
    RegionManager() {}

    private void loadFromMongo() {
        for (Document doc : collection.find()) {
            try {
                RegionType type = RegionType.valueOf(doc.getString("type"));
                ProtectRegion region = new ProtectRegion(
                        doc.getString("name"), type, doc.getString("world"),
                        doc.getInteger("minX"), doc.getInteger("minY"), doc.getInteger("minZ"),
                        doc.getInteger("maxX"), doc.getInteger("maxY"), doc.getInteger("maxZ")
                );
                regions.put(region.getName().toLowerCase(), region);
            } catch (Exception ignored) {}
        }
    }

    public void defineRegion(String name, RegionType type, String world,
                              int x1, int y1, int z1, int x2, int y2, int z2) {
        ProtectRegion region = new ProtectRegion(name, type, world, x1, y1, z1, x2, y2, z2);
        regions.put(name.toLowerCase(), region);
        if (connected) {
            Document filter = new Document("name", name);
            Document doc = new Document("name", name)
                    .append("type", type.name())
                    .append("world", world)
                    .append("minX", region.getMinX()).append("minY", region.getMinY()).append("minZ", region.getMinZ())
                    .append("maxX", region.getMaxX()).append("maxY", region.getMaxY()).append("maxZ", region.getMaxZ());
            collection.replaceOne(filter, doc, new ReplaceOptions().upsert(true));
        }
    }

    public List<ProtectRegion> getRegionsAt(Location loc) {
        List<ProtectRegion> result = new ArrayList<>();
        for (ProtectRegion r : regions.values()) {
            if (r.contains(loc)) result.add(r);
        }
        return result;
    }

    public boolean isConnected() { return connected; }
}
```

- [ ] **Step 4: Run `./gradlew test` and confirm all tests pass**

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/RegionManager.java \
        src/test/java/me/revqz/genPvP/protect/RegionManagerTest.java
git commit -m "feat(protect): add RegionManager with in-memory store and MongoDB persistence"
```

---

## Task 7: BlockTimerManager

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/BlockTimerManager.java`

Note: this class requires a Bukkit scheduler — it is tested indirectly via `ProtectListenerTest` in Task 10. Manual server testing is needed to verify timer behaviour.

- [ ] **Step 1: Create `src/main/java/me/revqz/genPvP/protect/BlockTimerManager.java`**

```java
package me.revqz.genPvP.protect;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

public class BlockTimerManager {

    private final Map<Location, BukkitTask> activeTimers = new HashMap<>();
    private final JavaPlugin plugin;
    private final long timerTicks;
    private final Sound decaySound;

    public BlockTimerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        int seconds = plugin.getConfig().getInt("block-timer-seconds", 600);
        this.timerTicks = seconds * 20L;
        String soundName = plugin.getConfig().getString("block-decay-sound", "BLOCK_SAND_BREAK");
        Sound parsed;
        try {
            parsed = Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid block-decay-sound '" + soundName + "', using BLOCK_SAND_BREAK");
            parsed = Sound.BLOCK_SAND_BREAK;
        }
        this.decaySound = parsed;
    }

    public void trackBlock(Location loc) {
        Location key = loc.getBlock().getLocation();
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            key.getBlock().setType(Material.AIR);
            key.getWorld().playSound(key, decaySound, 1.0f, 1.0f);
            activeTimers.remove(key);
        }, timerTicks);
        activeTimers.put(key, task);
    }

    public void cancelTimer(Location loc) {
        BukkitTask task = activeTimers.remove(loc.getBlock().getLocation());
        if (task != null) task.cancel();
    }

    public void cancelAll() {
        activeTimers.values().forEach(BukkitTask::cancel);
        activeTimers.clear();
    }

    public boolean isTracking(Location loc) {
        return activeTimers.containsKey(loc.getBlock().getLocation());
    }
}
```

- [ ] **Step 2: Run `./gradlew compileJava` to confirm it compiles**

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/BlockTimerManager.java
git commit -m "feat(protect): add BlockTimerManager for survival block decay"
```

---

## Task 8: ProtectCommand

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/ProtectCommand.java`

- [ ] **Step 1: Create `src/main/java/me/revqz/genPvP/protect/ProtectCommand.java`**

```java
package me.revqz.genPvP.protect;

import me.revqz.genPvP.protect.flags.RegionType;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProtectCommand implements CommandExecutor {

    private final RegionManager regionManager;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Set<UUID> bypassing = new HashSet<>();

    public ProtectCommand(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("§cYou must be OP to use this command.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§eUsage: /region <pos1|pos2|define <name> <type>|bypass>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "pos1" -> {
                pos1.put(player.getUniqueId(), player.getLocation().getBlock().getLocation());
                player.sendMessage("§aPos1 set to " + formatLoc(player.getLocation()));
            }
            case "pos2" -> {
                pos2.put(player.getUniqueId(), player.getLocation().getBlock().getLocation());
                player.sendMessage("§aPos2 set to " + formatLoc(player.getLocation()));
            }
            case "define" -> {
                if (args.length < 3) {
                    player.sendMessage("§eUsage: /region define <name> <type>");
                    player.sendMessage("§eValid types: " + Arrays.toString(RegionType.values()));
                    return true;
                }
                Location p1 = pos1.get(player.getUniqueId());
                Location p2 = pos2.get(player.getUniqueId());
                if (p1 == null || p2 == null) {
                    player.sendMessage("§cSet both pos1 and pos2 first.");
                    return true;
                }
                RegionType type;
                try {
                    type = RegionType.valueOf(args[2].toUpperCase());
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cInvalid type. Valid types: " + Arrays.toString(RegionType.values()));
                    return true;
                }
                regionManager.defineRegion(args[1], type, p1.getWorld().getName(),
                        p1.getBlockX(), p1.getBlockY(), p1.getBlockZ(),
                        p2.getBlockX(), p2.getBlockY(), p2.getBlockZ());
                player.sendMessage("§aRegion '" + args[1] + "' defined as " + type.name() + ".");
            }
            case "bypass" -> {
                UUID uuid = player.getUniqueId();
                if (bypassing.remove(uuid)) {
                    player.sendMessage("§cRegion bypass disabled.");
                } else {
                    bypassing.add(uuid);
                    player.sendMessage("§aRegion bypass enabled.");
                }
            }
            default -> player.sendMessage("§eUsage: /region <pos1|pos2|define <name> <type>|bypass>");
        }
        return true;
    }

    public boolean isBypassing(Player player) {
        return bypassing.contains(player.getUniqueId());
    }

    private String formatLoc(Location loc) {
        return loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ();
    }
}
```

- [ ] **Step 2: Run `./gradlew compileJava` to confirm it compiles**

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/ProtectCommand.java
git commit -m "feat(protect): add /region command for pos1/pos2/define/bypass"
```

---

## Task 9: ProtectListener + Tests

**Files:**
- Create: `src/main/java/me/revqz/genPvP/protect/ProtectListener.java`
- Create: `src/test/java/me/revqz/genPvP/protect/ProtectListenerTest.java`

- [ ] **Step 1: Create the test file**

```java
package me.revqz.genPvP.protect;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.protect.flags.RegionType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtectListenerTest {

    private ServerMock server;
    private GenPvP plugin;
    private RegionManager regionManager;
    private BlockTimerManager blockTimerManager;
    private ProtectCommand protectCommand;
    private ProtectListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(GenPvP.class);
        regionManager = new RegionManager();              // in-memory, no MongoDB
        blockTimerManager = new BlockTimerManager(plugin); // use same instance injected into listener
        protectCommand = new ProtectCommand(regionManager);
        listener = new ProtectListener(regionManager, blockTimerManager, protectCommand);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // --- EntityDamageEvent ---

    @Test
    void playerTakesDamageOutsideAnyRegion_allowed() {
        PlayerMock player = server.addPlayer();
        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void playerTakesDamageInSpawnRegion_cancelled() {
        PlayerMock player = server.addPlayer();
        Location loc = player.getLocation();
        regionManager.defineRegion("spawn", RegionType.SPAWN, loc.getWorld().getName(),
                loc.getBlockX() - 5, loc.getBlockY() - 5, loc.getBlockZ() - 5,
                loc.getBlockX() + 5, loc.getBlockY() + 5, loc.getBlockZ() + 5);
        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void playerTakesDamageInOpMinesRegion_allowed() {
        PlayerMock player = server.addPlayer();
        Location loc = player.getLocation();
        regionManager.defineRegion("opmines", RegionType.OPMINES, loc.getWorld().getName(),
                loc.getBlockX() - 5, loc.getBlockY() - 5, loc.getBlockZ() - 5,
                loc.getBlockX() + 5, loc.getBlockY() + 5, loc.getBlockZ() + 5);
        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(event);
        assertFalse(event.isCancelled());
    }

    // --- BlockBreakEvent ---

    @Test
    void blockBreakInGensRegion_allowed() {
        PlayerMock player = server.addPlayer();
        Location loc = player.getLocation();
        regionManager.defineRegion("gens", RegionType.GENS, loc.getWorld().getName(),
                loc.getBlockX() - 5, loc.getBlockY() - 5, loc.getBlockZ() - 5,
                loc.getBlockX() + 5, loc.getBlockY() + 5, loc.getBlockZ() + 5);
        Block block = loc.getBlock();
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void blockBreakInSpawnRegion_cancelled() {
        PlayerMock player = server.addPlayer();
        Location loc = player.getLocation();
        regionManager.defineRegion("spawn", RegionType.SPAWN, loc.getWorld().getName(),
                loc.getBlockX() - 5, loc.getBlockY() - 5, loc.getBlockZ() - 5,
                loc.getBlockX() + 5, loc.getBlockY() + 5, loc.getBlockZ() + 5);
        Block block = loc.getBlock();
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        listener.onBlockBreak(event);
        assertTrue(event.isCancelled());
    }

    // --- BlockPlaceEvent in SURVIVAL starts block timer ---

    @Test
    void survivalBlockPlace_outsideRegion_startsTimer() {
        PlayerMock player = server.addPlayer();
        player.setGameMode(GameMode.SURVIVAL);
        Location loc = player.getLocation();
        Block block = loc.getBlock();
        block.setType(Material.STONE);
        BlockPlaceEvent event = new BlockPlaceEvent(block, block.getState(), block,
                new org.bukkit.inventory.ItemStack(Material.STONE), player, true,
                org.bukkit.inventory.EquipmentSlot.HAND);
        listener.onBlockPlace(event);
        assertFalse(event.isCancelled());
        assertTrue(blockTimerManager.isTracking(loc));
    }

    // --- Bypass skips region rules ---

    @Test
    void bypassedPlayerTakesDamageInSpawn_allowed() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        Location loc = player.getLocation();
        regionManager.defineRegion("spawn", RegionType.SPAWN, loc.getWorld().getName(),
                loc.getBlockX() - 5, loc.getBlockY() - 5, loc.getBlockZ() - 5,
                loc.getBlockX() + 5, loc.getBlockY() + 5, loc.getBlockZ() + 5);
        // Toggle bypass on via command
        protectCommand.toggleBypass(player);
        EntityDamageEvent event = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5.0);
        listener.onEntityDamage(event);
        assertFalse(event.isCancelled());
    }
}
```

Note: `blockTimerManager` is accessed via `plugin.getBlockTimerManager()` — expose it in `GenPvP.java` in Task 10. Also, `protectCommand.toggleBypass(Player)` is a helper extracted from the switch block — add it in the next step.

- [ ] **Step 2: Add `toggleBypass` helper to `ProtectCommand.java`**

Open `src/main/java/me/revqz/genPvP/protect/ProtectCommand.java` and add after `isBypassing()`:

```java
/** Exposed for testing — same logic as the bypass switch case. */
public void toggleBypass(Player player) {
    UUID uuid = player.getUniqueId();
    if (!bypassing.remove(uuid)) bypassing.add(uuid);
}
```

And replace the `case "bypass"` body with:

```java
case "bypass" -> {
    toggleBypass(player);
    player.sendMessage(isBypassing(player) ? "§aRegion bypass enabled." : "§cRegion bypass disabled.");
}
```

- [ ] **Step 3: Create `src/main/java/me/revqz/genPvP/protect/ProtectListener.java`**

```java
package me.revqz.genPvP.protect;

import me.revqz.genPvP.protect.flags.RegionRule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProtectListener implements Listener {

    private static final Set<Material> INTERACT_BLOCKS = Set.of(
            Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL,
            Material.ENCHANTING_TABLE, Material.GRINDSTONE, Material.CRAFTING_TABLE
    );

    private final RegionManager regionManager;
    private final BlockTimerManager blockTimerManager;
    private final ProtectCommand protectCommand;
    private final Set<Location> creativePlacedBlocks = new HashSet<>();

    public ProtectListener(RegionManager regionManager, BlockTimerManager blockTimerManager,
                           ProtectCommand protectCommand) {
        this.regionManager = regionManager;
        this.blockTimerManager = blockTimerManager;
        this.protectCommand = protectCommand;
    }

    /**
     * Returns true if the given location is inside a region that denies the rule.
     * @param bypassPlayer the player to check for bypass, or null if N/A
     */
    private boolean isRegionDenying(Location loc, Player bypassPlayer, RegionRule rule) {
        if (bypassPlayer != null && protectCommand.isBypassing(bypassPlayer)) return false;
        List<ProtectRegion> regions = regionManager.getRegionsAt(loc);
        if (regions.isEmpty()) return false;
        return regions.stream().anyMatch(r -> !r.hasRule(rule));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        Player bypassCheck = entity instanceof Player p ? p : null;
        if (isRegionDenying(entity.getLocation(), bypassCheck, RegionRule.ALLOW_DAMAGE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();

        // Global rule: creative-placed blocks cannot be broken unless in an ALLOW_BREAK region
        if (creativePlacedBlocks.contains(blockLoc) && !protectCommand.isBypassing(player)) {
            boolean inAllowBreakRegion = regionManager.getRegionsAt(blockLoc)
                    .stream().anyMatch(r -> r.hasRule(RegionRule.ALLOW_BREAK));
            if (!inAllowBreakRegion) {
                event.setCancelled(true);
                return;
            }
        }

        if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_BREAK)) {
            event.setCancelled(true);
            return;
        }

        // Block was allowed to break — clean up tracking
        blockTimerManager.cancelTimer(blockLoc);
        creativePlacedBlocks.remove(blockLoc);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Location blockLoc = event.getBlock().getLocation();

        if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_PLACE)) {
            event.setCancelled(true);
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE) {
            creativePlacedBlocks.add(blockLoc);
        } else if (player.getGameMode() == GameMode.SURVIVAL) {
            blockTimerManager.trackBlock(blockLoc);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntity() instanceof Player) return;
        if (isRegionDenying(event.getLocation(), null, RegionRule.ALLOW_MOB_SPAWN)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKnockback(EntityKnockbackEvent event) {
        Entity entity = event.getEntity();
        Player bypassCheck = entity instanceof Player p ? p : null;
        if (isRegionDenying(entity.getLocation(), bypassCheck, RegionRule.ALLOW_KNOCKBACK)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();

        // Flint and steel
        if (event.getMaterial() == Material.FLINT_AND_STEEL) {
            if (isRegionDenying(player.getLocation(), player, RegionRule.ALLOW_FLINT_STEEL)) {
                event.setCancelled(true);
                return;
            }
        }

        // Interactive blocks (anvil, enchant table, grindstone, crafting table)
        if (event.hasBlock() && INTERACT_BLOCKS.contains(event.getClickedBlock().getType())) {
            Location blockLoc = event.getClickedBlock().getLocation();
            if (isRegionDenying(blockLoc, player, RegionRule.ALLOW_INTERACT)) {
                event.setCancelled(true);
            }
        }
    }
}
```

- [ ] **Step 4: Run `./gradlew test` and confirm all tests pass**

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/me/revqz/genPvP/protect/ProtectListener.java \
        src/main/java/me/revqz/genPvP/protect/ProtectCommand.java \
        src/test/java/me/revqz/genPvP/protect/ProtectListenerTest.java
git commit -m "feat(protect): add ProtectListener with full region rule and block timer enforcement"
```

---

## Task 10: Wire Up GenPvP.java

**Files:**
- Modify: `src/main/java/me/revqz/genPvP/GenPvP.java`

- [ ] **Step 1: Replace `src/main/java/me/revqz/genPvP/GenPvP.java` with**

```java
package me.revqz.genPvP;

import me.revqz.genPvP.protect.BlockTimerManager;
import me.revqz.genPvP.protect.ProtectCommand;
import me.revqz.genPvP.protect.ProtectListener;
import me.revqz.genPvP.protect.RegionManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class GenPvP extends JavaPlugin {

    private BlockTimerManager blockTimerManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegionManager regionManager = new RegionManager(this);
        blockTimerManager = new BlockTimerManager(this);
        ProtectCommand protectCommand = new ProtectCommand(regionManager);

        var regionCmd = getCommand("region");
        if (regionCmd != null) regionCmd.setExecutor(protectCommand);
        getServer().getPluginManager().registerEvents(
                new ProtectListener(regionManager, blockTimerManager, protectCommand), this
        );
    }

    @Override
    public void onDisable() {
        if (blockTimerManager != null) {
            blockTimerManager.cancelAll();
        }
    }

}
```

- [ ] **Step 2: Run `./gradlew test` to confirm all tests still pass**

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run `./gradlew build` to confirm the jar builds**

Expected: BUILD SUCCESSFUL, jar in `build/libs/`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/me/revqz/genPvP/GenPvP.java
git commit -m "feat(protect): wire up Protect system in GenPvP onEnable/onDisable"
```

---

## Task 11: Manual Server Smoke Test

Run the server via `./gradlew runServer` and verify the following manually:

- [ ] Server starts without errors in console
- [ ] `/region pos1` and `/region pos2` set positions (requires OP)
- [ ] `/region define spawn SPAWN` creates a region (check console for MongoDB error if not connected — acceptable)
- [ ] Standing in the spawn region: taking damage from another player is blocked
- [ ] Standing in the spawn region: placing a block is blocked
- [ ] `/region bypass` toggles bypass — OP can then take damage in spawn region
- [ ] Placing a stone block in survival outside any region → block disappears after `block-timer-seconds` seconds
- [ ] Breaking that survival block early cancels the timer (block does not disappear after the timer elapses)
