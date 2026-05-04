package me.revqz.genPvP;

import me.revqz.genPvP.Items.AutoCompressorListener;
import me.revqz.genPvP.Items.CustomItemRegistry;
import me.revqz.genPvP.Items.LuffyArmorManager;
import me.revqz.genPvP.Items.OnePieceAbilityListener;
import me.revqz.genPvP.Protect.BlockTimerManager;
import me.revqz.genPvP.Protect.ProtectCommand;
import me.revqz.genPvP.Protect.ProtectListener;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.AutoBroadcast.AutoBroadcastManager;
import me.revqz.genPvP.Scoreboard.ScoreboardManager;
import me.revqz.genPvP.util.SpawnHandler;
import me.revqz.genPvP.util.SpawnCommand;
import me.revqz.genPvP.util.XpPickupListener;
import org.bukkit.plugin.java.JavaPlugin;

import me.revqz.genPvP.Database.DatabaseManager;
import me.revqz.genPvP.Database.LogManager;
import me.revqz.genPvP.Database.CrossServerMessenger;
import me.revqz.genPvP.Database.listeners.LogListener;
import me.revqz.genPvP.AntiDupe.AntiDupeManager;
import me.revqz.genPvP.AntiDupe.AntiDupeListener;

public final class GenPvP extends JavaPlugin {

    private BlockTimerManager blockTimerManager;
    private DatabaseManager databaseManager;
    private LogManager logManager;
    private CrossServerMessenger crossServerMessenger;
    private AntiDupeManager antiDupeManager;
    private RegionManager regionManager;
    private me.revqz.genPvP.PvPRooms.PvPRoomManager pvpRoomManager;
    private me.revqz.genPvP.Koth.KothManager kothManager;
    private AutoBroadcastManager autoBroadcastManager;
    private me.revqz.genPvP.Bank.BankManager bankManager;
    private me.revqz.genPvP.Shop.ShopManager shopManager;
    private ScoreboardManager scoreboardManager;
    private SpawnHandler spawnHandler;
    private me.revqz.genPvP.Combat.CombatListener combatListener;
    private me.revqz.genPvP.Combat.SpawnBorderGuard spawnBorderGuard;
    private SpawnCommand spawnCommand;
    private XpPickupListener xpPickupListener;
    private me.revqz.genPvP.Teams.TeamManager teamManager;
    private AutoCompressorListener autoCompressorListener;
    private CustomItemRegistry customItemRegistry;
    private me.revqz.genPvP.util.Nametag nametag;
    private me.revqz.genPvP.DevilFruits.DevilFruitManager devilFruitManager;
    private me.revqz.genPvP.DevilFruits.FruitRollManager fruitRollManager;
    private me.revqz.genPvP.SpawnDisplays.DisplaysManager displaysManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        spawnHandler = new SpawnHandler(this);
        getServer().getPluginManager().registerEvents(spawnHandler, this);

        getServer().getPluginManager().registerEvents(new me.revqz.genPvP.util.ChatListener(this), this);

        xpPickupListener = new XpPickupListener(this);
        getServer().getPluginManager().registerEvents(xpPickupListener, this);

        spawnCommand = new SpawnCommand(this, spawnHandler);
        var spawnCmd = getCommand("spawn");
        if (spawnCmd != null)
            spawnCmd.setExecutor(spawnCommand);

        databaseManager = new DatabaseManager(this);
        logManager = new LogManager(this, databaseManager);

        customItemRegistry = new CustomItemRegistry(this,
                databaseManager.getDatabase(), databaseManager.isMongoConnected());

        // Cross-server pub/sub framework — disabled by default until configured
        if (getConfig().getBoolean("cross-server.enabled", false)) {
            crossServerMessenger = new CrossServerMessenger(this, databaseManager);
            crossServerMessenger.start();
        }

        antiDupeManager = new AntiDupeManager(this, logManager);
        var antiDupeCmd = getCommand("antidupe");
        if (antiDupeCmd != null) {
            me.revqz.genPvP.AntiDupe.AntiDupeCommand adc =
                    new me.revqz.genPvP.AntiDupe.AntiDupeCommand(antiDupeManager, this);
            antiDupeCmd.setExecutor(adc);
            antiDupeCmd.setTabCompleter(adc);
        }

        regionManager = new RegionManager(this, databaseManager);

