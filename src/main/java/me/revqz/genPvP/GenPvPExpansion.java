package me.revqz.genPvP;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.revqz.genPvP.Bank.BankManager;
import me.revqz.genPvP.DevilFruits.DevilFruit;
import me.revqz.genPvP.DevilFruits.DevilFruitManager;
import me.revqz.genPvP.DevilFruits.ManaManager;
import me.revqz.genPvP.Items.LuffyArmorManager;
import me.revqz.genPvP.Koth.KothManager;
import me.revqz.genPvP.Prestige.PrestigeManager;
import me.revqz.genPvP.Protect.RegionManager;
import me.revqz.genPvP.PvPRooms.PvPPhase;
import me.revqz.genPvP.PvPRooms.PvPRoomManager;
import me.revqz.genPvP.PvPRooms.PvPRoomState;
import me.revqz.genPvP.Stats.StatsManager;
import me.revqz.genPvP.Teams.Team;
import me.revqz.genPvP.Teams.TeamManager;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GenPvPExpansion extends PlaceholderExpansion {

    private final GenPvP plugin;
    private final PvPRoomManager pvpRoomManager;
    private final KothManager kothManager;
    private final BankManager bankManager;
    private final PrestigeManager prestigeManager;
    private final RegionManager regionManager;
    private final StatsManager statsManager;
    private final TeamManager teamManager;
    private final DevilFruitManager devilFruitManager;
    private final LuffyArmorManager luffyArmorManager;
    private final ManaManager manaManager;

    public GenPvPExpansion(GenPvP plugin, PvPRoomManager pvpRoomManager,
            KothManager kothManager, BankManager bankManager,
            PrestigeManager prestigeManager, StatsManager statsManager,
            Object skinCache ,
            TeamManager teamManager) {
        this(plugin, pvpRoomManager, kothManager, bankManager, prestigeManager,
                statsManager, skinCache, teamManager, null, null, null);
    }

    public GenPvPExpansion(GenPvP plugin, PvPRoomManager pvpRoomManager,
            KothManager kothManager, BankManager bankManager,
            PrestigeManager prestigeManager, StatsManager statsManager,
            Object skinCache ,
            TeamManager teamManager,
            DevilFruitManager devilFruitManager,
            LuffyArmorManager luffyArmorManager) {
        this(plugin, pvpRoomManager, kothManager, bankManager, prestigeManager,
                statsManager, skinCache, teamManager, devilFruitManager, luffyArmorManager, null);
    }

    public GenPvPExpansion(GenPvP plugin, PvPRoomManager pvpRoomManager,
            KothManager kothManager, BankManager bankManager,
            PrestigeManager prestigeManager, StatsManager statsManager,
            Object skinCache ,
            TeamManager teamManager,
            DevilFruitManager devilFruitManager,
            LuffyArmorManager luffyArmorManager,
            ManaManager manaManager) {
        this.plugin = plugin;
        this.pvpRoomManager = pvpRoomManager;
        this.kothManager = kothManager;
        this.bankManager = bankManager;
        this.prestigeManager = prestigeManager;
        this.regionManager = pvpRoomManager.getRegionManager();
        this.statsManager = statsManager;
        this.teamManager = teamManager;
        this.devilFruitManager = devilFruitManager;
        this.luffyArmorManager = luffyArmorManager;
        this.manaManager = manaManager;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "genpvp";
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getDescription().getAuthors().get(0);
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {

        if (params.equalsIgnoreCase("level")) {
            if (player == null)
                return "0";
            return String.valueOf(prestigeManager.getLevel(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("level_next")) {
            if (player == null)
                return "1";
            int current = prestigeManager.getLevel(player.getUniqueId());
            return String.valueOf(current + 1);
        }

        if (params.equalsIgnoreCase("prestige")) {
            if (player == null)
                return "0";
            return String.valueOf(prestigeManager.getPrestige(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("level_bar_lines")) {
            if (player == null)
                return "§c||||||||||||||||||||";
            return prestigeManager.buildBarLines(player.getUniqueId());
        }

        if (params.equalsIgnoreCase("level_bar_percentage")) {
            if (player == null)
                return "0%";
            return prestigeManager.getLevelPercentage(player.getUniqueId()) + "%";
        }

        if (params.equalsIgnoreCase("time_till_next_koth")) {
            return kothManager.isKothActive()
                    ? "Active"
                    : formatTime(kothManager.getTimeUntilNextKoth());
        }

        if (params.equalsIgnoreCase("time_till_koth_capture")) {
            if (!kothManager.isKothActive())
                return "N/A";
            if (kothManager.getCapturingPlayerName().equals("None"))
                return "N/A";
            return formatTime(kothManager.getCaptureTimeRemaining());
        }

        if (params.equalsIgnoreCase("capturer_koth")) {
            return kothManager.getCapturingPlayerName();
        }

        if (params.startsWith("koth_top_winner_name_")) {
            try {
                int rank = Integer.parseInt(params.substring("koth_top_winner_name_".length()));
                return kothManager.getTopWinner(rank).name;
            } catch (NumberFormatException ignored) {
                return "Unknown";
            }
        }

        if (params.startsWith("koth_top_winner_number_")) {
            try {
                int rank = Integer.parseInt(params.substring("koth_top_winner_number_".length()));
                return String.valueOf(kothManager.getTopWinner(rank).wins);
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.startsWith("koth_top_winner_playerhead_")) {
            try {
                int rank = Integer.parseInt(params.substring("koth_top_winner_playerhead_".length()));
                return headForName(kothManager.getTopWinner(rank).name);
            } catch (NumberFormatException ignored) {
                return "";
            }
        }

        if (params.equalsIgnoreCase("koth_claim_bar")) {
            if (!kothManager.isKothActive() || kothManager.getCapturingPlayerName().equals("None")) {
                return "§c||||||||||||||||||||";
            }
            int elapsed = kothManager.getCaptureTimeSetting() - kothManager.getCaptureTimeRemaining();
            int green = Math.min(20, elapsed / 6);
            return "§a" + "|".repeat(green) + "§c" + "|".repeat(20 - green);
        }

        if (params.equalsIgnoreCase("koth_claim_time_formatted")) {
            if (!kothManager.isKothActive() || kothManager.getCapturingPlayerName().equals("None")) {
                return "0s";
            }
            int elapsed = kothManager.getCaptureTimeSetting() - kothManager.getCaptureTimeRemaining();
            return formatTime(elapsed);
        }

        if (params.equalsIgnoreCase("bank_money_total")) {
            if (player == null || !player.isOnline())
                return "0";
            return BankManager.formatBalance(bankManager.getBalance(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("bank_money_total_nonformated")) {
            if (player == null || !player.isOnline())
                return "0";
            return String.valueOf((long) bankManager.getBalance(player.getUniqueId()));
        }

        if (params.startsWith("bank_money_ign_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_money_ign_".length()));
                return bankManager.getTopEntry(rank).name();
            } catch (NumberFormatException ignored) {
                return "Unknown";
            }
        }

        if (params.startsWith("bank_money_amount_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_money_amount_".length()));
                return BankManager.formatBalance(bankManager.getTopEntry(rank).balance());
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.startsWith("bank_money_playerhead_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_money_playerhead_".length()));
                return headForName(bankManager.getTopEntry(rank).name());
            } catch (NumberFormatException ignored) {
                return "";
            }
        }

        if (params.equalsIgnoreCase("bank_shards_total")) {
            if (player == null || !player.isOnline())
                return "0";
            return BankManager.formatBalance(bankManager.getShards(player.getUniqueId()));
        }

        if (params.startsWith("bank_shards_ign_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_shards_ign_".length()));
                return bankManager.getTopShardsEntry(rank).name();
            } catch (NumberFormatException ignored) {
                return "Unknown";
            }
        }

        if (params.startsWith("bank_shards_amount_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_shards_amount_".length()));
                return BankManager.formatBalance(bankManager.getTopShardsEntry(rank).balance());
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.equalsIgnoreCase("bank_gold_total")) {
            if (player == null || !player.isOnline())
                return "0";
            return BankManager.formatBalance(bankManager.getGold(player.getUniqueId()));
        }

        if (params.startsWith("bank_gold_ign_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_gold_ign_".length()));
                return bankManager.getTopGoldEntry(rank).name();
            } catch (NumberFormatException ignored) {
                return "Unknown";
            }
        }

        if (params.startsWith("bank_gold_amount_")) {
            try {
                int rank = Integer.parseInt(params.substring("bank_gold_amount_".length()));
                return BankManager.formatBalance(bankManager.getTopGoldEntry(rank).balance());
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.equalsIgnoreCase("kills")) {
            if (player == null)
                return "0";
            return String.valueOf(statsManager.getKills(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("deaths")) {
            if (player == null)
                return "0";
            return String.valueOf(statsManager.getDeaths(player.getUniqueId()));
        }

        if (params.startsWith("kills_top_ign_")) {
            try {
                int rank = Integer.parseInt(params.substring("kills_top_ign_".length()));
                return statsManager.getTopKills(rank).name();
            } catch (NumberFormatException ignored) {
                return "Unknown";
            }
        }

        if (params.startsWith("kills_top_amount_")) {
            try {
                int rank = Integer.parseInt(params.substring("kills_top_amount_".length()));
                return String.valueOf(statsManager.getTopKills(rank).value());
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.startsWith("kills_top_playerhead_")) {
            try {
                int rank = Integer.parseInt(params.substring("kills_top_playerhead_".length()));
                StatsManager.StatsEntry e = statsManager.getTopKills(rank);
                return e.uuid() != null ? "<head:" + e.uuid() + ">" : headForName(e.name());
            } catch (NumberFormatException ignored) {
                return "";
            }
        }

        if (params.startsWith("deaths_top_ign_")) {
            try {
                int rank = Integer.parseInt(params.substring("deaths_top_ign_".length()));
                return statsManager.getTopDeaths(rank).name();
            } catch (NumberFormatException ignored) {
                return "Unknown";
            }
        }

        if (params.startsWith("deaths_top_amount_")) {
            try {
                int rank = Integer.parseInt(params.substring("deaths_top_amount_".length()));
                return String.valueOf(statsManager.getTopDeaths(rank).value());
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.startsWith("deaths_top_playerhead_")) {
            try {
                int rank = Integer.parseInt(params.substring("deaths_top_playerhead_".length()));
                StatsManager.StatsEntry e = statsManager.getTopDeaths(rank);
                return e.uuid() != null ? "<head:" + e.uuid() + ">" : headForName(e.name());
            } catch (NumberFormatException ignored) {
                return "";
            }
        }

        if (params.equalsIgnoreCase("pvproom1_max"))
            return "2";
        if (params.equalsIgnoreCase("pvproom2_max"))
            return "2";

        if (params.equalsIgnoreCase("pvproom1_in")) {
            return String.valueOf(insideCount(pvpRoomManager.getRoom1(), "PVPROOM1"));
        }
        if (params.equalsIgnoreCase("pvproom2_in")) {
            return String.valueOf(insideCount(pvpRoomManager.getRoom2(), "PVPROOM2"));
        }

        if (params.equalsIgnoreCase("team_name")) {
            if (player == null)
                return "N/A";
            if (!teamManager.isInTeam(player.getUniqueId()))
                return "N/A";
            Team team = teamManager.getTeam(player.getUniqueId());
            return team != null ? team.getName() : "N/A";
        }

        if (params.equalsIgnoreCase("team_inteam")) {
            if (player == null)
                return "false";
            return teamManager.isInTeam(player.getUniqueId()) ? "true" : "false";
        }

        if (params.equalsIgnoreCase("team_display")) {
            if (player == null)
                return "§7No Team";
            Team team = teamManager.getTeam(player.getUniqueId());
            if (team != null) {
                return me.revqz.genPvP.util.ColorUtil.colorize("&#00A4FB" + team.getName());
            } else {
                return me.revqz.genPvP.util.ColorUtil.colorize("&#A7A7A7No Team");
            }
        }

        if (params.equalsIgnoreCase("team_points")) {
            if (player == null)
                return "0";
            Team team = teamManager.getTeam(player.getUniqueId());
            return team != null ? String.valueOf(team.getPoints()) : "0";
        }

        if (params.startsWith("team_top_name_")) {
            try {
                int rank = Integer.parseInt(params.substring("team_top_name_".length()));
                List<Team> top = teamManager.getTopTeams(rank);
                return top.size() >= rank ? top.get(rank - 1).getName() : "N/A";
            } catch (NumberFormatException ignored) {
                return "N/A";
            }
        }

        if (params.startsWith("team_top_points_")) {
            try {
                int rank = Integer.parseInt(params.substring("team_top_points_".length()));
                List<Team> top = teamManager.getTopTeams(rank);
                return top.size() >= rank ? String.valueOf(top.get(rank - 1).getPoints()) : "0";
            } catch (NumberFormatException ignored) {
                return "0";
            }
        }

        if (params.equalsIgnoreCase("devil_fruit_equipped")) {
            if (player == null || devilFruitManager == null) return "None";
            String key = devilFruitManager.getEquippedFruit(player.getUniqueId());
            if (key == null) return "None";
            DevilFruit fruit = DevilFruit.fromKey(key);
            return fruit != null ? fruit.getDisplayName() : key;
        }

        if (params.equalsIgnoreCase("devil_fruit_mana")) {
            if (player == null || manaManager == null) return "0";
            return String.valueOf(manaManager.getMana(player.getUniqueId()));
        }

        if (params.equalsIgnoreCase("devil_fruit_mana_regen")) {
            if (manaManager == null) return "FULL";
            if (player == null || manaManager.getMana(player.getUniqueId()) >= manaManager.getMax()) return "FULL";
            double seconds = manaManager.getMillisUntilRegen() / 1000.0;
            return String.format("%.1fs", seconds);
        }

        if (params.equalsIgnoreCase("luffy_armor_currently")) {
            if (player == null || !player.isOnline() || luffyArmorManager == null) return "0";
            Player online = player.getPlayer();
            if (online == null) return "0";
            return String.valueOf(luffyArmorManager.getEquippedPieceCount(online));
        }

        if (params.equalsIgnoreCase("luffy_armor_haki_currentbuff")) {
            if (player == null || !player.isOnline() || luffyArmorManager == null) return "0%";
            Player online = player.getPlayer();
            if (online == null) return "0%";
            int pieces = luffyArmorManager.getEquippedPieceCount(online);
            int pct = (int) Math.round(pieces * LuffyArmorManager.HAKI_CHANCE_PER_PIECE * 100);
            return pct + "%";
        }

        if (params.equalsIgnoreCase("luffy_armor_haki_maxbuff")) {
            if (luffyArmorManager == null) return "0%";
            int pct = (int) Math.round(LuffyArmorManager.PIECE_IDS.length
                    * LuffyArmorManager.HAKI_CHANCE_PER_PIECE * 100);
            return pct + "%";
        }

        return null;
    }

    private int insideCount(PvPRoomState room, String regionName) {
        if (room.getCurrentPhase() != PvPPhase.WAITING) {
            return room.getParticipants().size();
        }
        return room.getCachedInsideCount();
    }

    @SuppressWarnings("deprecation")
    private static String headForName(String name) {
        if (name == null || name.equals("None") || name.equals("Unknown"))
            return "";
        return "<head:" + Bukkit.getOfflinePlayer(name).getUniqueId() + ">";
    }

    private static String formatTime(int seconds) {
        if (seconds < 60)
            return seconds + "s";
        int m = seconds / 60, s = seconds % 60;
        return s == 0 ? m + "m" : m + "m " + s + "s";
    }
}
