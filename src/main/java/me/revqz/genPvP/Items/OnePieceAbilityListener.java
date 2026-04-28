package me.revqz.genPvP.Items;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Handles active abilities for OnePiece custom weapons:
 *
 *  luffy_sword  — Swipe: right-click hits all enemies in a 90° arc (±45°), 4-block
 *                 range. Each target takes the player's attack damage × 1.10 (+10%).
 *                 Cooldown: 2 s.
 *
 *  pirate_axe   — Throw & Return: right-click removes the axe from the hand and
 *                 fires an Arrow with explicit velocity + gravity dealing 2.5 hearts.
 *                 After it hits (or 4 s timeout) the axe returns to inventory.
 *                 Cooldown: 5 s.
 *
 *  pirate_sword — Ghost Crew: right-click summons 2 skeletons flanking the player.
 *                 They are fire-immune (never burn in sunlight).
 *                 They target nearby enemies but NEVER attack the summoner or
 *                 players in SPAWN / GENS regions.
 *                 Each skeleton has a TextDisplay hologram: configurable name lines
 *                 (onepiece.ghost-crew.name-lines in config.yml) plus a live HP bar.
 *                 Skeletons despawn after 20 s. Cooldown: 30 s.
 *
 *  All abilities blocked in SPAWN and GENS region types.
 */
