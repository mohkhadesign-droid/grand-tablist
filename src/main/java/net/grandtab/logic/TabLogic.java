package net.grandtab.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minecraft-independent rules of the tab list (tested by TabLogicSelfTest). */
public final class TabLogic {
    private TabLogic() {
    }

    /** The 16 Minecraft chat colours by their standard names. */
    public static final Map<String, Integer> NAMED;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("black", 0x000000);
        m.put("dark_blue", 0x0000AA);
        m.put("dark_green", 0x00AA00);
        m.put("dark_aqua", 0x00AAAA);
        m.put("dark_red", 0xAA0000);
        m.put("dark_purple", 0xAA00AA);
        m.put("gold", 0xFFAA00);
        m.put("gray", 0xAAAAAA);
        m.put("dark_gray", 0x555555);
        m.put("blue", 0x5555FF);
        m.put("green", 0x55FF55);
        m.put("aqua", 0x55FFFF);
        m.put("red", 0xFF5555);
        m.put("light_purple", 0xFF55FF);
        m.put("yellow", 0xFFFF55);
        m.put("white", 0xFFFFFF);
        NAMED = Collections.unmodifiableMap(m);
    }

    /** Parses a colour name (gold, dark_green ...) or #RRGGBB; throws IllegalArgumentException. */
    public static int parseColor(String text) {
        String s = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        Integer named = NAMED.get(s);
        if (named != null) {
            return named;
        }
        if (s.matches("#[0-9a-f]{6}")) {
            return Integer.parseInt(s.substring(1), 16);
        }
        throw new IllegalArgumentException("unknown colour '" + text + "' (use a name like gold or #RRGGBB)");
    }

    /** Like parseColor, but returns the fallback for empty or invalid input. */
    public static int colorOr(String text, int fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        try {
            return parseColor(text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public static String formatColor(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * Rank prefix from a format such as "[{kingdom} {role}]". Placeholders: {kingdom} (the
     * kingdom tag, e.g. K1), {kingdom_name} (e.g. Kingdom I), {role} (e.g. MONARCH).
     */
    public static String prefix(String format, String kingdomTag, String kingdomName, String roleName) {
        return format.replace("{kingdom_name}", kingdomName)
                .replace("{kingdom}", kingdomTag)
                .replace("{role}", roleName);
    }

    /**
     * Scoreboard team used only for sorting (vanilla sorts the tab list by team name, then by
     * player name). Kingdom order first, then role order; unranked players last.
     */
    public static String sortTeam(int kingdomIndex, int roleOrder) {
        return String.format(Locale.ROOT, "gt%02d%02d", Math.max(0, Math.min(98, kingdomIndex)), Math.max(0, Math.min(99, roleOrder)));
    }

    public static final String UNRANKED_TEAM = "gt9999";

    public static boolean isSortTeam(String name) {
        return name != null && name.matches("gt\\d{4}");
    }

    /** Colour of the optional ping text: green, yellow, gold, red. */
    public static int pingColor(int ms) {
        if (ms < 0) {
            return 0xAAAAAA;
        }
        if (ms < 150) {
            return 0x55FF55;
        }
        if (ms < 300) {
            return 0xFFFF55;
        }
        if (ms < 600) {
            return 0xFFAA00;
        }
        return 0xFF5555;
    }

    /** Spaces that render as a horizontal rule when struck through. */
    public static String rule(int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, Math.min(200, width)); i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** Fills {online} and {max} in the player-count line. */
    public static String count(String format, int online, int max) {
        return format.replace("{online}", Integer.toString(online)).replace("{max}", Integer.toString(max));
    }

    /** Lower-case id for kingdoms and roles; letters, digits, - and _ only. */
    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_\\-]{1,16}");
    }

    /**
     * Sorting team from a Server Utilities rank weight: higher weight sorts first. Values are
     * kept inside gt0000-gt9998 so they never collide with the unranked team.
     */
    public static String weightTeam(int weight) {
        int key = Math.max(0, Math.min(9998, 5000 - weight));
        return String.format(Locale.ROOT, "gt%04d", key);
    }

    /** A run of text with one style, from a legacy formatted string such as "&c[&lKING&c]". */
    public static final class Segment {
        public final String text;
        public final Integer color;
        public final boolean bold;
        public final boolean italic;
        public final boolean underlined;
        public final boolean strikethrough;
        public final boolean obfuscated;

        Segment(String text, Integer color, boolean bold, boolean italic, boolean underlined, boolean strikethrough, boolean obfuscated) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.strikethrough = strikethrough;
            this.obfuscated = obfuscated;
        }
    }

    private static final String CODES = "0123456789abcdef";
    private static final int[] LEGACY = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /**
     * Splits text with Minecraft legacy codes (&amp; or the section sign, colours 0-f, k l m n o r,
     * and &amp;#RRGGBB) into styled segments. Unknown codes stay as plain text. As in vanilla, a
     * colour code resets the formatting codes.
     */
    public static List<Segment> parseLegacy(String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        StringBuilder run = new StringBuilder();
        Integer color = null;
        boolean bold = false, italic = false, underlined = false, strike = false, obf = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                Integer newColor = null;
                int consumed = 0;
                if (code == '#' && i + 8 <= text.length() && text.substring(i + 2, i + 8).matches("[0-9a-fA-F]{6}")) {
                    newColor = Integer.parseInt(text.substring(i + 2, i + 8), 16);
                    consumed = 8;
                } else if (CODES.indexOf(code) >= 0) {
                    newColor = LEGACY[CODES.indexOf(code)];
                    consumed = 2;
                } else if ("klmnor".indexOf(code) >= 0) {
                    consumed = 2;
                }
                if (consumed > 0) {
                    if (run.length() > 0) {
                        out.add(new Segment(run.toString(), color, bold, italic, underlined, strike, obf));
                        run.setLength(0);
                    }
                    if (newColor != null) {
                        color = newColor;
                        bold = italic = underlined = strike = obf = false;
                    } else if (code == 'r') {
                        color = null;
                        bold = italic = underlined = strike = obf = false;
                    } else if (code == 'l') {
                        bold = true;
                    } else if (code == 'o') {
                        italic = true;
                    } else if (code == 'n') {
                        underlined = true;
                    } else if (code == 'm') {
                        strike = true;
                    } else if (code == 'k') {
                        obf = true;
                    }
                    i += consumed;
                    continue;
                }
            }
            run.append(c);
            i++;
        }
        if (run.length() > 0) {
            out.add(new Segment(run.toString(), color, bold, italic, underlined, strike, obf));
        }
        return out;
    }

    /** Text without legacy codes (for comparisons and logs). */
    public static String stripLegacy(String text) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : parseLegacy(text)) {
            sb.append(s.text);
        }
        return sb.toString();
    }
}
