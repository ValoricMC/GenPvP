package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Paramecia devil fruit ability activation (Sneak + Right-click).
 *
 * Rules:
 * - Abilities do not work in SPAWN regions.
 * - Only one ability may be active at a time.
 * - Mana must be sufficient.
 * - Player must own the equipped fruit in the database.
 */
public class ParameciaAbilityListener implements Listener {

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final ManaManager manaManager;
    private final RegionManager regionManager;
    private final FruitGUIManager guiManager;
    private final FruitSlotManager fruitSlotManager;

    // Cooldown: maps player UUID → System.currentTimeMillis() when the cooldown expires
    private final Map<UUID, Long> abilityCooldown = new ConcurrentHashMap<>();
    // Players currently in a duration ability (effects still running)
    private final Set<UUID> activeAbility    = ConcurrentHashMap.newKeySet();
    // Scheduled end task per player — stored so it can be cancelled on early resolution
    private final Map<UUID, BukkitTask> endTasks = new ConcurrentHashMap<>();

    // Per-ability state
    private final Set<UUID> arrowDeflects    = ConcurrentHashMap.newKeySet(); // Sube Sube
    private final Set<UUID> fallNegates      = ConcurrentHashMap.newKeySet(); // Bane Bane
    private final Set<UUID> pendingExplosion = ConcurrentHashMap.newKeySet(); // Bomu Bomu
    private final Set<UUID> meleeBuffed      = ConcurrentHashMap.newKeySet(); // Supa Supa
    private final Map<UUID, List<Block>> bariBlocks = new ConcurrentHashMap<>(); // Bari Bari
    private final Map<UUID, BukkitTask> fuwaTransitionTasks = new ConcurrentHashMap<>(); // Fuwa Fuwa
    private final Set<UUID> sukeActive          = ConcurrentHashMap.newKeySet();         // Suke Suke armor hide
    private final Map<UUID, BukkitTask> sukeArmorTasks = new ConcurrentHashMap<>();      // Suke Suke armor hide tasks