public class OnePieceAbilityListener implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    // ── Metadata keys ─────────────────────────────────────────────────────────
    private static final String META_PIRATE_AXE = "genpvp_pirate_axe";
    private static final String META_GHOST_CREW = "genpvp_ghost_crew";

    /** IDs that have active right-click abilities. Armor pieces are intentionally excluded. */
    private static final Set<String> WEAPON_IDS = Set.of("luffy_sword", "pirate_axe", "pirate_sword");

    // ── Ability timings ───────────────────────────────────────────────────────
    private static final long   SWORD_COOLDOWN_MS        = 2_000;
    private static final long   AXE_COOLDOWN_MS          = 5_000;
    private static final long   PIRATE_SWORD_COOLDOWN_MS = 30_000;

    private static final double SWIPE_RANGE_BLOCKS = 4.0;
    private static final double SWIPE_HALF_ANGLE   = 45.0;
    private static final double SWIPE_DAMAGE_BONUS = 1.10;

    private static final double AXE_DAMAGE       = 5.0;
    private static final long   AXE_RETURN_TICKS = 30L;
    private static final long   AXE_TIMEOUT_TICKS = 80L;

    private static final long   GHOST_DURATION_TICKS = 400L;
    private static final double GHOST_SIDE_OFFSET    = 1.5;
    private static final double GHOST_TARGET_RADIUS  = 20.0;

    // TextDisplay hologram scale (configurable in source; user requested 0.3, 3.0, 0.3)
    private static final Vector3f DISPLAY_SCALE = new Vector3f(0.3f, 3.0f, 0.3f);
    // Vertical gap between hologram lines (blocks). Larger than normal due to Y scale 3.
    private static final double   LINE_GAP      = 0.6;
    // Height above skeleton feet for the HP bar
    private static final double   HP_BAR_Y      = 2.1;

    // ── State ─────────────────────────────────────────────────────────────────
    private final GenPvP             plugin;
    private final CustomItemRegistry registry;
    private final RegionManager      regionManager;

    private final Map<UUID, Long>      swordCd       = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      axeCd         = new ConcurrentHashMap<>();
    private final Map<UUID, Long>      pirateSwordCd = new ConcurrentHashMap<>();

    private final Map<UUID, ItemStack> pendingAxes  = new ConcurrentHashMap<>();
    private final Map<UUID, UUID>      axeArrows    = new ConcurrentHashMap<>();

    /** skeleton UUID → owner player UUID */
    private final Map<UUID, UUID> ghostSkeletons = new ConcurrentHashMap<>();
    /** skeleton UUID → HP-bar TextDisplay */
    private final Map<UUID, TextDisplay> ghostHpBars = new ConcurrentHashMap<>();
    /** skeleton UUID → ordered list of name-line TextDisplays (top → bottom) */
    private final Map<UUID, List<TextDisplay>> ghostNameDisplays = new ConcurrentHashMap<>();

    private final BukkitTask hologramTask;

    public OnePieceAbilityListener(GenPvP plugin, CustomItemRegistry registry, RegionManager regionManager) {
        this.plugin        = plugin;
        this.registry      = registry;
        this.regionManager = regionManager;

        // Tick every 4 ticks (0.2 s): teleport holograms to follow skeletons + refresh HP bar
        this.hologramTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tickHolograms, 4L, 4L);
    }

    // ── Region guard ──────────────────────────────────────────────────────────

    private boolean isRestrictedZone(Location loc) {
        for (ProtectRegion r : regionManager.getRegionsAt(loc)) {
            RegionType t = r.getType();
            if (t == RegionType.SPAWN || t == RegionType.GENS) return true;
        }
        return false;
    }

    // ── Right-click dispatch ──────────────────────────────────────────────────

    /**
     * LOW priority, ignoreCancelled=false so we catch RIGHT_CLICK_AIR reliably
     * on Paper 1.21 (non-usable items don't always fire that action at higher
     * priorities). We cancel the event for weapon items to prevent block
     * interactions from also firing.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        String id = registry.getItemId(player.getInventory().getItemInMainHand());
        if (id == null) return;

        // Armor pieces have no ability — let Minecraft handle them normally (equip on right-click)
        if (!WEAPON_IDS.contains(id)) return;

        // Prevent block interaction (opening chests, doors, etc.) for weapon items
        event.setCancelled(true);

        if (isRestrictedZone(player.getLocation())) {
            player.sendActionBar(LEGACY.deserialize(ColorUtil.colorize("&c[OnePiece] Abilities cannot be used in this area.")));
            return;
        }

        switch (id) {
            case "luffy_sword"  -> swipe(player);
            case "pirate_axe"   -> throwAxe(player);
            case "pirate_sword" -> ghostCrew(player);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LUFFY SWORD — SWIPE
    // ══════════════════════════════════════════════════════════════════════════

    private void swipe(Player player) {
        if (checkCooldown(player, swordCd, SWORD_COOLDOWN_MS)) return;
        setCooldown(player, swordCd, SWORD_COOLDOWN_MS);

        double baseDmg  = getAttackDamage(player);
        double swipeDmg = baseDmg * SWIPE_DAMAGE_BONUS;

        Location origin     = player.getEyeLocation();
        Vector   facing     = origin.getDirection();
        Vector   flatFacing = new Vector(facing.getX(), 0, facing.getZ());
        if (flatFacing.lengthSquared() > 0) flatFacing.normalize();
        double cosHalf = Math.cos(Math.toRadians(SWIPE_HALF_ANGLE));

        int hits = 0;
        for (Entity entity : player.getNearbyEntities(SWIPE_RANGE_BLOCKS, SWIPE_RANGE_BLOCKS, SWIPE_RANGE_BLOCKS)) {
            if (entity.equals(player)) continue;
            if (!(entity instanceof LivingEntity target)) continue;

            Vector toTarget = entity.getLocation().toVector().subtract(player.getLocation().toVector());
            Vector flatTo   = new Vector(toTarget.getX(), 0, toTarget.getZ());
            if (flatTo.lengthSquared() == 0) continue;
            flatTo.normalize();
            if (flatTo.dot(flatFacing) < cosHalf) continue;

            target.damage(swipeDmg, player);
            hits++;
            entity.getWorld().spawnParticle(Particle.SWEEP_ATTACK, entity.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
        }

        spawnArcParticles(player.getLocation().add(0, 1, 0), flatFacing, SWIPE_RANGE_BLOCKS);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1f);

        player.sendMessage(ColorUtil.colorize(hits > 0
                ? "&6[⚔ Luffy Sword] &fSwipe struck &e" + hits + " &ftarget" + (hits == 1 ? "" : "s") + "!"
                : "&7[⚔ Luffy Sword] &fSwipe missed..."));
    }

    private void spawnArcParticles(Location origin, Vector flatFacing, double range) {
        World world = origin.getWorld();
        if (world == null) return;
        double step  = Math.toRadians(15);
        double start = Math.toRadians(-SWIPE_HALF_ANGLE);
        double end   = Math.toRadians( SWIPE_HALF_ANGLE);
        for (double a = start; a <= end; a += step) {
            double cos = Math.cos(a), sin = Math.sin(a);
            double rx  = flatFacing.getX() * cos - flatFacing.getZ() * sin;
            double rz  = flatFacing.getX() * sin + flatFacing.getZ() * cos;
            Location p = origin.clone().add(new Vector(rx, 0, rz).multiply(range * 0.6));
            world.spawnParticle(Particle.SWEEP_ATTACK, p, 1, 0, 0, 0, 0);
        }
    }

    private double getAttackDamage(Player player) {
        var attr = player.getAttribute(Attribute.ATTACK_DAMAGE);
        return attr != null ? attr.getValue() : 7.0;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PIRATE AXE — THROW & RETURN
    // ══════════════════════════════════════════════════════════════════════════

    private void throwAxe(Player player) {
        if (checkCooldown(player, axeCd, AXE_COOLDOWN_MS)) return;
        if (pendingAxes.containsKey(player.getUniqueId())) {
            player.sendMessage(ColorUtil.colorize("&c[Pirate Axe] &fYour axe is still in flight!"));
            return;
        }
        setCooldown(player, axeCd, AXE_COOLDOWN_MS);

        ItemStack axe = player.getInventory().getItemInMainHand().clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        player.updateInventory();
        pendingAxes.put(player.getUniqueId(), axe);

        // Spawn at eye position, explicit velocity in look direction — works mid-air
        Vector   throwDir = player.getEyeLocation().getDirection().normalize();
        Location spawnLoc = player.getEyeLocation().add(throwDir.clone().multiply(0.5));

        Arrow arrow = player.getWorld().spawn(spawnLoc, Arrow.class, a -> {
            a.setShooter(player);
            a.setVelocity(throwDir.multiply(1.8));
            a.setGravity(true);
            a.setDamage(AXE_DAMAGE);
            a.setCritical(false);
            a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        });
        arrow.setMetadata(META_PIRATE_AXE, new FixedMetadataValue(plugin, player.getUniqueId().toString()));
        axeArrows.put(arrow.getUniqueId(), player.getUniqueId());

        BukkitTask[] trail = new BukkitTask[1];
        trail[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (arrow.isDead() || !arrow.isValid()) { trail[0].cancel(); return; }
            arrow.getWorld().spawnParticle(Particle.CRIT, arrow.getLocation(), 3, 0.05, 0.05, 0.05, 0);
        }, 0L, 2L);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (axeArrows.remove(arrow.getUniqueId()) != null) {
                trail[0].cancel();
                arrow.remove();
                returnAxe(player.getUniqueId());
            }
        }, AXE_TIMEOUT_TICKS);

        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 1f);
        player.sendMessage(ColorUtil.colorize("&e[Pirate Axe] &fAxe thrown!"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAxeHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(META_PIRATE_AXE)) return;

        UUID ownerUUID = axeArrows.remove(arrow.getUniqueId());
        if (ownerUUID == null) return;

        Location hit = arrow.getLocation();
        hit.getWorld().spawnParticle(Particle.CRIT, hit, 12, 0.2, 0.2, 0.2, 0);

        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner != null && owner.isOnline()) {
            owner.playSound(owner.getLocation(), Sound.ITEM_TRIDENT_HIT, 1f, 0.9f);
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            arrow.remove();
            returnAxe(ownerUUID);
        }, AXE_RETURN_TICKS);
    }

    private void returnAxe(UUID ownerUUID) {
        ItemStack axe = pendingAxes.remove(ownerUUID);
        if (axe == null) return;
        Player player = Bukkit.getPlayer(ownerUUID);
        if (player == null || !player.isOnline()) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(axe);
        overflow.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        player.updateInventory();
        player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1f, 1.2f);
        player.sendMessage(ColorUtil.colorize("&a[Pirate Axe] &fAxe returned!"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  PIRATE SWORD — GHOST CREW
    // ══════════════════════════════════════════════════════════════════════════

    private void ghostCrew(Player player) {
        if (checkCooldown(player, pirateSwordCd, PIRATE_SWORD_COOLDOWN_MS)) return;
        setCooldown(player, pirateSwordCd, PIRATE_SWORD_COOLDOWN_MS);

        Location loc     = player.getLocation();
        Vector   forward = new Vector(loc.getDirection().getX(), 0, loc.getDirection().getZ()).normalize();
        Vector   right   = new Vector(-forward.getZ(), 0, forward.getX()).normalize();

        Skeleton skR = spawnGhost(loc.clone().add(right.clone().multiply( GHOST_SIDE_OFFSET)), player);
        Skeleton skL = spawnGhost(loc.clone().add(right.clone().multiply(-GHOST_SIDE_OFFSET)), player);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            removeGhost(skR);
            removeGhost(skL);
        }, GHOST_DURATION_TICKS);

        player.playSound(loc, Sound.ENTITY_SKELETON_AMBIENT, 1f, 0.7f);
        player.sendMessage(ColorUtil.colorize("&5[Pirate Sword] &fGhost crew summoned for &d"
                + (GHOST_DURATION_TICKS / 20) + "s&f!"));
    }

    private Skeleton spawnGhost(Location loc, Player owner) {
        Skeleton sk = (Skeleton) loc.getWorld().spawnEntity(loc, EntityType.SKELETON);
        sk.setMetadata(META_GHOST_CREW, new FixedMetadataValue(plugin, owner.getUniqueId().toString()));

        // Fire immunity — skeletons must not burn in sunlight
        sk.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                Integer.MAX_VALUE, 0, false, false, false));

        sk.setCustomNameVisible(false); // name shown via TextDisplay instead
        sk.setCanPickupItems(false);
        ghostSkeletons.put(sk.getUniqueId(), owner.getUniqueId());

        // Spawn TextDisplay holograms
        spawnHolograms(sk);

        assignBestTarget(sk, owner);
        return sk;
    }

    // ── Hologram spawning ─────────────────────────────────────────────────────

    /**
     * Spawns one TextDisplay per configured name line plus one HP-bar display.
     * The hologram tick task keeps them positioned above the skeleton.
     */
    private void spawnHolograms(Skeleton sk) {
        List<String> nameLines = plugin.getConfig()
                .getStringList("onepiece.ghost-crew.name-lines");
        if (nameLines.isEmpty()) nameLines = List.of("&5☠ &fGhost Crew");

        Location base = sk.getLocation();
        List<TextDisplay> nameDisplays = new ArrayList<>();

        // Name lines — stacked from HP_BAR_Y upward; first config line = topmost
        for (int i = 0; i < nameLines.size(); i++) {
            double y = HP_BAR_Y + LINE_GAP * (nameLines.size() - i); // top line highest
            TextDisplay d = spawnTextDisplay(base.clone().add(0, y, 0),
                    ColorUtil.colorize(nameLines.get(i)));
            nameDisplays.add(d);
        }
        ghostNameDisplays.put(sk.getUniqueId(), nameDisplays);

        // HP bar — always at the bottom (closest to head)
        TextDisplay hpBar = spawnTextDisplay(base.clone().add(0, HP_BAR_Y, 0), "");
        ghostHpBars.put(sk.getUniqueId(), hpBar);

        // Initialise HP bar text immediately
        double max = getMaxHealth(sk);
        refreshHpBar(hpBar, sk.getHealth(), max);
    }

    private TextDisplay spawnTextDisplay(Location loc, String coloredText) {
        return loc.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.text(LEGACY.deserialize(coloredText));
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),   // no translation offset
                    new Quaternionf(),            // no rotation
                    DISPLAY_SCALE,               // (0.3, 3.0, 0.3) as requested
                    new Quaternionf()
            ));
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)); // transparent background
            d.setShadowed(true);
            d.setSeeThrough(false);
        });
    }

    // ── Hologram tick ─────────────────────────────────────────────────────────

    /**
     * Runs every 4 ticks: teleports hologram entities to track their skeleton,
     * and refreshes the HP bar text.
     */
    private void tickHolograms() {
        for (UUID skUUID : new HashSet<>(ghostSkeletons.keySet())) {
            Entity e = Bukkit.getEntity(skUUID);
            if (!(e instanceof Skeleton sk) || sk.isDead()) {
                cleanupHolograms(skUUID);
                ghostSkeletons.remove(skUUID);
                continue;
            }

            Location base = sk.getLocation();

            // Reposition name lines
            List<TextDisplay> names = ghostNameDisplays.get(skUUID);
            if (names != null) {
                for (int i = 0; i < names.size(); i++) {
                    double y = HP_BAR_Y + LINE_GAP * (names.size() - i);
                    names.get(i).teleport(base.clone().add(0, y, 0));
                }
            }

            // Reposition + refresh HP bar
            TextDisplay hpBar = ghostHpBars.get(skUUID);
            if (hpBar != null) {
                hpBar.teleport(base.clone().add(0, HP_BAR_Y, 0));
                refreshHpBar(hpBar, sk.getHealth(), getMaxHealth(sk));
            }
        }
    }

    /**
     * Renders an HP bar using strikethrough spaces:
     * green segments for remaining HP, red for lost HP.
     *
     * Format:  &a&m(green spaces)&r&c&m(red spaces)
     */
    private void refreshHpBar(TextDisplay display, double current, double max) {
        int segments = plugin.getConfig().getInt("onepiece.ghost-crew.hp-bar-segments", 20);
        int green = (int) Math.round((current / max) * segments);
        int red   = segments - green;

        StringBuilder bar = new StringBuilder();
        if (green > 0) bar.append("&a&m").append(" ".repeat(green));
        if (red   > 0) bar.append("&r&c&m").append(" ".repeat(red));

        display.text(LEGACY.deserialize(ColorUtil.colorize(bar.toString())));
    }

    private double getMaxHealth(Skeleton sk) {
        var attr = sk.getAttribute(Attribute.MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    private void cleanupHolograms(UUID skUUID) {
        TextDisplay hp = ghostHpBars.remove(skUUID);
        if (hp != null && !hp.isDead()) hp.remove();

        List<TextDisplay> names = ghostNameDisplays.remove(skUUID);
        if (names != null) names.forEach(d -> { if (!d.isDead()) d.remove(); });
    }

    private void removeGhost(Skeleton sk) {
        UUID uuid = sk.getUniqueId();
        ghostSkeletons.remove(uuid);
        cleanupHolograms(uuid);
        if (!sk.isDead()) sk.remove();
    }

    @EventHandler
    public void onGhostDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Skeleton sk)) return;
        if (!sk.hasMetadata(META_GHOST_CREW)) return;
        UUID uuid = sk.getUniqueId();
        ghostSkeletons.remove(uuid);
        cleanupHolograms(uuid);
    }

    /** Clean up a disconnected player's ghosts so they don't linger headless. */
    @EventHandler
    public void onOwnerQuit(PlayerQuitEvent event) {
        UUID ownerUUID = event.getPlayer().getUniqueId();
        for (Map.Entry<UUID, UUID> entry : new HashMap<>(ghostSkeletons).entrySet()) {
            if (!entry.getValue().equals(ownerUUID)) continue;
            UUID skUUID = entry.getKey();
            Entity e = Bukkit.getEntity(skUUID);
            if (e instanceof Skeleton sk) removeGhost(sk);
            else {
                ghostSkeletons.remove(skUUID);
                cleanupHolograms(skUUID);
            }
        }
    }

    // ── Target guard ──────────────────────────────────────────────────────────

    private void assignBestTarget(Skeleton skeleton, Player owner) {
        double radiusSq = GHOST_TARGET_RADIUS * GHOST_TARGET_RADIUS;
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(owner)) continue;
            if (isRestrictedZone(candidate.getLocation())) continue;
            double dist = candidate.getLocation().distanceSquared(skeleton.getLocation());
            if (dist < radiusSq && dist < bestDist) { bestDist = dist; best = candidate; }
        }
        if (best != null) skeleton.setTarget(best);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onGhostTarget(EntityTargetEvent event) {
        if (!(event.getEntity() instanceof Skeleton sk)) return;
        if (!sk.hasMetadata(META_GHOST_CREW)) return;
        Entity target = event.getTarget();
        if (target == null) return;
        String ownerStr = sk.getMetadata(META_GHOST_CREW).get(0).asString();
        if (target instanceof Player p && p.getUniqueId().toString().equals(ownerStr)) {
            event.setCancelled(true);
            return;
        }
        if (target instanceof Player p && isRestrictedZone(p.getLocation())) {
            event.setCancelled(true);
        }
    }

    // ── Cooldown helpers ──────────────────────────────────────────────────────

    private boolean checkCooldown(Player player, Map<UUID, Long> map, long durationMs) {
        Long expiry = map.get(player.getUniqueId());
        if (expiry == null || System.currentTimeMillis() >= expiry) return false;
        long remaining = (expiry - System.currentTimeMillis() + 999) / 1000;
        player.sendMessage(ColorUtil.colorize("&c[OnePiece] Cooldown: &f" + remaining + "s remaining."));
        return true;
    }

    private void setCooldown(Player player, Map<UUID, Long> map, long durationMs) {
        map.put(player.getUniqueId(), System.currentTimeMillis() + durationMs);
    }
}
