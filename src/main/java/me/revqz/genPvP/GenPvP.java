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
    private me.revqz.genPvP.Database.PlayerDataLoader playerDataLoader;

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

        ProtectListener protectListener = new ProtectListener(this, regionManager, blockTimerManager, protectCommand, customItemRegistry);
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

        new me.revqz.genPvP.Anvil.AnvilManager(this, regionManager);

        me.revqz.genPvP.PitNetherite.PitNetheriteManager pitNetheriteManager =
                new me.revqz.genPvP.PitNetherite.PitNetheriteManager(this, regionManager);

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
        var balanceCmd = getCommand("balance");
        if (balanceCmd != null) {
            me.revqz.genPvP.Bank.BalanceCommand bc = new me.revqz.genPvP.Bank.BalanceCommand(this, bankManager);
            balanceCmd.setExecutor(bc);
            balanceCmd.setTabCompleter(bc);
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
                    autoCompressorListener, customItemRegistry, pitNetheriteManager);
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

        devilFruitManager =
                new me.revqz.genPvP.DevilFruits.DevilFruitManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(devilFruitManager, this);

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

        fruitRollManager =
                new me.revqz.genPvP.DevilFruits.FruitRollManager(this, databaseManager);
        getServer().getPluginManager().registerEvents(fruitRollManager, this);

        me.revqz.genPvP.DevilFruits.FruitRollGUI fruitRollGUI =
                new me.revqz.genPvP.DevilFruits.FruitRollGUI(
                        this, devilFruitManager, fruitRollManager, fruitGUIManager, bankManager);
        getServer().getPluginManager().registerEvents(fruitRollGUI, this);

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

        LuffyArmorManager luffyArmorManager = new LuffyArmorManager(this, customItemRegistry, devilFruitManager);
        getServer().getPluginManager().registerEvents(luffyArmorManager, this);

        getServer().getPluginManager().registerEvents(
                new OnePieceAbilityListener(this, customItemRegistry, regionManager), this);
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.Items.BoxSphereListener(this, customItemRegistry, regionManager), this);

        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.Items.Heads.HeadAbilityListener(this, customItemRegistry, regionManager, logManager), this);

        playerDataLoader = new me.revqz.genPvP.Database.PlayerDataLoader(
                this, databaseManager, bankManager, prestigeManager, statsManager,
                devilFruitManager, kitManager, fruitRollManager);
        getServer().getPluginManager().registerEvents(playerDataLoader, this);

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

        displaysManager = new me.revqz.genPvP.SpawnDisplays.DisplaysManager(this);
        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.SpawnDisplays.DisplaysListener(this, displaysManager), this);
        getServer().getScheduler().runTaskLater(this, displaysManager::summon, 1L);

        getServer().getScheduler().runTaskLater(this, () ->
                new me.revqz.genPvP.util.ParticleCircleEffect(this).start(), 1L);

        getServer().getPluginManager().registerEvents(
                new me.revqz.genPvP.util.DoubleJumpListener(this, regionManager), this);

        getServer().getScheduler().runTaskLater(this, () -> {
            if (playerDataLoader != null) {
                playerDataLoader.reloadOnlinePlayers();
            }
        }, 1L);
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

        getServer().getScheduler().cancelTasks(this);

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
