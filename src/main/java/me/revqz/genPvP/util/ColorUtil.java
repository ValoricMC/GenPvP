package me.revqz.genPvP.util;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates color codes in strings, supporting:
 *  - Standard codes:  {@code &a}, {@code &l}, etc.
 *  - Hex RGB:         {@code &#RRGGBB}
 */
public final class ColorUtil {

    private static final Pattern HEX = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {}

    public static String colorize(String text) {
        if (text == null || text.isEmpty()) return text;

        // Replace &#RRGGBB with BungeeCord hex ChatColor
        Matcher m = HEX.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, ChatColor.of("#" + m.group(1)).toString());
        }
        m.appendTail(sb);

        // Then standard & codes
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}
