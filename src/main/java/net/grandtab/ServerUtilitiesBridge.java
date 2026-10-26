package net.grandtab;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Read-only access to Elrol's Server Utilities ranks (verified against
 * Server_Utils-1.16.5-2.8.0.jar). Nothing of Server Utilities is bundled or compiled against: the
 * classes are looked up by reflection when the mod is installed, so this mod also works without
 * it. Nothing is ever written to Server Utilities.
 *
 * <p>Verified API used:
 * <ul>
 *   <li>{@code dev.elrol.serverutilities.Main.database} (static, PlayerDatabase)</li>
 *   <li>{@code PlayerDatabase.isPresent(UUID)} (checked first: {@code get} would create data for
 *   unknown players) and {@code get(UUID)} returning IPlayerData</li>
 *   <li>{@code IPlayerData.getPrefix()}: the same prefix Server Utilities puts in chat, with
 *   &amp; colour codes; {@code IPlayerData.getDomRank()} returning IRank, and
 *   {@code IRank.getName()} / {@code getWeight()} for sorting</li>
 *   <li>{@code dev.elrol.serverutilities.init.Ranks.rankMap} (static Map of rank name to Rank)
 *   for the list of rank names</li>
 *   <li>{@code Main.textUtils.formatString(String)}: Server Utilities' own conversion of
 *   &amp; codes, used to find its prefix inside its chat lines</li>
 *   <li>{@code dev.elrol.serverutilities.config.FeatureConfig.jan_colors} … {@code dec_colors}:
 *   the month colours of its &amp;h code</li>
 * </ul>
 * Server Utilities 2.8.0 has no public API for this (its IElrolAPI only exposes commands, text
 * utilities and folders), so these public members of its implementation are used, and every
 * call is guarded: if a later version changes them, the integration switches itself off.
 */
public final class ServerUtilitiesBridge {
    private static boolean initialised;
    private static Field databaseField;
    private static Method isPresent;
    private static Method get;
    private static String status = "not checked";

    private ServerUtilitiesBridge() {
    }

    /** What Server Utilities offers, as read from it. */
    public static final class SuRank {
        public final String prefix;
        public final String rankName;
        public final int weight;

        SuRank(String prefix, String rankName, int weight) {
            this.prefix = prefix;
            this.rankName = rankName;
            this.weight = weight;
        }
    }

    private static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (!ModList.get().isLoaded("serverutilities")) {
            status = "Server Utilities is not installed";
            return;
        }
        try {
            Class<?> main = Class.forName("dev.elrol.serverutilities.Main");
            databaseField = main.getField("database");
            Class<?> dbType = databaseField.getType();
            isPresent = dbType.getMethod("isPresent", UUID.class);
            get = dbType.getMethod("get", UUID.class);
            status = "connected";
        } catch (ReflectiveOperationException | RuntimeException e) {
            databaseField = null;
            status = "Server Utilities found, but its rank API is different from version 2.8.0 (" + e + ")";
            GrandTab.LOG.warn("Grand Tab: {}", status);
        }
    }

    /** Names of all Server Utilities ranks (internal names such as testking), or empty. */
    public static java.util.List<String> rankNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (!available()) {
            return names;
        }
        try {
            Object map = Class.forName("dev.elrol.serverutilities.init.Ranks").getField("rankMap").get(null);
            if (map instanceof java.util.Map) {
                for (Object key : ((java.util.Map<?, ?>) map).keySet()) {
                    names.add(String.valueOf(key));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            GrandTab.LOG.debug("Grand Tab: cannot list Server Utilities ranks", e);
        }
        java.util.Collections.sort(names);
        return names;
    }

    /** The raw prefix (with &amp; codes) of a Server Utilities rank, or null if unknown. */
    public static String prefixOfRank(String rankName) {
        if (!available()) {
            return null;
        }
        try {
            Object map = Class.forName("dev.elrol.serverutilities.init.Ranks").getField("rankMap").get(null);
            if (map instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) map).entrySet()) {
                    if (String.valueOf(e.getKey()).equalsIgnoreCase(rankName) && e.getValue() != null) {
                        Object prefix = e.getValue().getClass().getMethod("getPrefix").invoke(e.getValue());
                        return prefix == null ? "" : prefix.toString();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            GrandTab.LOG.debug("Grand Tab: cannot read Server Utilities rank {}", rankName, e);
        }
        return null;
    }

    /** Server Utilities' own formatting of a string (&amp; codes to section codes), or null. */
    public static String formatString(String raw) {
        if (!available()) {
            return null;
        }
        try {
            Object utils = Class.forName("dev.elrol.serverutilities.Main").getField("textUtils").get(null);
            if (utils == null) {
                return null;
            }
            Object out = utils.getClass().getMethod("formatString", String.class).invoke(utils, raw);
            return out == null ? null : out.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            GrandTab.LOG.debug("Grand Tab: cannot call Server Utilities formatString", e);
            return null;
        }
    }

    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    /** This month's colour codes of Server Utilities' &amp;h code, or "" if unavailable. */
    public static String holidayCodes() {
        if (!available()) {
            return "";
        }
        try {
            int month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH);
            Object value = Class.forName("dev.elrol.serverutilities.config.FeatureConfig")
                    .getField(MONTHS[month] + "_colors").get(null);
            if (value instanceof net.minecraftforge.common.ForgeConfigSpec.ConfigValue) {
                Object codes = ((net.minecraftforge.common.ForgeConfigSpec.ConfigValue<?>) value).get();
                return codes == null ? "" : codes.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            GrandTab.LOG.debug("Grand Tab: cannot read Server Utilities holiday colours", e);
        }
        return "";
    }

    public static String status() {
        init();
        return status;
    }

    public static boolean available() {
        init();
        return databaseField != null;
    }

    /** The player's Server Utilities prefix and dominant rank, or null if none or unavailable. */
    public static SuRank rankOf(UUID player) {
        if (!available()) {
            return null;
        }
        try {
            Object db = databaseField.get(null);
            if (db == null || !Boolean.TRUE.equals(isPresent.invoke(db, player))) {
                return null;
            }
            Object data = get.invoke(db, player);
            if (data == null) {
                return null;
            }
            Object prefix = data.getClass().getMethod("getPrefix").invoke(data);
            String rankName = "";
            int weight = 0;
            Object dom = data.getClass().getMethod("getDomRank").invoke(data);
            if (dom != null) {
                Object name = dom.getClass().getMethod("getName").invoke(dom);
                Object w = dom.getClass().getMethod("getWeight").invoke(dom);
                rankName = name == null ? "" : name.toString();
                weight = w instanceof Integer ? (Integer) w : 0;
            }
            String p = prefix == null ? "" : prefix.toString().trim();
            if (p.isEmpty() && rankName.isEmpty()) {
                return null;
            }
            return new SuRank(p, rankName, weight);
        } catch (ReflectiveOperationException | RuntimeException e) {
            GrandTab.LOG.debug("Grand Tab: could not read Server Utilities rank of {}", player, e);
            return null;
        }
    }
}
