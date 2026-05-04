package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ZoanAbilityListener implements Listener {

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final ManaManager manaManager;
    private final RegionManager regionManager;
    private final FruitGUIManager guiManager;
    private final FruitSlotManager fruitSlotManager;

    private final Map<UUID, Long>       abilityCooldown = new ConcurrentHashMap<>();
    private final Set<UUID>             activeAbility   = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> endTasks        = new ConcurrentHashMap<>();

    // Tori Tori Falcon: saved chestplate + tagged elytra key
    private final NamespacedKey          FALCON_ELYTRA_KEY;
    private final Map<UUID, ItemStack>   savedChestplate = new ConcurrentHashMap<>();

    // Kumo Kumo Tarantula: shot entity UUID → shooter UUID, plus placed web blocks per shooter
    private final Map<UUID, UUID>        webShots  = new ConcurrentHashMap<>(); // shot entity → shooter
    private final Map<UUID, List<Block>> webBlocks = new ConcurrentHashMap<>();

    // Zou Zou Mammoth / Neko Neko Leopard: shared dash state
    private final Set<UUID>             dashing   = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> dashTasks = new ConcurrentHashMap<>();

    public ZoanAbilityListener(GenPvP plugin, DevilFruitManager fruitManager,
                               ManaManager manaManager, RegionManager regionManager,
                               FruitGUIManager guiManager, FruitSlotManager fruitSlotManager) {
        this.plugin            = plugin;
        this.fruitManager      = fruitManager;
        this.manaManager       = manaManager;
        this.regionManager     = regionManager;
        this.guiManager        = guiManager;
        this.fruitSlotManager  = fruitSlotManager;
        this.FALCON_ELYTRA_KEY = new NamespacedKey(plugin, "falcon_elytra");
    }

    // ── Activation: Sneak + Right-click ──────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        org.bukkit.event.block.Action action = event.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!fruitSlotManager.isFruitSlotItem(player.getInventory().getItemInMainHand())) return;

        UUID uuid = player.getUniqueId();

        String equipped = fruitManager.getEquippedFruit(uuid);
        if (equipped == null) return;
        DevilFruit fruit = DevilFruit.fromKey(equipped);
        if (fruit == null || fruit.getType() != FruitType.ZOAN) return;

        if (!fruitManager.getOwnedFruits(uuid).contains(equipped)) return;

        if (isInSpawn(player)) {
            player.sendMessage(msg("ability-in-spawn"));
            return;
        }

        if (fruitManager.isFruitKeyDisabled(equipped)) {
            player.sendMessage(msg("ability-disabled").replace("%fruit%", fruit.getDisplayName()));
            return;
        }

        Long expiresAt = abilityCooldown.get(uuid);
        if (expiresAt != null && System.currentTimeMillis() < expiresAt) {
            double secondsLeft = (expiresAt - System.currentTimeMillis()) / 1000.0;
            player.sendMessage(msg("ability-on-cooldown")
                    .replace("%time%", String.format("%.1f", secondsLeft)));
            return;
        }
        abilityCooldown.remove(uuid);

        if (activeAbility.contains(uuid)) {
            player.sendMessage(msg("ability-already-active"));
            return;
        }

        int cost = cfg(equipped, "mana-cost", 1);
        if (!manaManager.spend(uuid, cost)) {
            player.sendMessage(msg("not-enough-mana")
                    .replace("%current%", String.valueOf(manaManager.getMana(uuid)))
                    .replace("%cost%", String.valueOf(cost)));
            return;
        }

        event.setCancelled(true);
        dispatch(player, equipped);
    }

    private void dispatch(Player player, String key) {
        switch (key) {
            case "tori_tori_falcon"    -> activateToriTori(player);
            case "kumo_kumo_tarantula" -> activateKumoKumo(player);
            case "zou_zou_mammoth"     -> activateZouZou(player);
            case "neko_neko_leopard"   -> activateNekoNeko(player);
            case "hebi_hebi_cobra"     -> activateHebiHebi(player);
            case "inu_inu_wolf"        -> activateInuInu(player);
        }
    }

    // ── Tori Tori no Mi, Model: Falcon ────────────────────────────────────────
    // Launch ~10 blocks up; force-equip a tagged Elytra for duration seconds, then restore.

    private void activateToriTori(Player player) {
        UUID uuid          = player.getUniqueId();
        int  durationTicks = cfg("tori_tori_falcon", "duration-seconds",   4) * 20;
        int  cooldownTicks = cfg("tori_tori_falcon", "cooldown-seconds",  15) * 20;
        double launchY     = cfgD("tori_tori_falcon", "launch-velocity-y", 1.3);

        activeAbility.add(uuid);

        // Save whatever is in the chestplate slot, including null
        ItemStack current = player.getInventory().getChestplate();
        savedChestplate.put(uuid, current != null ? current.clone() : null);

        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta  meta   = elytra.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(FALCON_ELYTRA_KEY, PersistentDataType.BYTE, (byte) 1);
            elytra.setItemMeta(meta);
        }
        player.getInventory().setChestplate(elytra);
        player.setVelocity(player.getVelocity().setY(launchY));

        player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 1f, 1.2f);
        player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_AMBIENT, 0.6f, 1.5f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0),
                25, 0.4, 0.5, 0.4, 0.06);
        player.sendMessage(msg("ability.tori_tori_falcon"));

        // Restore after the active window; scheduleEnd also calls it as a safety net
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> restoreToriChestplate(player, uuid), durationTicks);
        scheduleEnd(uuid, cooldownTicks, () -> restoreToriChestplate(player, uuid));
    }

    private void restoreToriChestplate(Player player, UUID uuid) {
        // Always pull out of the map first — prevents leaks if the player removed the elytra manually
        ItemStack saved = savedChestplate.remove(uuid);
        if (saved == null || !player.isOnline()) return;
        // Only overwrite if our tagged elytra is still in the slot
        ItemStack chest = player.getInventory().getChestplate();
        if (chest == null || !chest.hasItemMeta()) return;
        ItemMeta m = chest.getItemMeta();
        if (m == null || !m.getPersistentDataContainer().has(FALCON_ELYTRA_KEY, PersistentDataType.BYTE)) return;
        player.getInventory().setChestplate(saved.getType() == Material.AIR ? null : saved);
    }

    // ── Kumo Kumo no Mi, Model: Tarantula ─────────────────────────────────────
    // Fire a tagged projectile; on block hit, spawn a 3×3 cobweb trap at the hit face.

    private void activateKumoKumo(Player player) {
        UUID uuid          = player.getUniqueId();
        int  cooldownTicks = cfg("kumo_kumo_tarantula", "cooldown-seconds", 12) * 20;
        activeAbility.add(uuid);

        Snowball shot = player.launchProjectile(Snowball.class);
        shot.setVelocity(player.getLocation().getDirection().normalize().multiply(2.0));
        webShots.put(shot.getUniqueId(), uuid);

        player.playSound(player.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 1f, 1.5f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getEyeLocation(),
                6, 0.2, 0.2, 0.2, 0.01);
        player.sendMessage(msg("ability.kumo_kumo_tarantula"));

        scheduleEnd(uuid, cooldownTicks, null);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball snowball)) return;

        UUID shooterUuid = webShots.remove(snowball.getUniqueId());
        if (shooterUuid == null) return;

        int  webDuration = cfg("kumo_kumo_tarantula", "web-duration-seconds", 5) * 20;
        final UUID uuid  = shooterUuid;

        Location impact = snowball.getLocation();
        World world = impact.getWorld();
        int cx = impact.getBlockX();
        int cz = impact.getBlockZ();
        int cy = impact.getBlockY();
        // Scan up from impact until we find a non-solid block to anchor the cube base
        for (int scan = cy; scan <= cy + 4; scan++) {
            if (!world.getBlockAt(cx, scan, cz).getType().isSolid()) { cy = scan; break; }
        }

        Location center = new Location(world, cx + 0.5, cy, cz + 0.5);

        // Impact burst
        center.getWorld().playSound(center, Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.8f);
        center.getWorld().spawnParticle(Particle.CLOUD, center, 12, 0.6, 0.2, 0.6, 0.03);

        // Place a 3×3×3 cube of cobwebs — skip solid blocks and liquids only
        List<Block> allPlaced = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (!block.getType().isSolid() && !block.isLiquid()) {
                        block.setType(Material.COBWEB);
                        allPlaced.add(block);
                    }
                }
            }
        }

        broadcastOps("[KumoKumo] " + (allPlaced.isEmpty()
                ? "NOT placed — no air at " + cx + "," + cy + "," + cz
                : "placed " + allPlaced.size() + " cobwebs at " + cx + "," + cy + "," + cz));
        if (!allPlaced.isEmpty()) webBlocks.put(uuid, allPlaced);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            List<Block> webs = webBlocks.remove(uuid);
            if (webs != null) {
                webs.forEach(b -> { if (b.getType() == Material.COBWEB) b.setType(Material.AIR); });
                webs.forEach(b -> b.getWorld().spawnParticle(Particle.CLOUD,
                        b.getLocation().add(0.5, 0.5, 0.5), 2, 0.1, 0.1, 0.1, 0.01));
            }
        }, webDuration);
    }

    // ── Zou Zou no Mi, Model: Mammoth ─────────────────────────────────────────
    // Barrel forward at high speed, dealing damage and pushing enemies sideways.
    // Unlike Neko Neko, the charge does NOT stop on first hit — it tramples through.

    private void activateZouZou(Player player) {
        UUID   uuid          = player.getUniqueId();
        int    cooldownTicks = cfg("zou_zou_mammoth", "cooldown-seconds", 14) * 20;
        int    dashTick      = cfg("zou_zou_mammoth", "dash-ticks",        12);
        double dashSpeed     = cfgD("zou_zou_mammoth", "dash-speed",        1.4);
        double damage        = cfgD("zou_zou_mammoth", "damage",            4.0);
        double sideKnockback = cfgD("zou_zou_mammoth", "side-knockback",    1.5);
        double knockY        = cfgD("zou_zou_mammoth", "knockback-y",       0.5);

        activeAbility.add(uuid);
        dashing.add(uuid);

        Vector dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        final Vector chargeDir = dir.normalize().clone();
        player.setVelocity(chargeDir.clone().multiply(dashSpeed).setY(0.25));

        // Activation burst
        player.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.7f);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.5f, 0.4f);
        player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 3, 0.4, 0.1, 0.4, 0);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 0.1, 0),
                30, 0.8, 0.1, 0.8, 0, new Particle.DustOptions(Color.fromRGB(101, 67, 33), 2.5f));
        player.sendMessage(msg("ability.zou_zou_mammoth"));

        BukkitTask dashTask = new org.bukkit.scheduler.BukkitRunnable() {
            int tick = 0;
            final Set<UUID> hit = new HashSet<>();

            @Override
            public void run() {
                if (!player.isOnline() || !dashing.contains(uuid) || tick >= dashTick) {
                    dashing.remove(uuid);
                    dashTasks.remove(uuid);
                    cancel();
                    return;
                }
                tick++;

                // Re-apply horizontal charge velocity each tick to resist drag
                Vector vel = player.getVelocity();
                double hSpeed = Math.sqrt(vel.getX() * vel.getX() + vel.getZ() * vel.getZ());
                if (hSpeed < dashSpeed * 0.65) {
                    player.setVelocity(chargeDir.clone().multiply(dashSpeed * 0.85)
                            .setY(Math.max(vel.getY(), 0)));
                }

                // Dust + ground debris trail
                player.getWorld().spawnParticle(Particle.DUST,
                        player.getLocation().add(0, 0.3, 0), 12, 0.5, 0.3, 0.5, 0,
                        new Particle.DustOptions(Color.fromRGB(101, 67, 33), 1.8f));
                player.getWorld().spawnParticle(Particle.BLOCK,
                        player.getLocation().add(0, 0.1, 0), 8, 0.4, 0.1, 0.4, 0.04,
                        Material.DIRT.createBlockData());

                // Wide hitbox — tramples through all enemies in range
                for (Entity nearby : player.getNearbyEntities(2.2, 2.0, 2.2)) {
                    if (!(nearby instanceof LivingEntity target) || nearby.equals(player)) continue;
                    if (hit.contains(target.getUniqueId())) continue;
                    if (target instanceof Player tp && isInSpawn(tp)) continue;
                    hit.add(target.getUniqueId());

                    target.damage(damage, player);

                    // Push the target sideways away from the charge path
                    Vector pushDir = target.getLocation().toVector()
                            .subtract(player.getLocation().toVector()).setY(0);
                    if (pushDir.lengthSquared() < 1e-6) pushDir = new Vector(1, 0, 0);
                    target.setVelocity(pushDir.normalize().multiply(sideKnockback).setY(knockY));

                    // Impact effects
                    target.getWorld().spawnParticle(Particle.CRIT,
                            target.getLocation().add(0, 1, 0), 18, 0.5, 0.6, 0.5, 0.1);
                    target.getWorld().spawnParticle(Particle.EXPLOSION,
                            target.getLocation().add(0, 0.5, 0), 2, 0.2, 0.2, 0.2, 0);
                    target.getWorld().playSound(target.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1f, 0.7f);
                    player.playSound(player.getLocation(), Sound.ENTITY_RAVAGER_ATTACK, 0.8f, 0.9f);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        dashTasks.put(uuid, dashTask);

        scheduleEnd(uuid, cooldownTicks, () -> {
            dashing.remove(uuid);
            BukkitTask dt = dashTasks.remove(uuid);
            if (dt != null) dt.cancel();
        });
    }

    // ── Neko Neko no Mi, Model: Leopard ──────────────────────────────────────
    // Rapid forward dash; first collision deals burst damage + disables shield. Stops on hit.

    private void activateNekoNeko(Player player) {
        UUID   uuid          = player.getUniqueId();
        int    cooldownTicks = cfg("neko_neko_leopard", "cooldown-seconds", 10) * 20;
        int    dashTick      = cfg("neko_neko_leopard", "dash-ticks",        8);
        double dashSpeed     = cfgD("neko_neko_leopard", "dash-speed",        1.2);
        double burstDamage   = cfgD("neko_neko_leopard", "burst-damage",      5.0);

        activeAbility.add(uuid);
        dashing.add(uuid);

        Vector dir = player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(1, 0, 0);
        player.setVelocity(dir.normalize().multiply(dashSpeed).setY(0.2));

        player.playSound(player.getLocation(), Sound.ENTITY_CAT_HISS, 0.8f, 1.5f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0),
                10, 0.3, 0.5, 0.3, 0.08);
        player.sendMessage(msg("ability.neko_neko_leopard"));

        BukkitTask dashTask = new org.bukkit.scheduler.BukkitRunnable() {
            int tick = 0;
            final Set<UUID> hit = new HashSet<>();

            @Override
            public void run() {
                if (!player.isOnline() || !dashing.contains(uuid) || tick >= dashTick) {
                    dashing.remove(uuid);
                    dashTasks.remove(uuid);
                    cancel();
                    return;
                }
                tick++;

                for (Entity nearby : player.getNearbyEntities(1.5, 2.0, 1.5)) {
                    if (!(nearby instanceof Player target)) continue;
                    if (hit.contains(target.getUniqueId())) continue;
                    if (isInSpawn(target)) continue;
                    hit.add(target.getUniqueId());

                    target.damage(burstDamage, player);
                    target.setCooldown(Material.SHIELD, 100); // ~5 s shield disable
                    target.getWorld().spawnParticle(Particle.CRIT,
                            target.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
                    target.playSound(target.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);

                    // Stop the dash on first contact
                    dashing.remove(uuid);
                    dashTasks.remove(uuid);
                    endAbilityEarly(uuid);
                    cancel();
                    return;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        dashTasks.put(uuid, dashTask);

        scheduleEnd(uuid, cooldownTicks, () -> {
            dashing.remove(uuid);
            BukkitTask dt = dashTasks.remove(uuid);
            if (dt != null) dt.cancel();
        });
    }

    // ── Hebi Hebi no Mi, Model: King Cobra ────────────────────────────────────
    // Pull nearest enemy slightly toward the user + apply Poison II.

    private void activateHebiHebi(Player player) {
        UUID   uuid           = player.getUniqueId();
        int    cooldownTicks  = cfg("hebi_hebi_cobra", "cooldown-seconds",        12) * 20;
        double range          = cfgD("hebi_hebi_cobra", "range",                   12.0);
        double pullStrength   = cfgD("hebi_hebi_cobra", "pull-strength",            0.4);
        int    poisonDuration = cfg("hebi_hebi_cobra", "poison-duration-seconds",    6) * 20;
        int    poisonAmp      = cfg("hebi_hebi_cobra", "poison-amplifier",            1);

        activeAbility.add(uuid);

        // Find nearest non-spawn player within range
        Player target  = null;
        double nearest = range;
        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Player t)) continue;
            if (isInSpawn(t)) continue;
            double dist = t.getLocation().distance(player.getLocation());
            if (dist < nearest) { nearest = dist; target = t; }
        }

        if (target == null) {
            player.sendMessage(msg("ability.hebi_hebi_cobra_miss"));
            scheduleEnd(uuid, cooldownTicks, null);
            return;
        }

        Vector pull = player.getLocation().toVector()
                .subtract(target.getLocation().toVector()).normalize().multiply(pullStrength);
        target.setVelocity(pull);
        target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, poisonDuration, poisonAmp, false, true));

        target.getWorld().spawnParticle(Particle.DUST,
                target.getLocation().add(0, 1, 0), 24, 0.4, 0.6, 0.4, 0,
                new Particle.DustOptions(Color.fromRGB(0, 140, 0), 1.2f));
        player.playSound(player.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 0.8f, 0.5f);
        player.sendMessage(msg("ability.hebi_hebi_cobra"));

        scheduleEnd(uuid, cooldownTicks, null);
    }

    // ── Inu Inu no Mi, Model: Wolf ────────────────────────────────────────────
    // Howl: Speed II for the user + Glowing on all nearby enemies, strips invisibility.

    private void activateInuInu(Player player) {
        UUID   uuid          = player.getUniqueId();
        int    cooldownTicks = cfg("inu_inu_wolf", "cooldown-seconds",       18) * 20;
        double radius        = cfgD("inu_inu_wolf", "radius",                 20.0);
        int    speedAmp      = cfg("inu_inu_wolf", "speed-amplifier",          1);
        int    speedDuration = cfg("inu_inu_wolf", "speed-duration-seconds",   8) * 20;
        int    glowDuration  = cfg("inu_inu_wolf", "glow-duration-seconds",   10) * 20;

        activeAbility.add(uuid);

        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, speedDuration, speedAmp, false, true));

        player.playSound(player.getLocation(), Sound.ENTITY_WOLF_GROWL, 1f, 0.5f);
        player.playSound(player.getLocation(), Sound.ENTITY_WOLF_AMBIENT, 0.8f, 0.4f);
        player.getWorld().spawnParticle(Particle.NOTE, player.getLocation().add(0, 2, 0),
                10, 0.6, 0.3, 0.6, 1.0);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 1, 0),
                12, 0.5, 0.4, 0.5, 0.03);
        player.sendMessage(msg("ability.inu_inu_wolf"));

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Player target)) continue;
            if (isInSpawn(target)) continue;
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glowDuration, 0, false, false));
            target.removePotionEffect(PotionEffectType.INVISIBILITY);
        }

        scheduleEnd(uuid, cooldownTicks, null);
    }

    // ── Death: Tori Tori chestplate handling ──────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        if (!savedChestplate.containsKey(uuid)) return;

        if (event.getKeepInventory()) {
            // Chestplate slot is preserved — onRespawn will swap the elytra out
            return;
        }

        // keepInventory off: remove our tagged elytra from drops, add the real chestplate
        ItemStack saved = savedChestplate.remove(uuid);
        event.getDrops().removeIf(i -> {
            if (i == null || !i.hasItemMeta()) return false;
            ItemMeta m = i.getItemMeta();
            return m != null && m.getPersistentDataContainer().has(FALCON_ELYTRA_KEY, PersistentDataType.BYTE);
        });
        if (saved != null && saved.getType() != Material.AIR) {
            event.getDrops().add(saved);
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();
        if (!savedChestplate.containsKey(uuid)) return;
        // keepInventory=true path: player respawns with elytra still in slot
        plugin.getServer().getScheduler().runTaskLater(plugin,
                () -> restoreToriChestplate(player, uuid), 1L);
    }

    // ── Cleanup on disconnect ─────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();

        BukkitTask task = endTasks.remove(uuid);
        if (task != null) task.cancel();

        BukkitTask dt = dashTasks.remove(uuid);
        if (dt != null) dt.cancel();
        dashing.remove(uuid);

        // Restore chestplate before the inventory is serialised to disk
        restoreToriChestplate(player, uuid);
        savedChestplate.remove(uuid);

        webShots.values().removeIf(v -> v.equals(uuid)); // remove any in-flight shots by this player

        List<Block> webs = webBlocks.remove(uuid);
        if (webs != null) webs.forEach(b -> { if (b.getType() == Material.COBWEB) b.setType(Material.AIR); });

        activeAbility.remove(uuid);
        abilityCooldown.remove(uuid);
    }

    // ── Scheduling helpers ────────────────────────────────────────────────────

    private void scheduleEnd(UUID uuid, long delayTicks, Runnable extraCleanup) {
        BukkitTask existing = endTasks.remove(uuid);
        if (existing != null) existing.cancel();

        abilityCooldown.put(uuid, System.currentTimeMillis() + delayTicks * 50L);

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            endTasks.remove(uuid);
            if (extraCleanup != null) extraCleanup.run();
            activeAbility.remove(uuid);
        }, Math.max(1, delayTicks));

        endTasks.put(uuid, task);
    }

    private void endAbilityEarly(UUID uuid) {
        BukkitTask task = endTasks.remove(uuid);
        if (task != null) task.cancel();
        activeAbility.remove(uuid);
    }

    // ── Config / message helpers ──────────────────────────────────────────────

    private String msg(String key) {
        return ColorUtil.colorize(guiManager.getMessagesConfig().getString(key, "§cMissing: " + key));
    }

    private int cfg(String key, String field, int def) {
        return guiManager.getBalanceConfig().getInt("devil-fruits.abilities." + key + "." + field, def);
    }

    private double cfgD(String key, String field, double def) {
        return guiManager.getBalanceConfig().getDouble("devil-fruits.abilities." + key + "." + field, def);
    }

    private void broadcastOps(String message) {
        String colored = "§7[§bDebug§7] §f" + message;
        for (Player op : plugin.getServer().getOnlinePlayers()) {
            if (op.isOp()) op.sendMessage(colored);
        }
    }

    private boolean isInSpawn(Player player) {
        for (ProtectRegion region : regionManager.getRegionsAt(player.getLocation())) {
            if (region.getType() == RegionType.SPAWN) return true;
        }
        return false;
    }
}
