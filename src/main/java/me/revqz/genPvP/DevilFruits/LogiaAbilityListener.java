package me.revqz.genPvP.DevilFruits;

import me.revqz.genPvP.GenPvP;
import me.revqz.genPvP.Protect.ProtectRegion;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.Protect.flags.RegionType;
import me.revqz.genPvP.util.ColorUtil;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LogiaAbilityListener implements Listener {

    private final GenPvP plugin;
    private final DevilFruitManager fruitManager;
    private final ManaManager manaManager;
    private final RegionManager regionManager;
    private final FruitGUIManager guiManager;
    private final FruitSlotManager fruitSlotManager;

    private final Map<UUID, Long>       abilityCooldown = new ConcurrentHashMap<>();
    private final Set<UUID>             activeAbility   = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> endTasks        = new ConcurrentHashMap<>();

    private final Map<UUID, Map<Block, Material>> icedBlocks = new ConcurrentHashMap<>();

    public LogiaAbilityListener(GenPvP plugin, DevilFruitManager fruitManager,
                                ManaManager manaManager, RegionManager regionManager,
                                FruitGUIManager guiManager, FruitSlotManager fruitSlotManager) {
        this.plugin           = plugin;
        this.fruitManager     = fruitManager;
        this.manaManager      = manaManager;
        this.regionManager    = regionManager;
        this.guiManager       = guiManager;
        this.fruitSlotManager = fruitSlotManager;
    }

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
        if (fruit == null || fruit.getType() != FruitType.LOGIA) return;

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
            case "goro_goro" -> activateGoroGoro(player);
            case "hie_hie"   -> activateHieHie(player);
            case "yami_yami" -> activateYamiYami(player);
        }
    }

    private void activateGoroGoro(Player player) {
        UUID   uuid          = player.getUniqueId();
        int    cooldownTicks = cfg("goro_goro", "cooldown-seconds", 12) * 20;
        double rayDist       = cfgD("goro_goro", "raycast-distance", 15.0);
        double aoeDamage     = cfgD("goro_goro", "aoe-damage", 5.0);
        double aoeRadius     = cfgD("goro_goro", "aoe-radius", 4.0);

        activeAbility.add(uuid);

        RayTraceResult result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getLocation().getDirection(),
                rayDist,
                FluidCollisionMode.NEVER,
                true
        );

        if (result == null || result.getHitBlock() == null) {
            player.sendMessage(msg("ability.goro_goro_miss"));
            scheduleEnd(uuid, cooldownTicks, null);
            return;
        }

        Block hitBlock = result.getHitBlock();
        
        Location landLoc = hitBlock.getLocation().add(0.5, 1.0, 0.5);
        landLoc.setYaw(player.getLocation().getYaw());
        landLoc.setPitch(player.getLocation().getPitch());

        if (isInSpawn(landLoc)) {
            player.sendMessage(msg("ability.goro_goro_miss"));
            scheduleEnd(uuid, cooldownTicks, null);
            return;
        }

        player.teleport(landLoc);

        player.getWorld().strikeLightningEffect(landLoc);

        for (Entity nearby : player.getNearbyEntities(aoeRadius, aoeRadius, aoeRadius)) {
            if (!(nearby instanceof LivingEntity target)) continue;
            if (target.equals(player)) continue;
            if (target instanceof Player tp && isInSpawn(tp)) continue;
            target.damage(aoeDamage, player);
        }

        player.playSound(landLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
        player.sendMessage(msg("ability.goro_goro"));
        scheduleEnd(uuid, cooldownTicks, null);
    }

    private void activateHieHie(Player player) {
        UUID   uuid          = player.getUniqueId();
        int    cooldownTicks = cfg("hie_hie", "cooldown-seconds", 14) * 20;
        int    radius        = cfg("hie_hie", "radius", 3);
        int    iceTicks      = cfg("hie_hie", "ice-duration-seconds", 8) * 20;
        int    slowAmp       = cfg("hie_hie", "slowness-amplifier", 3);
        int    slowTicks     = cfg("hie_hie", "slowness-duration-seconds", 5) * 20;
        int    fatigueTicks  = cfg("hie_hie", "fatigue-duration-seconds", 5) * 20;
        int    fatigueAmp    = cfg("hie_hie", "fatigue-amplifier", 0);

        activeAbility.add(uuid);

        World    world  = player.getWorld();
        Location origin = player.getLocation();
        int cx = origin.getBlockX();
        int cy = origin.getBlockY();
        int cz = origin.getBlockZ();

        Map<Block, Material> placed = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int bx = bx(cx, dx);
                int bz = bz(cz, dz);

                for (int by = cy; by >= cy - 5; by--) {
                    Block b = world.getBlockAt(bx, by, bz);
                    Material type = b.getType();

                    if (!type.isSolid()) continue;

                    if (type == Material.FROSTED_ICE || type == Material.ICE
                            || type == Material.PACKED_ICE || type == Material.BLUE_ICE) break;

                    if (isInSpawn(b.getLocation())) break;

                    placed.put(b, type);
                    b.setType(Material.FROSTED_ICE);
                    break;
                }
            }
        }

        if (!placed.isEmpty()) icedBlocks.put(uuid, placed);

        for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof Player target)) continue;
            if (target.equals(player)) continue;
            if (isInSpawn(target)) continue;
            target.addPotionEffect(
                    new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, slowAmp, false, true));
            target.addPotionEffect(
                    new PotionEffect(PotionEffectType.MINING_FATIGUE, fatigueTicks, fatigueAmp, false, true));
        }

        final Map<Block, Material> toRestore = new HashMap<>(placed);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            restoreIce(toRestore);
            icedBlocks.remove(uuid, toRestore);
        }, iceTicks);

        player.playSound(origin, Sound.BLOCK_GLASS_PLACE, 1f, 0.5f);
        world.spawnParticle(Particle.SNOWFLAKE, origin.clone().add(0, 0.5, 0),
                60, radius, 0.3, radius, 0.04);
        player.sendMessage(msg("ability.hie_hie"));
        scheduleEnd(uuid, cooldownTicks, null);
    }

    private void restoreIce(Map<Block, Material> blocks) {
        for (Map.Entry<Block, Material> entry : blocks.entrySet()) {
            Block b = entry.getKey();
            
            Material current = b.getType();
            if (current == Material.FROSTED_ICE || current == Material.WATER) {
                b.setType(entry.getValue());
            }
        }
    }

    private static int bx(int cx, int dx) { return cx + dx; }
    private static int bz(int cz, int dz) { return cz + dz; }

    private void activateYamiYami(Player player) {
        UUID   uuid          = player.getUniqueId();
        int    cooldownTicks = cfg("yami_yami", "cooldown-seconds", 16) * 20;
        double radius        = cfgD("yami_yami", "radius", 12.0);
        double pullStrength  = cfgD("yami_yami", "pull-strength", 2.5);
        int    blindTicks    = cfg("yami_yami", "blindness-duration-seconds", 3) * 20;

        activeAbility.add(uuid);

        Location playerLoc = player.getLocation();

        for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
            if (!(nearby instanceof LivingEntity target)) continue;
            if (target.equals(player)) continue;
            if (target instanceof Player tp && isInSpawn(tp)) continue;

            Vector pull = playerLoc.toVector()
                    .subtract(nearby.getLocation().toVector())
                    .normalize()
                    .multiply(pullStrength);
            nearby.setVelocity(pull);

            target.addPotionEffect(
                    new PotionEffect(PotionEffectType.BLINDNESS, blindTicks, 0, false, true));
        }

        player.playSound(playerLoc, Sound.ENTITY_WITHER_AMBIENT, 1f, 0.3f);
        player.playSound(playerLoc, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 0.7f, 0.5f);
        playerLoc.getWorld().spawnParticle(Particle.DUST,
                playerLoc.clone().add(0, 1, 0),
                80, radius * 0.6, 1.5, radius * 0.6, 0,
                new Particle.DustOptions(Color.fromRGB(28, 0, 50), 1.8f));
        player.sendMessage(msg("ability.yami_yami"));
        scheduleEnd(uuid, cooldownTicks, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        BukkitTask task = endTasks.remove(uuid);
        if (task != null) task.cancel();

        activeAbility.remove(uuid);
        abilityCooldown.remove(uuid);

        Map<Block, Material> remaining = icedBlocks.remove(uuid);
        if (remaining != null) restoreIce(remaining);
    }

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

    private boolean isInSpawn(Player player) {
        return isInSpawn(player.getLocation());
    }

    private boolean isInSpawn(Location location) {
        for (ProtectRegion region : regionManager.getRegionsAt(location)) {
            if (region.getType() == RegionType.SPAWN) return true;
        }
        return false;
    }

    private String msg(String key) {
        return ColorUtil.colorize(guiManager.getMessagesConfig().getString(key, "§cMissing: " + key));
    }

    private int cfg(String key, String field, int def) {
        return guiManager.getBalanceConfig().getInt("devil-fruits.abilities." + key + "." + field, def);
    }

    private double cfgD(String key, String field, double def) {
        return guiManager.getBalanceConfig().getDouble("devil-fruits.abilities." + key + "." + field, def);
    }
}
