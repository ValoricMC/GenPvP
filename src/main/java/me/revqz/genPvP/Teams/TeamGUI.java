package me.revqz.genPvP.Teams;

import me.revqz.genPvP.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * Builds team GUIs.
 *
 * <h3>Team Info GUI (4 rows, 36 slots)</h3>
 * <pre>
 *   Row 0-2 (slots 0-26): member skulls / gray glass pane invite placeholders
 *   Row 3 (slots 27-35): control bar
 *     27 = sign (search)     28 = hopper (sort)
 *     30 = arrow (back)      31 = helmet (team)     32 = arrow (next)
 *     35 = sword (pvp)
 * </pre>
 *
 * <h3>Disband Confirmation GUI (3 rows, 27 slots)</h3>
 * Slot 11 = red glass (cancel), Slot 15 = lime glass (confirm)
 */
public final class TeamGUI {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /** Substring present in every team info GUI title — used to identify clicks. */
    public static final String TEAM_GUI_TITLE_CHECK = "ᴛᴇᴀᴍ";

    /** Raw config string for the disband confirmation title. */
    public static final String DISBAND_GUI_TITLE_RAW = "&#3F3F3Fᴄᴏɴꜰɪʀᴍ ᴅɪꜱʙᴀɴᴅɪɴɢ ᴛᴇᴀᴍ";

    private TeamGUI() {}

    // ── Small Caps ────────────────────────────────────────────────────────────

    private static final String NORMAL = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL  = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    public static String toSmallCaps(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            int idx = NORMAL.indexOf(Character.toLowerCase(c));
            sb.append(idx >= 0 ? SMALL.charAt(idx) : c);
        }
        return sb.toString();
    }

    // ── Team Info GUI (4 rows) ────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    public static Inventory buildTeamInfo(Team team, boolean sortByJoinDate) {
        Component title = LEGACY.deserialize(ColorUtil.colorize("&#3F3F3Fᴛᴇᴀᴍ (Page 1/1)"));
        Inventory gui = Bukkit.createInventory(null, 36, title);

        // Fill slots 0-26 with invite placeholder
        ItemStack invitePane = makeItem(Material.GRAY_STAINED_GLASS_PANE,
                ColorUtil.colorize("&#00F986ɪɴᴠɪᴛᴇ"),
                List.of("§fClick to invite a new player"));
        for (int i = 0; i <= 26; i++) {
            gui.setItem(i, invitePane);
        }

        // ── Control bar (row 3, slots 27-35) ──────────────────────────────────

        gui.setItem(27, makeItem(Material.OAK_SIGN,
                ColorUtil.colorize("&#00F986ꜱᴇᴀʀᴄʜ"),
                List.of("§7Click to search for teammate")));

        gui.setItem(28, makeItem(Material.HOPPER,
                ColorUtil.colorize("&#00F986ꜱᴏʀᴛɪɴɢ"),
                List.of("§7Click to sort (Join Date)")));

        gui.setItem(30, makeItem(Material.ARROW,
                ColorUtil.colorize("&#00F986ʙᴀᴄᴋ"),
                List.of("§fClick to go to the previous page")));

        // Helmet — team name + points
        String teamSmall = toSmallCaps(team.getName());
        ItemStack helmet = makeItem(Material.IRON_HELMET,
                ColorUtil.colorize("&#00F986ᴛᴇᴀᴍ " + teamSmall),
                List.of("§fClick to refresh",
                        "§7Members: §f" + team.size(),
                        "§7Points: §f" + team.getPoints()));
        ItemMeta helmetMeta = helmet.getItemMeta();
        if (helmetMeta != null) {
            helmetMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            helmet.setItemMeta(helmetMeta);
        }
        gui.setItem(31, helmet);

        gui.setItem(32, makeItem(Material.ARROW,
                ColorUtil.colorize("&#00F986ɴᴇxᴛ"),
                List.of("§fClick to go to the next page")));

        // PvP sword — reflects toggle state
        String pvpState = team.isPvpEnabled()
                ? ColorUtil.colorize("§7Currently: &#7AFB00§lON")
                : ColorUtil.colorize("§7Currently: &#FC0000§lOFF");
        ItemStack pvpSword = makeItem(Material.IRON_SWORD,
                ColorUtil.colorize("&#00F986ᴘᴠᴘ"),
                List.of(pvpState, "§7Click to toggle"));
        ItemMeta swordMeta = pvpSword.getItemMeta();
        if (swordMeta != null) {
            swordMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            pvpSword.setItemMeta(swordMeta);
        }
        gui.setItem(35, pvpSword);

        // ── Player skulls ─────────────────────────────────────────────────────
        List<TeamMember> members = sortByJoinDate
                ? team.getMembersSortedByJoinDate()
                : team.getMemberList();

        int placement = 0;
        for (TeamMember member : members) {
            if (placement > 26) break;

            boolean online = Bukkit.getPlayer(member.getUuid()) != null;
            String prefix  = online
                    ? ColorUtil.colorize("&#00F986■")
                    : ColorUtil.colorize("&#FC0000■");
            String suffix = ColorUtil.colorize("&#00F986" + member.getName());

            ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(member.getUuid()));
                skullMeta.setDisplayName(prefix + suffix);
                skullMeta.setLore(List.of("§fClick to edit"));
                skull.setItemMeta(skullMeta);
            }
            gui.setItem(placement, skull);
            placement++;
        }

        return gui;
    }

    /** Default: no join-date sorting. */
    public static Inventory buildTeamInfo(Team team) {
        return buildTeamInfo(team, false);
    }

    // ── Disband Confirmation GUI ──────────────────────────────────────────────

    public static Inventory buildDisbandConfirmation() {
        Component title = LEGACY.deserialize(ColorUtil.colorize(DISBAND_GUI_TITLE_RAW));
        Inventory gui = Bukkit.createInventory(null, 27, title);

        gui.setItem(11, makeItem(Material.RED_STAINED_GLASS_PANE,
                ColorUtil.colorize("&#FC0000ᴄᴀɴᴄᴇʟ"),
                List.of("§fClick to cancel")));

        gui.setItem(15, makeItem(Material.LIME_STAINED_GLASS_PANE,
                ColorUtil.colorize("&#00FC00ᴄᴏɴꜰɪʀᴍ"),
                List.of("§fClick to confirm")));

        return gui;
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