    public ParameciaAbilityListener(GenPvP plugin, DevilFruitManager fruitManager,
                                    ManaManager manaManager, RegionManager regionManager,
                                    FruitGUIManager guiManager, FruitSlotManager fruitSlotManager) {
        this.plugin            = plugin;
        this.fruitManager      = fruitManager;
        this.manaManager       = manaManager;
        this.regionManager     = regionManager;
        this.guiManager        = guiManager;
        this.fruitSlotManager  = fruitSlotManager;
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
        if (fruit == null || fruit.getType() != FruitType.PARAMECIA) return;

        // DB ownership gate — player must actually own this fruit
        if (!fruitManager.getOwnedFruits(uuid).contains(equipped)) return;

        if (isInSpawn(player)) {
            player.sendMessage(msg("ability-in-spawn"));
            return;
        }

        if (activeAbility.contains(uuid)) {
            player.sendMessage(msg("ability-already-active"));
            return;
        }

        // Check cooldown
        Long expiresAt = abilityCooldown.get(uuid);
        if (expiresAt != null && System.currentTimeMillis() < expiresAt) {
            double secondsLeft = (expiresAt - System.currentTimeMillis()) / 1000.0;
            player.sendMessage(msg("ability-on-cooldown")
                    .replace("%time%", String.format("%.1f", secondsLeft)));
            return;
        }
        abilityCooldown.remove(uuid);

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

    // ── Ability dispatch ──────────────────────────────────────────────────────

    private void dispatch(Player player, String key) {
        switch (key) {
            case "sube_sube" -> activateSubeSube(player);
            case "suke_suke" -> activateSukeSuke(player);
            case "bane_bane" -> activateBaneBane(player);
            case "supa_supa" -> activateSupaSupa(player);
            case "doku_doku" -> activateDokuDoku(player);
            case "bomu_bomu" -> activateBomuBomu(player);
            case "bari_bari" -> activateBariBarI(player);
            case "fuwa_fuwa" -> activateFuwaFuwa(player);
            case "gura_gura" -> activateGuraGura(player);
        }
    }

    // ── Sube Sube — Slip-Slip ────────────────────────────────────────────────
    // Speed III burst + deflect the next arrow. 1 mana, 5 s.

    private void activateSubeSube(Player player) {
        UUID uuid = player.getUniqueId();
        int ticks = cfg("sube_sube", "duration-seconds", 5) * 20;
        int amp = cfg("sube_sube", "speed-amplifier", 2);
        // Fraction of horizontal velocity to retain each tick (0.93 = very slippery, like blue ice)
        double iceFriction = cfgD("sube_sube", "ice-friction", 0.93);
        activeAbility.add(uuid);
        arrowDeflects.add(uuid);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, amp, false, false));
        player.playSound(player.getLocation(), Sound.ENTITY_SNOWBALL_THROW, 1f, 1.5f);
        player.getWorld().spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.04);
        player.sendMessage(msg("ability.sube_sube"));

        // Every tick on ground: preserve horizontal momentum (low friction = ice glide)
        // Player keeps sliding when they release keys rather than stopping instantly.
        BukkitTask glideTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !activeAbility.contains(uuid)) return;
            if (!player.isOnGround()) return;
            Vector vel = player.getVelocity();
            double hx = vel.getX();
            double hz = vel.getZ();
            if (hx * hx + hz * hz > 0.001) {
                player.setVelocity(vel.setX(hx * iceFriction).setZ(hz * iceFriction));
                player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation(), 2, 0.2, 0.05, 0.2, Material.ICE.createBlockData());
            }
        }, 0L, 1L);

        scheduleEnd(uuid, ticks, () -> {
            arrowDeflects.remove(uuid);
            glideTask.cancel();
        });
    }

    // ── Suke Suke — Clear-Clear ──────────────────────────────────────────────
    // Invisibility (armor visible in vanilla) + Speed I. 2 mana, 8 s.

    private void activateSukeSuke(Player player) {
        UUID uuid = player.getUniqueId();
        int ticks = cfg("suke_suke", "duration-seconds", 8) * 20;
        int amp = cfg("suke_suke", "speed-amplifier", 0);
        activeAbility.add(uuid);
        sukeActive.add(uuid);
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, amp, false, false));
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.8f);
        player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 18, 0.3, 0.6, 0.3, 0.02);
        player.sendMessage(msg("ability.suke_suke"));

        // Build AIR map once; reused each tick.
        Map<EquipmentSlot, ItemStack> fakeArmor = new HashMap<>();
        ItemStack air = new ItemStack(Material.AIR);
        fakeArmor.put(EquipmentSlot.HEAD, air);
        fakeArmor.put(EquipmentSlot.CHEST, air);
        fakeArmor.put(EquipmentSlot.LEGS, air);
        fakeArmor.put(EquipmentSlot.FEET, air);

        // Re-send fake armor every tick — 1-tick interval ensures we always beat the entity
        // tracker's initial-load equipment sync, which fires when a player enters render distance.
        // All players including the activator receive the packet so F5 view also hides armor.
        BukkitTask armorTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !sukeActive.contains(uuid)) return;
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                online.sendEquipmentChange(player, fakeArmor);
            }
        }, 0L, 1L);
        sukeArmorTasks.put(uuid, armorTask);

        scheduleEnd(uuid, ticks, () -> {
            sukeActive.remove(uuid);
            armorTask.cancel();
            sukeArmorTasks.remove(uuid);
            if (player.isOnline()) {
                Map<EquipmentSlot, ItemStack> realArmor = new HashMap<>();
                realArmor.put(EquipmentSlot.HEAD, player.getInventory().getHelmet());
                realArmor.put(EquipmentSlot.CHEST, player.getInventory().getChestplate());
                realArmor.put(EquipmentSlot.LEGS, player.getInventory().getLeggings());
                realArmor.put(EquipmentSlot.FEET, player.getInventory().getBoots());
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    online.sendEquipmentChange(player, realArmor);
                }
            }
        });
    }

    // ── Bane Bane — Spring-Spring ────────────────────────────────────────────
    // Massive forward leap; negates the next fall-damage event. 2 mana.

    private void activateBaneBane(Player player) {
        UUID uuid = player.getUniqueId();
        int windowTicks = cfg("bane_bane", "window-seconds", 30) * 20;
        double mult = cfgD("bane_bane", "leap-multiplier", 1.8);
        double yVel = cfgD("bane_bane", "leap-y-velocity", 0.9);
        activeAbility.add(uuid);
        fallNegates.add(uuid);

        Vector dir = player.getLocation().getDirection().normalize();
        player.setVelocity(dir.multiply(mult).setY(yVel));
        player.playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1f, 0.5f);
        player.getWorld().spawnParticle(Particle.ITEM_SLIME, player.getLocation().add(0, 0.1, 0), 22, 0.4, 0.1, 0.4, 0.08);
        player.sendMessage(msg("ability.bane_bane"));

        // Window expires if the player never lands within it
        scheduleEnd(uuid, windowTicks, () -> fallNegates.remove(uuid));
    }

    // ── Supa Supa — Dice-Dice ────────────────────────────────────────────────
    // Resistance I + bonus damage on empty-hand hits. 3 mana, 10 s.

    private void activateSupaSupa(Player player) {
        UUID uuid = player.getUniqueId();
        int ticks = cfg("supa_supa", "duration-seconds", 10) * 20;
        activeAbility.add(uuid);
        meleeBuffed.add(uuid);
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, false, false));
        player.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 0.8f, 1.2f);
        player.getWorld().spawnParticle(Particle.CRIT, player.getLocation().add(0, 1, 0), 20, 0.4, 0.6, 0.4, 0.08);
        player.getWorld().spawnParticle(Particle.SWEEP_ATTACK, player.getLocation().add(0, 1, 0), 4, 0.4, 0.4, 0.4, 0);
        player.sendMessage(msg("ability.supa_supa"));
        scheduleEnd(uuid, ticks, () -> meleeBuffed.remove(uuid));
    }

    // ── Doku Doku — Venom-Venom ──────────────────────────────────────────────
    // Throws a real splash potion (Slowness II + Poison I). 3 mana, instant.

    private void activateDokuDoku(Player player) {
        UUID uuid = player.getUniqueId();
        int effectTicks = cfg("doku_doku", "effect-duration-seconds", 8) * 20;
        int slowAmp = cfg("doku_doku", "slowness-amplifier", 1);
        int poisonAmp = cfg("doku_doku", "poison-amplifier", 0);
        activeAbility.add(uuid);

        ItemStack potionItem = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) potionItem.getItemMeta();
        if (meta != null) {
            meta.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, effectTicks, slowAmp), true);
            meta.addCustomEffect(new PotionEffect(PotionEffectType.POISON,   effectTicks, poisonAmp), true);
            potionItem.setItemMeta(meta);
        }

        ThrownPotion tp = player.getWorld().spawn(player.getEyeLocation(), ThrownPotion.class);
        tp.setItem(potionItem);
        tp.setVelocity(player.getLocation().getDirection().normalize().multiply(1.5));
        tp.setShooter(player);

        player.playSound(player.getLocation(), Sound.ENTITY_WITCH_THROW, 1f, 1f);
        Particle.DustOptions venomDust = new Particle.DustOptions(Color.fromRGB(100, 0, 180), 1.2f);
        player.getWorld().spawnParticle(Particle.DUST, player.getLocation().add(0, 1, 0), 22, 0.4, 0.6, 0.4, 0, venomDust);
        player.sendMessage(msg("ability.doku_doku"));
        // Instant — clear active flag after 1 tick
        scheduleEnd(uuid, 1, null);
    }

    // ── Bomu Bomu — Bomb-Bomb ────────────────────────────────────────────────
    // Next melee hit triggers a non-block-breaking explosion. 2 mana.

    private void activateBomuBomu(Player player) {
        UUID uuid = player.getUniqueId();
        int windowTicks = cfg("bomu_bomu", "window-seconds", 5) * 20;
        activeAbility.add(uuid);
        pendingExplosion.add(uuid);
        player.playSound(player.getLocation(), Sound.ENTITY_CREEPER_PRIMED, 0.8f, 1.5f);
        player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0, 1, 0), 14, 0.35, 0.55, 0.35, 0.02);
        player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1.5, 0), 7, 0.2, 0.3, 0.2, 0.01);
        player.sendMessage(msg("ability.bomu_bomu"));
        // Auto-expire if unused within the window
        scheduleEnd(uuid, windowTicks, () -> pendingExplosion.remove(uuid));
    }

    // ── Bari Bari — Barrier-Barrier ──────────────────────────────────────────
    // Spawns a 3×3 glass wall 2 blocks in front of the player. 3 mana, 6 s.

    private void activateBariBarI(Player player) {
        UUID uuid = player.getUniqueId();
        int ticks = cfg("bari_bari", "duration-seconds", 6) * 20;
        activeAbility.add(uuid);

        Location loc = player.getLocation();
        Vector facing = loc.getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) facing = new Vector(1, 0, 0);
        facing.normalize();
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());

        Location wallOrigin = loc.clone().add(facing.multiply(2));
        List<Block> placed  = new ArrayList<>();

        for (int w = -1; w <= 1; w++) {
            for (int h = 0; h <= 2; h++) {
                Block block = wallOrigin.clone()
                        .add(right.clone().multiply(w))
                        .add(0, h, 0)
                        .getBlock();
                if (block.getType() == Material.AIR) {
                    block.setType(Material.GLASS);
                    placed.add(block);
                }
            }
        }
        bariBlocks.put(uuid, placed);
        for (Block b : placed) {
            b.getWorld().spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 0.5, 0.5), 2, 0.15, 0.15, 0.15, 0.01);
        }

        player.playSound(player.getLocation(), Sound.BLOCK_GLASS_PLACE, 1f, 1f);
        player.sendMessage(msg("ability.bari_bari"));
        // scheduleEnd callback already runs on main thread via runTaskLater
        scheduleEnd(uuid, ticks, () -> {
            List<Block> blocks = bariBlocks.remove(uuid);
            if (blocks != null) {
                blocks.forEach(b -> { if (b.getType() == Material.GLASS) b.setType(Material.AIR); });
            }
        });
    }

    // ── Fuwa Fuwa — Float-Float ──────────────────────────────────────────────
    // Levitation for N seconds, then Slow Falling for 5 s. 3 mana.

    private void activateFuwaFuwa(Player player) {
        UUID uuid = player.getUniqueId();
        int levTicks  = cfg("fuwa_fuwa", "levitation-seconds", 5) * 20;
        int fallTicks = cfg("fuwa_fuwa", "slow-falling-seconds", 1) * 20;
        int levAmp = cfg("fuwa_fuwa", "levitation-amplifier", 6);
        activeAbility.add(uuid);

        player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, levTicks, levAmp, false, false));
        player.playSound(player.getLocation(), Sound.ENTITY_PHANTOM_FLAP, 0.8f, 1.2f);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0, 0.5, 0), 18, 0.5, 0.3, 0.5, 0.01);
        player.sendMessage(msg("ability.fuwa_fuwa"));

        // Transition to slow falling once levitation expires; tracked so it can be cancelled on quit
        BukkitTask existing = fuwaTransitionTasks.remove(uuid);
        if (existing != null) existing.cancel();
        
        if (fallTicks > 0) {
            BukkitTask transitionTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                fuwaTransitionTasks.remove(uuid);
                if (player.isOnline()) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, fallTicks, 0, false, false));
                }
            }, levTicks);
            fuwaTransitionTasks.put(uuid, transitionTask);
            scheduleEnd(uuid, (long) levTicks + fallTicks, null);
        } else {
            scheduleEnd(uuid, levTicks, null);
        }
    }

    // ── Gura Gura — Tremor-Tremor ────────────────────────────────────────────
    // Shockwave: damage + knockback all nearby entities. 4 mana, instant.

    private void activateGuraGura(Player player) {
        UUID uuid   = player.getUniqueId();
        double maxRadius = cfgD("gura_gura", "radius", 5.0);
        double damage = cfgD("gura_gura", "damage",  6.0);
        double kbMult = cfgD("gura_gura", "knockback-multiplier", 2.2);
        double kbY    = cfgD("gura_gura", "knockback-y", 0.5);
        int speedTicks = cfg("gura_gura", "shockwave-speed-ticks", 1);
        activeAbility.add(uuid);

        Location origin = player.getLocation().clone();
        player.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
        player.sendMessage(msg("ability.gura_gura"));

        Set<UUID> hitEntities = new HashSet<>();

        new org.bukkit.scheduler.BukkitRunnable() {
            double currentRadius = 1.0;
            @Override
            public void run() {
                if (currentRadius > maxRadius) {
                    this.cancel();
                    return;
                }

                // Dense particle ring — scales with radius so it stays solid at any size
                int particles = Math.max(12, (int) (currentRadius * 18));
                for (int i = 0; i < particles; i++) {
                    double angle = 2 * Math.PI * i / particles;
                    double x = Math.cos(angle) * currentRadius;
                    double z = Math.sin(angle) * currentRadius;
                    origin.getWorld().spawnParticle(Particle.EXPLOSION, origin.clone().add(x, 0.3, z), 1, 0, 0, 0, 0);
                }

                // Hit every entity within the current wave radius (hitEntities prevents double-hits)
                for (Entity entity : origin.getWorld().getNearbyEntities(origin, currentRadius + 1, 3, currentRadius + 1)) {
                    if (!(entity instanceof LivingEntity target) || entity.equals(player)) continue;
                    if (hitEntities.contains(target.getUniqueId())) continue;

                    double dist = target.getLocation().distance(origin);
                    if (dist <= currentRadius + 0.5) {
                        hitEntities.add(target.getUniqueId());
                        target.damage(damage, player);

                        Vector delta = target.getLocation().toVector().subtract(origin.toVector());
                        if (delta.lengthSquared() < 1e-6) delta = new Vector(1, 0, 0);
                        target.setVelocity(delta.normalize().multiply(kbMult).setY(kbY));
                    }
                }

                currentRadius += 1.5;
            }
        }.runTaskTimer(plugin, 0L, speedTicks);

        int steps = (int) Math.ceil((maxRadius - 1.0) / 1.5);
        int totalTicks = steps * speedTicks + 2;
        scheduleEnd(uuid, totalTicks, null);
    }

    // ── Damage event handlers ─────────────────────────────────────────────────

    /** Sube Sube arrow deflect | Supa Supa melee buff | Bomu Bomu explosion. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {

        // Sube Sube — deflect the next arrow that hits the player
        if (event.getEntity() instanceof Player defender
                && event.getDamager() instanceof Arrow arrow
                && arrowDeflects.remove(defender.getUniqueId())) {
            event.setCancelled(true);
            arrow.setVelocity(arrow.getVelocity().multiply(-1.0));
            defender.playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1.5f);
            return;
        }

        // Attacker-side effects
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        UUID uuid = attacker.getUniqueId();

        // Supa Supa — extra damage with empty main hand
        if (meleeBuffed.contains(uuid)
                && attacker.getInventory().getItemInMainHand().getType() == Material.AIR) {
            event.setDamage(event.getDamage() + cfgD("supa_supa", "bonus-damage", 2.5));
        }

        // Bomu Bomu — first hit triggers a non-block-breaking explosion
        if (pendingExplosion.remove(uuid)) {
            Location explodeLoc = event.getEntity().getLocation();
            float yield = (float) cfgD("bomu_bomu", "explosion-yield", 1.2);
            explodeLoc.getWorld().createExplosion(explodeLoc, yield, false, false, attacker);
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1f);
            // Cancel the window-expiry task and clear active flag now
            endAbilityEarly(uuid);
        }
    }

    /** Bane Bane — negate the next fall-damage event. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (fallNegates.remove(player.getUniqueId())) {
            event.setCancelled(true);
            // Derive bounce height from fall distance (like a slime block): v = sqrt(0.16 * h) * 0.8
            // The 0.8 damping factor gives ~80% energy return, matching slime-block feel.
            double fallDist = player.getFallDistance();
            double bounceY = Math.min(Math.sqrt(0.16 * Math.max(fallDist, 1.0)) * 0.8, 1.5);
            player.setVelocity(player.getVelocity().setY(bounceY));
            player.playSound(player.getLocation(), Sound.ENTITY_SLIME_JUMP, 1f, 1f);
            player.getWorld().spawnParticle(Particle.ITEM_SLIME, player.getLocation().add(0, 0.1, 0), 15, 0.3, 0.1, 0.3, 0.05);
            // Cancel the window-expiry task and release the ability slot
            endAbilityEarly(player.getUniqueId());
        }
    }

    // ── Cleanup on disconnect ─────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Cancel any pending end task to prevent it from touching a future ability
        BukkitTask task = endTasks.remove(uuid);
        if (task != null) task.cancel();

        BukkitTask fuwaTask = fuwaTransitionTasks.remove(uuid);
        if (fuwaTask != null) fuwaTask.cancel();
        sukeActive.remove(uuid);
        BukkitTask sukeTask = sukeArmorTasks.remove(uuid);
        if (sukeTask != null) sukeTask.cancel();
        activeAbility.remove(uuid);
        abilityCooldown.remove(uuid);
        arrowDeflects.remove(uuid);
        fallNegates.remove(uuid);
        pendingExplosion.remove(uuid);
        meleeBuffed.remove(uuid);
        List<Block> blocks = bariBlocks.remove(uuid);
        if (blocks != null) {
            blocks.forEach(b -> { if (b.getType() == Material.GLASS) b.setType(Material.AIR); });
        }
    }

    // ── Scheduling helpers ────────────────────────────────────────────────────

    /**
     * Schedules removal from {@link #activeAbility} after {@code delayTicks}.
     * Also sets a cooldown timestamp so the player sees remaining time if they
     * try to activate again while on cooldown.
     * Stores the task so it can be cancelled if the ability resolves early.
     * Any previously stored task for this player is cancelled before registering the new one.
     */
    private void scheduleEnd(UUID uuid, long delayTicks, Runnable extraCleanup) {
        BukkitTask existing = endTasks.remove(uuid);
        if (existing != null) existing.cancel();

        // Set cooldown expiry timestamp
        long cooldownMs = delayTicks * 50L; // 1 tick = 50ms
        abilityCooldown.put(uuid, System.currentTimeMillis() + cooldownMs);

        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            endTasks.remove(uuid);
            if (extraCleanup != null) extraCleanup.run();
            activeAbility.remove(uuid);
        }, Math.max(1, delayTicks));

        endTasks.put(uuid, task);
    }

    /**
     * Cancels the pending end task and immediately removes the player from
     * {@link #activeAbility}. Used when an ability resolves before its full duration
     * (e.g., Bane Bane lands, Bomu Bomu hits).
     * The cooldown timestamp remains so the player still has a cooldown after use.
     */
    private void endAbilityEarly(UUID uuid) {
        BukkitTask task = endTasks.remove(uuid);
        if (task != null) task.cancel();
        activeAbility.remove(uuid);
    }

    // ── Config helpers ────────────────────────────────────────────────────────

    private String msg(String key) {
        return ColorUtil.colorize(guiManager.getMessagesConfig().getString(key, "§cMissing: " + key));
    }

    private int cfg(String key, String field, int def) {
        return guiManager.getBalanceConfig().getInt("devil-fruits.abilities." + key + "." + field, def);
    }

    private double cfgD(String key, String field, double def) {
        return guiManager.getBalanceConfig().getDouble("devil-fruits.abilities." + key + "." + field, def);
    }

    private boolean isInSpawn(Player player) {
        for (ProtectRegion region : regionManager.getRegionsAt(player.getLocation())) {
            if (region.getType() == RegionType.SPAWN) return true;
        }
        return false;
    }
}
