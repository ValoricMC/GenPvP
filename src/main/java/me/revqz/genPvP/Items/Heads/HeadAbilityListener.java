package me.revqz.genPvP.Items.Heads;

import me.revqz.genPvP.Database.LogManager;
import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Items.CustomItemRegistry;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HeadAbilityListener implements Listener {

    private static final double FEATHER_FALLING_MULT = 0.52;
    private static final long   SMOKE_DURATION_MS    = 5_000L;
    private static final long   IFRIT_DURATION_MS    = 10_000L;
    private static final long   IFRIT_FIRE_MS        = 5_000L;
    private static final long   ABILITY_COOLDOWN_MS  = 120_000L;

    private final GenPvP             plugin;
    private final CustomItemRegistry registry;
    private final RegionManager      regionManager;
    private final LogManager         logManager;
    private final NamespacedKey      namiSpeedKey;

    // playerUUID → (abilityKey → cooldown-end epoch ms)
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    // Sanji double-jump tracking
    private final Set<UUID>        sanji_flight       = ConcurrentHashMap.newKeySet();
    private final Set<UUID>        sanji_airborne     = ConcurrentHashMap.newKeySet(); // became airborne after onJump
    private final Map<UUID, Vector> sanji_lastHorizVel = new ConcurrentHashMap<>();   // cached XZ velocity for double-jump

    // Nami smoke spheres
    private record SmokeEntry(UUID deployer, long expiryMs) {}
    private final Map<Location, SmokeEntry> smokeSpheres = new ConcurrentHashMap<>();

    // Nami snowball tracking: snowball UUID → shooter UUID
    private final Map<UUID, UUID> namiSnowballs = new ConcurrentHashMap<>();

    // Ifrit Jambe: attacker UUID → ability-end epoch ms
    private final Map<UUID, Long> ifritActive = new ConcurrentHashMap<>();

    // Ifrit fire on victim: victim UUID → fire-end epoch ms
    private final Map<UUID, Long> ifritFire = new ConcurrentHashMap<>();

    public HeadAbilityListener(GenPvP plugin, CustomItemRegistry registry,
                               RegionManager regionManager, LogManager logManager) {
        this.plugin        = plugin;
        this.registry      = registry;
        this.regionManager = regionManager;
        this.logManager    = logManager;
        this.namiSpeedKey  = new NamespacedKey(plugin, "nami_speed_boost");

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickPassives,    20L, 40L);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickSmokeSpheres, 10L, 10L);
    }

    // ── Message helpers ───────────────────────────────────────────────────────

    private String msg(String key) {
        return ColorUtil.colorize(plugin.getConfig().getString("heads.messages." + key,
                "&cMessage not configured: heads.messages." + key));
    }

    private String msg(String key, String... pairs) {
        String raw = plugin.getConfig().getString("heads.messages." + key,
                "&cMessage not configured: heads.messages." + key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            raw = raw.replace(pairs[i], pairs[i + 1]);
        }
        return ColorUtil.colorize(raw);
    }

    // ── Region helpers ────────────────────────────────────────────────────────

    private boolean isRestrictedZone(Location loc) {
        return regionManager.insideAnyType(loc, RegionType.SPAWN, RegionType.GENS);
    }

    private boolean trajectoryEntersSpawn(Location from, Vector dir) {
        Vector step = dir.clone().normalize();
        for (int t = 1; t <= 8; t++) {
            if (regionManager.insideAnyType(
                    from.clone().add(step.clone().multiply(t)),
                    RegionType.SPAWN, RegionType.SPAWN)) return true;
        }
        return false;
    }

    // ── Item helpers ──────────────────────────────────────────────────────────

    private boolean isWearing(Player player, String id) {
        return registry.hasId(player.getInventory().getHelmet(), id);
    }

    private boolean isOnCooldown(UUID uuid, String ability) {
        Map<String, Long> pc = cooldowns.get(uuid);
        if (pc == null) return false;
        Long end = pc.get(ability);
        return end != null && System.currentTimeMillis() < end;
    }

    private double cooldownLeft(UUID uuid, String ability) {
        Map<String, Long> pc = cooldowns.get(uuid);
        if (pc == null) return 0;
        Long end = pc.get(ability);
        if (end == null) return 0;
        long left = end - System.currentTimeMillis();
        return left > 0 ? left / 1000.0 : 0;
    }

    private void setCooldown(UUID uuid, String ability) {
        cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                 .put(ability, System.currentTimeMillis() + ABILITY_COOLDOWN_MS);
    }

    private boolean sendCooldownMsg(Player player, String ability) {
        if (!isOnCooldown(player.getUniqueId(), ability)) return false;
        player.sendMessage(msg("cooldown", "%seconds%",
                String.format("%.1f", cooldownLeft(player.getUniqueId(), ability))));
        return true;
    }

    private boolean isSword(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (item.getType().name().endsWith("_SWORD")) return true;
        String id = registry.getItemId(item);
        return id != null && id.contains("sword");
    }

    // ── Passive tick ──────────────────────────────────────────────────────────

    private void tickPassives() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean luffy = isWearing(player, "luffy_head");
            boolean zoro  = isWearing(player, "zoro_head");
            boolean nami  = isWearing(player, "nami_head");

            if (luffy) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,    80, 1, false, false, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE,    80, 1, false, false, true));
            }
            if (zoro) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 80, 1, false, false, true));
            }
            updateNamiSpeed(player, nami);
        }
    }

    private void updateNamiSpeed(Player player, boolean wearing) {
        AttributeInstance attr = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr == null) return;
        attr.removeModifier(namiSpeedKey);
        if (wearing) {
            attr.addModifier(new AttributeModifier(
                    namiSpeedKey, 0.05,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    EquipmentSlotGroup.ANY));
        }
    }

    // ── Fall damage ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;

        if (isWearing(player, "luffy_head")) {
            event.setCancelled(true);
        } else if (isWearing(player, "sanji_head")) {
            event.setDamage(event.getDamage() * FEATHER_FALLING_MULT);
        }
    }

    // ── Ifrit fire damage bypasses armor ─────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFireTickDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FIRE_TICK) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        Long expiry = ifritFire.get(victim.getUniqueId());
        if (expiry == null) return;
        if (System.currentTimeMillis() > expiry) {
            ifritFire.remove(victim.getUniqueId());
            return;
        }
        if (victim instanceof Player p && isRestrictedZone(p.getLocation())) return;
        bypassArmor(event);
    }

    private void bypassArmor(EntityDamageEvent event) {
        for (EntityDamageEvent.DamageModifier mod : EntityDamageEvent.DamageModifier.values()) {
            if (mod == EntityDamageEvent.DamageModifier.BASE) continue;
            if (event.isApplicable(mod)) event.setDamage(mod, 0.0);
        }
    }

    // ── Right-click dispatch ──────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player  player   = event.getPlayer();
        boolean sneaking = player.isSneaking();

        boolean hasHead = isWearing(player, "luffy_head") || isWearing(player, "zoro_head")
                || isWearing(player, "nami_head") || isWearing(player, "sanji_head");
        if (!hasHead) return;

        if (isRestrictedZone(player.getLocation())) {
            // Allow consumable items (food, potions, etc.) to be used normally
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand != null && hand.getType().isEdible()) return;
            if (hand != null && (hand.getType() == Material.POTION
                    || hand.getType() == Material.SPLASH_POTION
                    || hand.getType() == Material.LINGERING_POTION)) return;

            // Allow interactable blocks (ender chest, crafting table, etc.)
            if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                    && isInteractable(event.getClickedBlock().getType())) return;

            player.sendMessage(msg("restricted-zone"));
            event.setCancelled(true);
            return;
        }

        if (isWearing(player, "luffy_head") && sneaking) {
            event.setCancelled(true);
            doGear3(player);
        } else if (isWearing(player, "zoro_head") && sneaking) {
            event.setCancelled(true);
            doPurgatoryOnigiri(player);
        } else if (isWearing(player, "nami_head")) {
            event.setCancelled(true);
            if (sneaking) doMirageTempo(player);
            else          doWeatherTraps(player);
        } else if (isWearing(player, "sanji_head") && sneaking) {
            event.setCancelled(true);
            doIfritJambe(player);
        }
    }

    /** Returns true for blocks that have a right-click interaction (containers, doors, etc.). */
    private boolean isInteractable(Material mat) {
        if (mat == null) return false;
        return switch (mat) {
            case ENDER_CHEST, CHEST, TRAPPED_CHEST, BARREL,
                 CRAFTING_TABLE, ENCHANTING_TABLE, ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL,
                 FURNACE, BLAST_FURNACE, SMOKER,
                 BREWING_STAND, STONECUTTER, LOOM, CARTOGRAPHY_TABLE, GRINDSTONE, SMITHING_TABLE,
                 SHULKER_BOX, DISPENSER, DROPPER, HOPPER, BEACON, LECTERN,
                 LEVER, COMPARATOR, REPEATER, DAYLIGHT_DETECTOR, NOTE_BLOCK, JUKEBOX -> true;
            default -> mat.name().endsWith("_DOOR") || mat.name().endsWith("_GATE")
                    || mat.name().endsWith("_BUTTON") || mat.name().endsWith("_TRAPDOOR")
                    || mat.name().endsWith("_BED") || mat.name().endsWith("_SIGN")
                    || mat.name().endsWith("SHULKER_BOX");
        };
    }

    // ── Melee passives & Ifrit Jambe ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMeleeHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        if (isWearing(attacker, "sanji_head")) {
            ItemStack hand = attacker.getInventory().getItemInMainHand();
            if (hand == null || hand.getType() == Material.AIR) {
                event.setDamage(7.0);
            }
        }

        if (isWearing(attacker, "zoro_head") && isSword(attacker.getInventory().getItemInMainHand())) {
            event.setDamage(event.getDamage() * 1.10);
        }

        if (isWearing(attacker, "sanji_head")) {
            Long end = ifritActive.get(attacker.getUniqueId());
            if (end != null && System.currentTimeMillis() < end) {
                boolean victimProtected = victim instanceof Player vp && isRestrictedZone(vp.getLocation());
                if (!victimProtected) {
                    victim.setFireTicks(100);
                    ifritFire.put(victim.getUniqueId(), System.currentTimeMillis() + IFRIT_FIRE_MS);
                    victim.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 80, 0, false, false));
                }
            } else {
                ifritActive.remove(attacker.getUniqueId());
            }
        }
    }

    // ── Nami snowball hit ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSnowballHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Snowball sb)) return;
        if (!namiSnowballs.containsKey(sb.getUniqueId())) return;
        namiSnowballs.remove(sb.getUniqueId());
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        if (victim instanceof Player vp && isRestrictedZone(vp.getLocation())) return;

        event.setDamage(6.0);
        bypassArmor(event);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  LUFFY — GEAR 3
    // ══════════════════════════════════════════════════════════════════════════

    private void doGear3(Player player) {
        if (sendCooldownMsg(player, "gear3")) return;
        setCooldown(player.getUniqueId(), "gear3");

        Location loc    = player.getLocation();
        int      pushed = 0;

        for (Entity entity : player.getNearbyEntities(10, 10, 10)) {
            if (!(entity instanceof LivingEntity)) continue;
            if (entity.equals(player)) continue;
            if (entity instanceof Player ep && isRestrictedZone(ep.getLocation())) continue;

            Vector dir = entity.getLocation().toVector().subtract(loc.toVector());
            if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 1, 0);
            dir.normalize().multiply(2.5).setY(0.8);

            if (trajectoryEntersSpawn(entity.getLocation(), dir)) continue;

            entity.setVelocity(dir);
            pushed++;
        }

        loc.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, loc, 2, 0.5, 0.5, 0.5, 0);
        loc.getWorld().playSound(loc, Sound.ENTITY_SLIME_SQUISH, 2f, 0.4f);

        logManager.logAbility(player.getUniqueId(), "gear3");
        player.sendMessage(msg("luffy-gear3",
                "%count%", String.valueOf(pushed),
                "%unit%",  pushed == 1 ? "entity" : "entities"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  ZORO — PURGATORY ONIGIRI
    // ══════════════════════════════════════════════════════════════════════════

    private void doPurgatoryOnigiri(Player player) {
        if (sendCooldownMsg(player, "onigiri")) return;
        setCooldown(player.getUniqueId(), "onigiri");
        logManager.logAbility(player.getUniqueId(), "onigiri");

        Location origin = player.getLocation().clone();
        Vector   dir    = origin.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        dir.normalize();

        Vector    finalDir = dir;
        Set<UUID> hit      = ConcurrentHashMap.newKeySet();
        int[]     step     = {0};

        player.playSound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5f, 0.6f);

        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            step[0]++;
            if (!player.isOnline() || step[0] > 10) {
                task.cancel();
                return;
            }

            Location playerLoc = player.getLocation();
            Location next      = origin.clone().add(finalDir.clone().multiply(step[0]));
            next.setYaw(playerLoc.getYaw());
            next.setPitch(playerLoc.getPitch());

            if (isRestrictedZone(next)) {
                task.cancel();
                return;
            }

            Block block = next.getBlock();
            if (!block.isPassable()) { task.cancel(); return; }

            player.teleport(next);
            next.getWorld().spawnParticle(Particle.SWEEP_ATTACK, next.clone().add(0, 1, 0), 3, 0.3, 0.3, 0.3, 0);

            for (Entity entity : player.getNearbyEntities(1.5, 2, 1.5)) {
                if (!(entity instanceof LivingEntity target)) continue;
                if (entity.equals(player)) continue;
                if (!hit.add(entity.getUniqueId())) continue;
                if (entity instanceof Player ep && isRestrictedZone(ep.getLocation())) continue;

                target.damage(4.0, player);
                target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0, false, true));
            }
        }, 0L, 1L);

        player.sendMessage(msg("zoro-onigiri"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NAMI — MIRAGE TEMPO
    // ══════════════════════════════════════════════════════════════════════════

    private void doMirageTempo(Player player) {
        if (sendCooldownMsg(player, "mirage_tempo")) return;
        setCooldown(player.getUniqueId(), "mirage_tempo");
        logManager.logAbility(player.getUniqueId(), "mirage_tempo");

        Location center = player.getLocation().clone().add(0, 1, 0);
        smokeSpheres.put(center, new SmokeEntry(player.getUniqueId(), System.currentTimeMillis() + SMOKE_DURATION_MS));

        spawnSmokeSphere(center);
        player.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.5f);
        player.sendMessage(msg("nami-mirage-tempo"));
    }

    private void spawnSmokeSphere(Location center) {
        World world = center.getWorld();
        if (world == null) return;
        double radius = 3.0;
        for (double phi = 0; phi <= Math.PI; phi += Math.PI / 10) {
            double sinPhi = Math.sin(phi);
            double cosPhi = Math.cos(phi);
            for (double theta = 0; theta < 2 * Math.PI; theta += Math.PI / 10) {
                double x = radius * sinPhi * Math.cos(theta);
                double y = radius * cosPhi;
                double z = radius * sinPhi * Math.sin(theta);
                world.spawnParticle(Particle.EXPLOSION, center.clone().add(x, y, z), 1, 0, 0, 0, 0);
            }
        }
        world.spawnParticle(Particle.LARGE_SMOKE, center, 80, 1.5, 1.5, 1.5, 0.02);
    }

    private void tickSmokeSpheres() {
        smokeSpheres.entrySet().removeIf(e -> System.currentTimeMillis() > e.getValue().expiryMs());

        for (Map.Entry<Location, SmokeEntry> entry : smokeSpheres.entrySet()) {
            Location center  = entry.getKey();
            UUID     deployer = entry.getValue().deployer();
            World    world   = center.getWorld();
            if (world == null) continue;
            world.spawnParticle(Particle.LARGE_SMOKE, center, 40, 1.5, 1.5, 1.5, 0.02);
            for (Entity entity : world.getNearbyEntities(center, 3, 3, 3)) {
                if (!(entity instanceof Player target)) continue;
                if (target.getUniqueId().equals(deployer)) continue;
                if (isRestrictedZone(target.getLocation())) continue;
                target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 0, false, false));
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  NAMI — WEATHER TRAPS
    // ══════════════════════════════════════════════════════════════════════════

    private void doWeatherTraps(Player player) {
        if (sendCooldownMsg(player, "weather_traps")) return;
        setCooldown(player.getUniqueId(), "weather_traps");
        logManager.logAbility(player.getUniqueId(), "weather_traps");

        Snowball sb = player.launchProjectile(Snowball.class);
        namiSnowballs.put(sb.getUniqueId(), player.getUniqueId());

        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 1.2f);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SANJI — SKY WALK (double jump)
    // ══════════════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();
        if (!isWearing(player, "sanji_head")) return;
        if (isRestrictedZone(player.getLocation())) return;
        if (sanji_flight.contains(uuid)) return;
        player.setAllowFlight(true);
        sanji_flight.add(uuid);
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMoveForLanding(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();
        if (!sanji_flight.contains(uuid)) return;

        // PlayerJumpEvent fires while still grounded; wait until the player is
        // actually airborne before we start watching for landing.
        if (!sanji_airborne.contains(uuid)) {
            if (!player.isOnGround()) sanji_airborne.add(uuid);
            return;
        }

        // Keep the horizontal velocity cache current so the double-jump carries
        // whatever direction the player is actively moving at press time.
        Vector v = player.getVelocity();
        if (v.getX() != 0 || v.getZ() != 0)
            sanji_lastHorizVel.put(uuid, new Vector(v.getX(), 0, v.getZ()));

        if (!isWearing(player, "sanji_head") || isRestrictedZone(player.getLocation()) || player.isOnGround()) {
            sanji_flight.remove(uuid);
            sanji_airborne.remove(uuid);
            sanji_lastHorizVel.remove(uuid);
            if (!player.isFlying()) player.setAllowFlight(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!sanji_flight.remove(player.getUniqueId())) return;

        sanji_airborne.remove(player.getUniqueId());
        event.setCancelled(true);
        player.setAllowFlight(false);

        if (isRestrictedZone(player.getLocation())) return;

        Vector horiz = sanji_lastHorizVel.remove(player.getUniqueId());
        if (horiz == null) horiz = new Vector(0, 0, 0);
        player.setVelocity(new Vector(horiz.getX(), 0.8, horiz.getZ()));
        player.playSound(player.getLocation(), Sound.BLOCK_SLIME_BLOCK_FALL, 1f, 1.3f);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  SANJI — IFRIT JAMBE
    // ══════════════════════════════════════════════════════════════════════════

    private void doIfritJambe(Player player) {
        if (sendCooldownMsg(player, "ifrit_jambe")) return;
        setCooldown(player.getUniqueId(), "ifrit_jambe");
        logManager.logAbility(player.getUniqueId(), "ifrit_jambe");

        ifritActive.put(player.getUniqueId(), System.currentTimeMillis() + IFRIT_DURATION_MS);

        player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0, 1, 0), 40, 0.3, 0.5, 0.3, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_AMBIENT, 1f, 0.8f);
        player.sendMessage(msg("sanji-ifrit-start", "%seconds%", String.valueOf(IFRIT_DURATION_MS / 1000)));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.sendMessage(msg("sanji-ifrit-end"));
        }, IFRIT_DURATION_MS / 50); // ms → ticks (1 tick = 50 ms)
    }

    // ── Cleanup on quit ───────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldowns.remove(uuid);
        ifritActive.remove(uuid);
        ifritFire.remove(uuid);

        if (sanji_flight.remove(uuid)) {
            event.getPlayer().setAllowFlight(false);
        }
        sanji_airborne.remove(uuid);
        sanji_lastHorizVel.remove(uuid);

        updateNamiSpeed(event.getPlayer(), false);
    }
}
