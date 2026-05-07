package me.revqz.genPvP.Scoreboard;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ScoreboardColorUtil {

    public record Segment(String text, Color color, boolean bold, boolean italic,
                          boolean strikethrough, boolean underline) {}

    private static final Color DEFAULT_COLOR = Color.WHITE;

    private static final Map<Character, Color> LEGACY = Map.ofEntries(
            Map.entry('0', new Color(0, 0, 0)),
            Map.entry('1', new Color(0, 0, 170)),
            Map.entry('2', new Color(0, 170, 0)),
            Map.entry('3', new Color(0, 170, 170)),
            Map.entry('4', new Color(170, 0, 0)),
            Map.entry('5', new Color(170, 0, 170)),
            Map.entry('6', new Color(255, 170, 0)),
            Map.entry('7', new Color(170, 170, 170)),
            Map.entry('8', new Color(85, 85, 85)),
            Map.entry('9', new Color(85, 85, 255)),
            Map.entry('a', new Color(85, 255, 85)),
            Map.entry('b', new Color(85, 255, 255)),
            Map.entry('c', new Color(255, 85, 85)),
            Map.entry('d', new Color(255, 85, 255)),
            Map.entry('e', new Color(255, 255, 85)),
            Map.entry('f', new Color(255, 255, 255))
    );

    private ScoreboardColorUtil() {}

    public static List<Segment> parseSegments(String raw) {
        List<Segment> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return result;

        Color color = DEFAULT_COLOR;
        boolean bold = false, italic = false, strikethrough = false, underline = false;
        StringBuilder buf = new StringBuilder();

        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);

            if (c == '&' && i + 7 < raw.length() && raw.charAt(i + 1) == '#') {
                String hex = raw.substring(i + 2, i + 8);
                if (hex.matches("[0-9A-Fa-f]{6}")) {
                    flush(result, buf, color, bold, italic, strikethrough, underline);
                    color = Color.decode("#" + hex);
                    bold = false; italic = false; strikethrough = false; underline = false;
                    i += 8;
                    continue;
                }
            }

            if (c == '&' && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(i + 1));

                if (LEGACY.containsKey(code)) {
                    flush(result, buf, color, bold, italic, strikethrough, underline);
                    color = LEGACY.get(code);
                    bold = false; italic = false; strikethrough = false; underline = false;
                    i += 2;
                    continue;
                }

                switch (code) {
                    case 'l' -> {
                        flush(result, buf, color, bold, italic, strikethrough, underline);
                        bold = true; i += 2; continue;
                    }
                    case 'o' -> {
                        flush(result, buf, color, bold, italic, strikethrough, underline);
                        italic = true; i += 2; continue;
                    }
                    case 'm' -> {
                        flush(result, buf, color, bold, italic, strikethrough, underline);
                        strikethrough = true; i += 2; continue;
                    }
                    case 'n' -> {
                        flush(result, buf, color, bold, italic, strikethrough, underline);
                        underline = true; i += 2; continue;
                    }
                    case 'r' -> {
                        flush(result, buf, color, bold, italic, strikethrough, underline);
                        color = DEFAULT_COLOR;
                        bold = false; italic = false; strikethrough = false; underline = false;
                        i += 2; continue;
                    }
                    case 'k' -> { i += 2; continue; } 
                }
            }

            buf.append(c);
            i++;
        }
        flush(result, buf, color, bold, italic, strikethrough, underline);
        return result;
    }

    private static void flush(List<Segment> out, StringBuilder buf,
                               Color color, boolean bold, boolean italic,
                               boolean strikethrough, boolean underline) {
        if (buf.isEmpty()) return;
        out.add(new Segment(buf.toString(), color, bold, italic, strikethrough, underline));
        buf.setLength(0);
    }
}