        // Teams
        teamManager = new me.revqz.genPvP.Teams.TeamManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(teamManager, this);
        me.revqz.genPvP.Teams.TeamCommand teamCommand = new me.revqz.genPvP.Teams.TeamCommand(this, teamManager);
        me.revqz.genPvP.Teams.TeamListener teamListener = new me.revqz.genPvP.Teams.TeamListener(teamManager,
                teamCommand);
        getServer().getPluginManager().registerEvents(teamListener, this);
        var teamCmd = getCommand("team");
        if (teamCmd != null) {
            teamCmd.setExecutor(teamCommand);
            teamCmd.setTabCompleter(teamCommand);
        }

        me.revqz.genPvP.Combat.CombatManager combatManager = new me.revqz.genPvP.Combat.CombatManager(this);
        combatListener = new me.revqz.genPvP.Combat.CombatListener(this, combatManager);
        getServer().getPluginManager().registerEvents(combatListener, this);

        spawnBorderGuard = new me.revqz.genPvP.Combat.SpawnBorderGuard(this, combatManager, regionManager);
        getServer().getPluginManager().registerEvents(spawnBorderGuard, this);

        var combatCmd = getCommand("combat");
        if (combatCmd != null) {
            me.revqz.genPvP.Combat.CombatCommand cc = new me.revqz.genPvP.Combat.CombatCommand(combatManager);
            combatCmd.setExecutor(cc);
            combatCmd.setTabCompleter(cc);
        }

        pvpRoomManager = new me.revqz.genPvP.PvPRooms.PvPRoomManager(this, regionManager);
        kothManager = new me.revqz.genPvP.Koth.KothManager(this, regionManager, logManager, databaseManager, teamManager);

        blockTimerManager = new BlockTimerManager(this);
        ProtectCommand protectCommand = new ProtectCommand(this, regionManager, databaseManager);

        var regionCmd = getCommand("region");
        if (regionCmd != null) {
            regionCmd.setExecutor(protectCommand);
            regionCmd.setTabCompleter(protectCommand);
        }

        var kothCmd = getCommand("koth");
        if (kothCmd != null) {
            me.revqz.genPvP.Koth.KothCommand kc = new me.revqz.genPvP.Koth.KothCommand(kothManager);
            kothCmd.setExecutor(kc);
            kothCmd.setTabCompleter(kc);
        }

        ProtectListener protectListener = new ProtectListener(this, regionManager, blockTimerManager, protectCommand);
        getServer().getPluginManager().registerEvents(protectListener, this);

        try {
            if (getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null ||
                    getServer().getPluginManager().getPlugin("WorldEdit") != null) {
                new me.revqz.genPvP.Protect.WorldEditHook(this, protectListener.getCreativePlacedBlocks());
            }
        } catch (Throwable t) {
            getLogger().warning("error: failed hook " + t.getMessage());
        }

        me.revqz.genPvP.Guide.GuideManager guideManager = new me.revqz.genPvP.Guide.GuideManager(this);
        var guideCmd = getCommand("guide");
        if (guideCmd != null) {
            guideCmd.setExecutor(guideManager);
        }
        getServer().getPluginManager().registerEvents(guideManager, this);

        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.Gens.GensListener(this, regionManager, protectCommand), this);

        getServer().getPluginManager().registerEvents(new LogListener(logManager), this);
        getServer().getPluginManager().registerEvents(new AntiDupeListener(antiDupeManager), this);

        me.revqz.genPvP.AutoPickup.AutoSmeltManager autoSmeltManager = new me.revqz.genPvP.AutoPickup.AutoSmeltManager();
        me.revqz.genPvP.AutoPickup.AutoSmeltListener autoSmeltListener = new me.revqz.genPvP.AutoPickup.AutoSmeltListener(
                this, autoSmeltManager);
        getServer().getPluginManager().registerEvents(autoSmeltListener, this);
        var autoSmeltCmd = getCommand("autosmelt");
        if (autoSmeltCmd != null)
            autoSmeltCmd.setExecutor(autoSmeltListener);

        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.AutoPickup.AutoPickupListener(regionManager, autoSmeltManager), this);
        getServer().getPluginManager()
                .registerEvents(new me.revqz.genPvP.PvPRooms.PvPRoomListener(pvpRoomManager, regionManager), this);

        autoBroadcastManager = new AutoBroadcastManager(this);

        bankManager = new me.revqz.genPvP.Bank.BankManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(bankManager, this);
        me.revqz.genPvP.Bank.MoneyShardManager moneyShardManager =
                new me.revqz.genPvP.Bank.MoneyShardManager(this);
        me.revqz.genPvP.Bank.BankMenu bankMenu = new me.revqz.genPvP.Bank.BankMenu(this, bankManager, moneyShardManager);
        getServer().getPluginManager().registerEvents(bankMenu, this);
        var bankCmd = getCommand("bank");
        if (bankCmd != null) {
            me.revqz.genPvP.Bank.BankCommand bc =
                    new me.revqz.genPvP.Bank.BankCommand(this, bankManager, bankMenu, moneyShardManager);
            bankCmd.setExecutor(bc);
            bankCmd.setTabCompleter(bc);
        }
        var withdrawCmd = getCommand("withdraw");
        if (withdrawCmd != null)
            withdrawCmd.setExecutor(new me.revqz.genPvP.Bank.WithdrawCommand(this, bankManager, moneyShardManager));
        var depositCmd = getCommand("deposit");
        if (depositCmd != null)
            depositCmd.setExecutor(new me.revqz.genPvP.Bank.DepositCommand(this, bankManager, moneyShardManager));
        var payCmd = getCommand("pay");
        if (payCmd != null) {
            me.revqz.genPvP.Bank.PayCommand pc = new me.revqz.genPvP.Bank.PayCommand(this, bankManager);
            payCmd.setExecutor(pc);
            payCmd.setTabCompleter(pc);
        }
        var settingsCmd = getCommand("settings");
        if (settingsCmd != null) {
            me.revqz.genPvP.Bank.SettingsCommand sc = new me.revqz.genPvP.Bank.SettingsCommand(this, bankManager);
            settingsCmd.setExecutor(sc);
            settingsCmd.setTabCompleter(sc);
        }
        me.revqz.genPvP.CommandSpy.CommandSpyManager commandSpy =
                new me.revqz.genPvP.CommandSpy.CommandSpyManager(this);
        getServer().getPluginManager().registerEvents(commandSpy, this);
        var commandSpyCmd = getCommand("commandspy");
        if (commandSpyCmd != null) commandSpyCmd.setExecutor(commandSpy);

        me.revqz.genPvP.Kit.KitManager kitManager =
                new me.revqz.genPvP.Kit.KitManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(kitManager, this);
        var kitCmd = getCommand("kit");
        if (kitCmd != null) {
            me.revqz.genPvP.Kit.KitCommand kc = new me.revqz.genPvP.Kit.KitCommand(kitManager);
            kitCmd.setExecutor(kc);
            kitCmd.setTabCompleter(kc);
        }

        me.revqz.genPvP.Prestige.PrestigeManager prestigeManager = new me.revqz.genPvP.Prestige.PrestigeManager(this,
                databaseManager);
        getServer().getPluginManager().registerEvents(prestigeManager, this);
        var prestigeCmd = getCommand("prestige");
        if (prestigeCmd != null) {
            me.revqz.genPvP.Prestige.PrestigeCommand pc = new me.revqz.genPvP.Prestige.PrestigeCommand(prestigeManager);
            prestigeCmd.setExecutor(pc);
            prestigeCmd.setTabCompleter(pc);
        }

        var levelCmd = getCommand("level");
        if (levelCmd != null) {
            me.revqz.genPvP.Prestige.LevelCommand lc = new me.revqz.genPvP.Prestige.LevelCommand(prestigeManager);
            levelCmd.setExecutor(lc);
            levelCmd.setTabCompleter(lc);
        }

        // XP sources: register listener (kills + block breaks) and /xp admin command
        me.revqz.genPvP.Prestige.XpListener xpListener = new me.revqz.genPvP.Prestige.XpListener(this, prestigeManager);
        getServer().getPluginManager().registerEvents(xpListener, this);
        var xpCmd = getCommand("xp");
        if (xpCmd != null) {
            me.revqz.genPvP.Prestige.XpCommand xc = new me.revqz.genPvP.Prestige.XpCommand(prestigeManager);
            xpCmd.setExecutor(xc);
            xpCmd.setTabCompleter(xc);
        }

        me.revqz.genPvP.Shop.ItemShopManager itemShopManager =
                new me.revqz.genPvP.Shop.ItemShopManager(this, bankManager);
        getServer().getPluginManager().registerEvents(itemShopManager, this);
        var itemShopCmd = getCommand("itemshop");
        if (itemShopCmd != null) {
            itemShopCmd.setExecutor(itemShopManager);
            itemShopCmd.setTabCompleter(itemShopManager);
        }

        shopManager = new me.revqz.genPvP.Shop.ShopManager(this, bankManager, prestigeManager);
        getServer().getPluginManager().registerEvents(shopManager, this);
        var shopCmd = getCommand("shop");
        if (shopCmd != null) {
            me.revqz.genPvP.Shop.ShopCommand sc = new me.revqz.genPvP.Shop.ShopCommand(shopManager, itemShopManager);
            shopCmd.setExecutor(sc);
            shopCmd.setTabCompleter(sc);
        }

        me.revqz.genPvP.Sell.SellMenu sellMenu = new me.revqz.genPvP.Sell.SellMenu(this);
        getServer().getPluginManager().registerEvents(sellMenu, this);
        me.revqz.genPvP.Sell.SellManager sellManager = new me.revqz.genPvP.Sell.SellManager(this, bankManager);
        var sellCmd = getCommand("sell");
        if (sellCmd != null) {
            me.revqz.genPvP.Sell.SellCommand sc = new me.revqz.genPvP.Sell.SellCommand(sellMenu, sellManager);
            sellCmd.setExecutor(sc);
            sellCmd.setTabCompleter(sc);
        }

        autoCompressorListener = new AutoCompressorListener(this);
        getServer().getPluginManager().registerEvents(autoCompressorListener, this);

        var genpvpCmd = getCommand("genpvp");
        if (genpvpCmd != null) {
            GenPvPCommand genpvpCommand = new GenPvPCommand(this, shopManager, itemShopManager,
                    autoCompressorListener, customItemRegistry);
            genpvpCmd.setExecutor(genpvpCommand);
            genpvpCmd.setTabCompleter(genpvpCommand);
        }

        var mysqlCmd = getCommand("mysql");
        if (mysqlCmd != null) {
            me.revqz.genPvP.Database.MysqlConvertCommand mcc =
                    new me.revqz.genPvP.Database.MysqlConvertCommand(this, databaseManager);
            mysqlCmd.setExecutor(mcc);
            mysqlCmd.setTabCompleter(mcc);
        }

        var feedbackCmd = getCommand("feedback");
        if (feedbackCmd != null) {
            feedbackCmd.setExecutor(new me.revqz.genPvP.util.FeedbackCommand(this));
        }

        me.revqz.genPvP.Stats.StatsManager statsManager = new me.revqz.genPvP.Stats.StatsManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(statsManager, this);
        getServer().getPluginManager().registerEvents(new me.revqz.genPvP.Stats.StatsListener(statsManager), this);

        // Devil Fruits
        devilFruitManager =
                new me.revqz.genPvP.DevilFruits.DevilFruitManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(devilFruitManager, this);

        // FruitGUIManager is created first so its GeneralMessages.yml (messages + mana) is available
        me.revqz.genPvP.DevilFruits.FruitGUIManager fruitGUIManager =
                new me.revqz.genPvP.DevilFruits.FruitGUIManager(this, devilFruitManager, regionManager);
        getServer().getPluginManager().registerEvents(fruitGUIManager, this);

        me.revqz.genPvP.DevilFruits.ManaManager manaManager =
                new me.revqz.genPvP.DevilFruits.ManaManager(this, fruitGUIManager.getMessagesConfig());
        getServer().getPluginManager().registerEvents(manaManager, this);

        me.revqz.genPvP.DevilFruits.FruitSlotManager fruitSlotManager =
                new me.revqz.genPvP.DevilFruits.FruitSlotManager(
                        this, devilFruitManager, fruitGUIManager, fruitGUIManager.getMessagesConfig());
        fruitSlotManager.setRegionManager(regionManager);
        getServer().getPluginManager().registerEvents(fruitSlotManager, this);

        // Wire equip-change callback: runs on main thread (fireEquipChange guarantees this)
        devilFruitManager.setOnEquipChange(uuid -> {
            org.bukkit.entity.Player p = getServer().getPlayer(uuid);
            if (p != null) fruitSlotManager.updateFruitSlot(p);
        });
        fruitGUIManager.setSlotManager(fruitSlotManager);

        me.revqz.genPvP.DevilFruits.ParameciaAbilityListener parameciaAbilities =
                new me.revqz.genPvP.DevilFruits.ParameciaAbilityListener(
                        this, devilFruitManager, manaManager, regionManager,
                        fruitGUIManager, fruitSlotManager);
        getServer().getPluginManager().registerEvents(parameciaAbilities, this);

        me.revqz.genPvP.DevilFruits.ZoanAbilityListener zoanAbilities =
                new me.revqz.genPvP.DevilFruits.ZoanAbilityListener(
                        this, devilFruitManager, manaManager, regionManager,
                        fruitGUIManager, fruitSlotManager);
        getServer().getPluginManager().registerEvents(zoanAbilities, this);

        me.revqz.genPvP.DevilFruits.LogiaAbilityListener logiaAbilities =
                new me.revqz.genPvP.DevilFruits.LogiaAbilityListener(
                        this, devilFruitManager, manaManager, regionManager,
                        fruitGUIManager, fruitSlotManager);
        getServer().getPluginManager().registerEvents(logiaAbilities, this);

        // Fruit Roll system — rolling slot-machine GUI
        fruitRollManager =
                new me.revqz.genPvP.DevilFruits.FruitRollManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(fruitRollManager, this);

        me.revqz.genPvP.DevilFruits.FruitRollGUI fruitRollGUI =
                new me.revqz.genPvP.DevilFruits.FruitRollGUI(
                        this, devilFruitManager, fruitRollManager, fruitGUIManager, bankManager);
        getServer().getPluginManager().registerEvents(fruitRollGUI, this);

        // Devil Fruit Shard — PvP kill-drop economy + physical currency for shops
        me.revqz.genPvP.DevilFruits.DevilFruitShardListener devilFruitShardListener =
                new me.revqz.genPvP.DevilFruits.DevilFruitShardListener(this, fruitGUIManager.getMessagesConfig());
        getServer().getPluginManager().registerEvents(devilFruitShardListener, this);
        shopManager.setDevilShardListener(devilFruitShardListener);
        itemShopManager.setDevilShardListener(devilFruitShardListener);

        var fruitCmd = getCommand("fruit");
        if (fruitCmd != null) {
            me.revqz.genPvP.DevilFruits.FruitCommand fc =
                    new me.revqz.genPvP.DevilFruits.FruitCommand(
                            this, devilFruitManager, fruitGUIManager, fruitRollManager, fruitRollGUI);
            fc.setShardListener(devilFruitShardListener);
            fruitCmd.setExecutor(fc);
            fruitCmd.setTabCompleter(fc);
        }

        var rollCmd = getCommand("roll");
        if (rollCmd != null) {
            me.revqz.genPvP.DevilFruits.RollCommand rc =
                    new me.revqz.genPvP.DevilFruits.RollCommand(fruitGUIManager, fruitRollManager, fruitRollGUI);
            rollCmd.setExecutor(rc);
            rollCmd.setTabCompleter(rc);
        }

        var dbCmd = getCommand("database");
        if (dbCmd != null) {
            me.revqz.genPvP.Database.DatabaseOptimizeCommand dbc =
                    new me.revqz.genPvP.Database.DatabaseOptimizeCommand(
                            this, databaseManager,
                            bankManager, prestigeManager, statsManager,
                            teamManager, devilFruitManager, fruitRollManager);
            dbCmd.setExecutor(dbc);
            dbCmd.setTabCompleter(dbc);
        }

        // Standalone /logia, /paramecia, /zoan commands
        for (String typeName : new String[]{"logia", "paramecia", "zoan"}) {
            var cmd = getCommand(typeName);
            if (cmd != null) {
                me.revqz.genPvP.DevilFruits.FruitType type =
                        me.revqz.genPvP.DevilFruits.FruitType.valueOf(typeName.toUpperCase());
                cmd.setExecutor((sender, command, label, args) -> {
                    if (!(sender instanceof org.bukkit.entity.Player p)) {
                        sender.sendMessage("Only players can use this command.");
                        return true;
                    }
                    fruitGUIManager.openType(p, type);
                    return true;
                });
            }
        }

        // Luffy Armor — Haki block (10% per piece) + full-set +20% fruit damage
        LuffyArmorManager luffyArmorManager = new LuffyArmorManager(this, customItemRegistry, devilFruitManager);
        getServer().getPluginManager().registerEvents(luffyArmorManager, this);

        // OnePiece weapon abilities: luffy_sword swipe, pirate_axe throw, pirate_sword ghost crew
        getServer().getPluginManager().registerEvents(
                new OnePieceAbilityListener(this, customItemRegistry, regionManager), this);
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.Items.BoxSphereListener(this, customItemRegistry, regionManager), this);

        // Head ability events (registration is now under /genpvp head)
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.Items.Heads.HeadAbilityListener(this, customItemRegistry, regionManager, logManager), this);

        // Unified player-data loader — fires at LOW priority (before each manager's
        // NORMAL handler)
        // so all caches are populated in one async task / one DB round trip.
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.Database.PlayerDataLoader(
                        this, databaseManager, bankManager, prestigeManager, statsManager,
                        devilFruitManager, kitManager, fruitRollManager),
                this);

        me.revqz.genPvP.util.SkinCache skinCache = new me.revqz.genPvP.util.SkinCache(this);
        getServer().getPluginManager().registerEvents(skinCache, this);

        me.revqz.genPvP.util.WelcomeMessageListener welcomeMessage =
                new me.revqz.genPvP.util.WelcomeMessageListener(this, skinCache);
        getServer().getPluginManager().registerEvents(welcomeMessage, this);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GenPvPExpansion(this, pvpRoomManager, kothManager, bankManager, prestigeManager, statsManager,
                    skinCache, teamManager, devilFruitManager, luffyArmorManager, manaManager).register();
        }

        scoreboardManager = new ScoreboardManager(this, kothManager);
        getServer().getPluginManager().registerEvents(scoreboardManager, this);

        me.revqz.genPvP.Options.OptionsManager optionsManager =
                new me.revqz.genPvP.Options.OptionsManager(this, databaseManager, kitManager, scoreboardManager);
        optionsManager.setBankManager(bankManager);
        getServer().getPluginManager().registerEvents(optionsManager, this);
        var optionsCmd = getCommand("options");
        if (optionsCmd != null) optionsCmd.setExecutor(optionsManager);

        getServer().getPluginManager().registerEvents(new me.revqz.genPvP.util.PressurePlateLauncher(), this);

        nametag = new me.revqz.genPvP.util.Nametag(this);
        getServer().getPluginManager().registerEvents(nametag, this);

        // Spawn displays (discord / store interaction holograms)
        displaysManager = new me.revqz.genPvP.SpawnDisplays.DisplaysManager(this);
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.SpawnDisplays.DisplaysListener(this, displaysManager), this);
        getServer().getScheduler().runTaskLater(this, displaysManager::summon, 1L);

        // Particle circle effect — start on next tick so the world is fully loaded
        getServer().getScheduler().runTaskLater(this, () ->
                new me.revqz.genPvP.util.ParticleCircleEffect(this).start(), 1L);

        // Double-jump — permission genpvp.doublejump, SPAWN regions only
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.util.DoubleJumpListener(this, regionManager), this);
    }

    public RegionManager getRegionManager() {
        return regionManager;
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    public SpawnHandler getSpawnHandler() {
        return spawnHandler;
    }

    public XpPickupListener getXpPickupListener() {
        return xpPickupListener;
    }

    @Override
    public void onDisable() {
        // Stop HTTP threads first — keeps the old classloader from leaking into the
        // next Plugman reload, which would prevent MongoDB from reconnecting.
        me.revqz.genPvP.Webhook.DiscordWebhook.shutdown();

        if (blockTimerManager != null) {
            blockTimerManager.cancelAll();
        }
        if (autoBroadcastManager != null) {
            autoBroadcastManager.shutdown();
        }
        if (spawnBorderGuard != null) {
            spawnBorderGuard.shutdown();
        }
        if (combatListener != null) {
            combatListener.shutdown();
        }
        if (scoreboardManager != null) {
            scoreboardManager.shutdown();
        }
        if (spawnCommand != null) {
            spawnCommand.shutdown();
        }
        if (crossServerMessenger != null) {
            crossServerMessenger.shutdown();
        }
        if (displaysManager != null) {
            displaysManager.shutdown();
        }
        if (nametag != null) {
            nametag.shutdown();
        }
        if (teamManager != null) {
            teamManager.shutdown();
        }
        // Flush all in-memory fruit data to MongoDB BEFORE closing the connection.
        // The async writes from giveFruit/consumeRoll may still be queued when the
        // scheduler shuts down — this synchronous pass guarantees persistence.
        if (devilFruitManager != null) {
            devilFruitManager.saveAll();
        }
        if (fruitRollManager != null) {
            fruitRollManager.saveAll();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
    }
}
// GUNS - IN GUNS DIRECTORY @GUNS

// TODO: GUN MODEL NAME: guns/vs_revolver
// TODO: Add the dependency ItemsAdder to animate the guns
// TODO: Add a sort of glow infront of the gun when it shots.
// TODO: Use a texture named items/ammo_box - When right clicked will give you
// 32 reguler ammo.
// TODO: Use ammo texture items/ammo - Used to shot
// TODO: Add a configurable reloading cooldown in config (mag size will be 1)
// TODO: Add configurable damage to other people.
// TODO: Because the gun is going to be a flint knock add a configurable
// velocity for the person who shot it and got shot with it
// TODO: COMMAND TO GIVE THE GUNS FOR OPS ONLY: /gun give <player>
// TODO: COMMAND TO GIVE THE AMMO FOR OPS ONLY: /ammo give <player>
// <box/reguler_ammo> <amount>

// PROTECT - PROTECT STUFF [DONE - SEE src/main/java/me/revqz/genPvP/Protect/]

// TODO: Add FastAsyncWorldEdit as a dependency [DONE]
// TODO: Add Placeholder API as a dependency [DONE]
// TODO: Add a configurable clock in config for each block placed by players in
// @RULE: SURVIVAL ONLY, When block timer == 0 block will be set to air with a
// sound (make it configurable). [DONE]
// TODO: Add region defineing e.g pos1 and pos2 in worldedit and then I run
// command /region define <region name> [DONE]
// TODO: Add /region bypass (for OPs Only) when run it will make whoever ran it
// able to bypass region protection. [DONE]
// SPECFIC REGION RULES:
// TODO: PEOPLE CANNOT BREAK ANY BLOCK PLACED IN CREATIVE UNLESS ITS IN A REGION
// WITH RULE BREAK ALLOW [DONE]
// TODO: Region Spawn rules: People can NOT take damage from anything, Place /
// break blocks, Spawn Mobs, Recive knockback, Use Flint and steal, In any way
// kill / hurt people / push them. - Can interact with anvils , Enchant tables,
// grindstones , crafting tables etc... [DONE]
// TODO: Region Gens rules: People can NOT take damage from anything, Place ,
// Spawn Mobs, Recive knockback, Use Flint and steal, In any way kill / hurt
// people / push them. - People can break blocks [DONE]
// TODO: Region OPMines: People can fight , Spawn mobs, Recive knockback, No use
// of flint and steal, Cant break /place blocks. [DONE]
// TODO: Region OPMinesGens: People can fight , Spawn mobs, Recive knockback, No
// use of flint and steal, place blocks. - People can break blocks [DONE]
// TODO: Region Koth: People can fight , Recive knockback, No use of flint and
// steal, place blocks. - People cant break blocks [DONE]
// TODO: Region KothCapture: People can fight , Recive knockback, No use of
// flint and steal, Cant place blocks. - People cant break blocks [DONE]
// TODO: Region PvProom1: People can fight , Spawn mobs, Recive knockback, No
// use of flint and steal, [DONE]
// TODO: Region PvProom2: People can fight , Spawn mobs, Recive knockback, No
// use of flint and steal, [DONE]
// TODO: Region PvProomGate1: People can fight , Spawn mobs, Recive knockback,
// No use of flint and steal, [DONE]
// TODO: Region PvProomGate2: People can fight , Spawn mobs, Recive knockback,
// No use of flint and steal, [DONE]
// TODO: Pit: People can fight , Recive knockback, No use of flint and steal,
// cant place blocks. - People cant break blocks [DONE]

// GENS: [DONE - SEE src/main/java/me/revqz/genPvP/Gens/GensListener.java]
// DONE: Blocks in GENS and OPMINESGENS regions regenerate instantly (next tick)
// via a tick-safe,
// concurrent-safe runTaskLater scheduler. Drops and XP fire normally. No
// console output.
// Regen speed is configurable via gens-regen-ticks in config.yml.
// FAWE/schematic-placed blocks in these regions are fully supported via
// WorldEditHook.

// DATABASE: [DONE - SEE src/main/java/me/revqz/genPvP/Database/]
// DONE: MongoDB connected and verified with a real ping command on startup
// (DatabaseManager).
// DONE: Redis → MongoDB pipeline: events enqueue instantly (non-blocking),
// async writer batches to Redis
// every 1 s, flush task moves Redis → MongoDB every 5 s. If Redis is down,
// writes go directly
// to MongoDB. If both are down, up to 10 000 docs are held in memory and
// retried.
// DONE: Logs: joins, leaves, kills/deaths (LogListener), KOTH start/stop/win
// (KothManager), dupe (AntiDupeManager).
// Economy and Shop log methods exist (logEconomy / logShop) — wire them up when
// those systems are built.
// DONE: Player names stored alongside UUIDs in every document for easy Atlas
// queries.
// DONE: Compound indexes on {type, timestamp} and {player, timestamp} plus
// sparse index on winner.
// TODO (future): Cross-server support via Redis pub/sub — add a subscriber that
// forwards log events
// from other servers into the same genpvp.logs collection.

// ANTIDUPE:
// TODO: Assign every unstackable item a unique ITEM-ID that means that item
// will be unique , If a player has 2 items with the same UUID it will send a
// messege in chat (configurable in config) for people with OP and log in in
// MongoDB.
// TODO: Add some sort of detection for stackable items.
// TODO: USE THE FOLLOWING (BELOW)
// Atomic Data Structures , Database Auditing, PDC (Persistent Data Container)
// Verification, PDC Stamping (Stackable), World vs. Memory Sanity Checks
// Per-Gen Tick Guards
// If you want more details about each tell me - Add more of your own for null
// handling and packet detection.

// PVPROOMS: [DONE - SEE src/main/java/me/revqz/genPvP/PvPRooms/]
// DONE: 2-player trigger on PVPROOM1 / PVPROOM2 — when exactly 2 players are
// detected the gate
// region (PVPROOMGATE1 / PVPROOMGATE2) is instantly filled with BARRIER blocks.
// DONE: Entry prevention — ALL teleport causes (ender pearl, /tp, plugin,
// chorus fruit …) are
// intercepted by PvPRoomListener and cancelled for non-participants during
// FIGHTING / LOOTING.
// Barrier blocks block physical movement. Ops can still intervene if needed.
// DONE: Command block — non-participant players inside the room cannot run any
// command during
// an active fight or loot phase; operators are exempt.
// DONE: On participant death or disconnect — loot phase starts (2.5 min = 150
// s). Winner sees:
// title "WIN!" (&#4498DB / &#70BAF5 gradient), subtitle "&#4498DB<seconds>
// Seconds left to loot."
// Title uses stay=25 ticks so minor server lag never causes a visible gap
// between updates.
// Loot task handle is stored; calling resetRoom() cancels it immediately
// without waiting.
// DONE: At t=0 (or on winner disconnect) — gate region is cleared to AIR and
// room returns to WAITING.
// DONE: Dual-quit / simultaneous death edge case — handled; room resets
// cleanly.
// DONE: Placeholders via GenPvPExpansion (merged — fixes silent KOTH
// placeholder breakage):
// %genpvp_pvproom1_max%, %genpvp_pvproom1_in%, %genpvp_pvproom2_max%,
// %genpvp_pvproom2_in%

// DONE: PvPRoom2 works identically to PvPRoom1 — gate sealed with PVPROOMGATE2
// on fight start, cleared on reset.

// KOTH: [DONE - SEE src/main/java/me/revqz/genPvP/Koth/]
// DONE: Auto-start every koth.interval seconds (config.yml). /koth start|stop
// for OPs.
// DONE: Single-player capture zone (KothCapture region). Timer resets if the
// capturer leaves or a second player enters.
// DONE: Configurable capture_time and reward_command (with %player%
// placeholder).
// DONE: All messages fully configurable in config.yml under koth.messages —
// supports single strings AND
// multi-line YAML lists. Color codes use & prefix. Pre-translated at load time
// (zero overhead per broadcast).
// Placeholders: %player% (winner / capturer name), %time% (seconds remaining).
// DONE: PlaceholderAPI — %genpvp_time_till_next_koth%,
// %genpvp_time_till_koth_capture%,
// %genpvp_capturer_koth%, %genpvp_koth_top_winner_name_<1-10>%,
// %genpvp_koth_top_winner_number_<1-10>%.
// DONE: MongoDB logging — START, STOP, WIN events with winner UUID + name.
// Leaderboard aggregated async.
