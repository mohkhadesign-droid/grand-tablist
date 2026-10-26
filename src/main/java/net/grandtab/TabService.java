package net.grandtab;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPipeline;
import net.grandtab.config.ConfigStore;
import net.grandtab.config.TabConfig;
import net.grandtab.logic.GradientLogic;
import net.grandtab.logic.TabLogic;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPlayerListHeaderFooterPacket;
import net.minecraft.network.play.server.SPlayerListItemPacket;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds and sends everything shown on the Tab screen. All of it uses vanilla packets, so
 * unmodified clients display it:
 * <ul>
 *   <li>header and footer: one text component each (SPlayerListHeaderFooterPacket), per player,
 *   so players who loaded the server resource pack get the logo image and the others a text
 *   title;</li>
 *   <li>player names: Forge's TabListNameFormat event sets each player's tab-list display name.
 *   This does not use scoreboard teams, so other mods changing teams cannot remove the
 *   prefixes, and the name is built from the account name, so prefixes added to the display
 *   name by other mods are not repeated;</li>
 *   <li>pings: the vanilla latency update, sent more often than vanilla's 30 s;</li>
 *   <li>optional sorting through scoreboard teams named gt0000-gt9999 (vanilla sorts the list by
 *   team name).</li>
 * </ul>
 */
public final class TabService {
    private final MinecraftServer server;
    private final ConfigStore store;
    private final Map<UUID, String> packStatus = new ConcurrentHashMap<>();
    private final Map<UUID, String> sentHeaders = new HashMap<>();
    private final Map<UUID, String> sentNames = new HashMap<>();
    /** Names computed in the last refresh (TabListNameFormat returns these). */
    private final Map<UUID, ITextComponent> names = new HashMap<>();
    private final Map<UUID, ServerUtilitiesBridge.SuRank> suCache = new HashMap<>();
    private final java.util.Set<UUID> animatedPlayers = new java.util.HashSet<>();
    private String holidayCodes = "";
    private long frame;
    private NametagDisplay nametags;
    private volatile boolean dirty = true;
    private long ticks;

    public TabService(MinecraftServer server, ConfigStore store) {
        this.server = server;
        this.store = store;
    }

    public TabConfig config() {
        return store.config();
    }

    public ConfigStore store() {
        return store;
    }

    public void markDirty() {
        dirty = true;
    }

    // ------------------------------------------------------------------ lifecycle

    public void start() {
        cleanupTeams(!config().players.sortByRank);
        nametags = new NametagDisplay(server, this);
        dirty = true;
    }

    public void stop() {
        cleanupTeams(true);
        if (nametags != null) {
            nametags.stop();
        }
    }

    public NametagDisplay nametags() {
        return nametags;
    }

    public long frame() {
        return frame;
    }

    public void tick() {
        ticks++;
        TabConfig cfg = config();
        boolean refresh = dirty || ticks % cfg.updateIntervalTicks == 0;
        boolean ping = ticks % cfg.pingUpdateIntervalTicks == 0;
        boolean newFrame = ticks % cfg.gradients.frameTicks == 0;
        if (newFrame) {
            frame++;
        }
        if (refresh) {
            dirty = false;
            refreshAll();
        } else if (newFrame && !animatedPlayers.isEmpty()) {
            refreshNames(true);
        }
        if (nametags != null) {
            nametags.tick(ticks, refresh, newFrame);
        }
        if (ping && cfg.enabled) {
            List<ServerPlayerEntity> players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                server.getPlayerList().sendPacketToAllPlayers(
                        new SPlayerListItemPacket(SPlayerListItemPacket.Action.UPDATE_LATENCY, players));
                if (cfg.players.showPingMs && !refresh) {
                    refreshNames(false);
                }
            }
        }
    }

    private void refreshAll() {
        TabConfig cfg = config();
        holidayCodes = ServerUtilitiesBridge.holidayCodes();
        suCache.clear();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            sendHeader(player);
            suCache.put(player.getUniqueID(), ServerUtilitiesBridge.rankOf(player.getUniqueID()));
        }
        refreshNames(false);
        if (cfg.enabled && cfg.players.sortByRank) {
            applySortTeams();
        }
    }

    /**
     * Recomputes tab names and sends only those that changed. With {@code onlyAnimated}, only
     * players whose prefix is animated are looked at (each animation frame).
     */
    private void refreshNames(boolean onlyAnimated) {
        if (!onlyAnimated) {
            animatedPlayers.clear();
        }
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUniqueID();
            if (onlyAnimated && !animatedPlayers.contains(id)) {
                continue;
            }
            ITextComponent name = buildName(player);
            if (!onlyAnimated && config().enabled && config().gradients.inTabList) {
                Prefix prefix = prefixOf(player);
                if (prefix != null && prefix.animated) {
                    animatedPlayers.add(id);
                }
            }
            String json = name == null ? "" : ITextComponent.Serializer.toJson(name);
            if (!json.equals(sentNames.get(id))) {
                sentNames.put(id, json);
                if (name == null) {
                    names.remove(id);
                } else {
                    names.put(id, name);
                }
                player.refreshTabListName(); // fires TabListNameFormat and sends the update to everyone
            }
        }
    }

    public void onLogin(ServerPlayerEntity player) {
        installPackListener(player);
        sentHeaders.remove(player.getUniqueID());
        sentNames.remove(player.getUniqueID());
        names.remove(player.getUniqueID());
        dirty = true;
    }

    public void onLogout(ServerPlayerEntity player) {
        packStatus.remove(player.getUniqueID());
        sentHeaders.remove(player.getUniqueID());
        sentNames.remove(player.getUniqueID());
        names.remove(player.getUniqueID());
        suCache.remove(player.getUniqueID());
        animatedPlayers.remove(player.getUniqueID());
        if (nametags != null) {
            nametags.onLogout(player);
        }
        dirty = true; // the online count drops on the next tick, when the player is gone
    }

    // ------------------------------------------------------------------ resource pack status

    private void installPackListener(ServerPlayerEntity player) {
        try {
            ChannelPipeline pipeline = player.connection.netManager.channel().pipeline();
            if (pipeline.get(PackStatusListener.NAME) == null && pipeline.get("packet_handler") != null) {
                pipeline.addBefore("packet_handler", PackStatusListener.NAME, new PackStatusListener(this, player.getUniqueID()));
            }
        } catch (RuntimeException e) {
            GrandTab.LOG.warn("Grand Tab: cannot watch resource pack status of {} (text title will be used): {}",
                    player.getGameProfile().getName(), e.toString());
        }
    }

    /** Called from the network thread when a client reports its resource pack status. */
    void packStatus(UUID player, String status) {
        packStatus.put(player, status);
        dirty = true;
    }

    public String packStatusOf(UUID player) {
        return packStatus.getOrDefault(player, "NONE (no pack offered or no answer)");
    }

    private boolean showLogo(ServerPlayerEntity player) {
        String mode = config().header.logoMode.toUpperCase(java.util.Locale.ROOT);
        if (mode.equals("PACK")) {
            return true;
        }
        if (mode.equals("AUTO")) {
            return "SUCCESSFULLY_LOADED".equals(packStatus.get(player.getUniqueID()));
        }
        return false;
    }

    // ------------------------------------------------------------------ header and footer

    private static IFormattableTextComponent text(String s, String color, int fallback, boolean bold) {
        IFormattableTextComponent c = new StringTextComponent(s);
        int rgb = TabLogic.colorOr(color, fallback);
        c.modifyStyle(style -> style.setColor(Color.fromInt(rgb)));
        if (bold) {
            c.mergeStyle(TextFormatting.BOLD);
        }
        return c;
    }

    private static IFormattableTextComponent rule(TabConfig.Header h) {
        IFormattableTextComponent c = text(TabLogic.rule(h.ruleWidth), h.ruleColor, 0x555555, false);
        c.mergeStyle(TextFormatting.STRIKETHROUGH);
        return c;
    }

    private static void line(IFormattableTextComponent root, ITextComponent part) {
        if (!root.getSiblings().isEmpty()) {
            root.appendSibling(new StringTextComponent("\n"));
        }
        root.appendSibling(part);
    }

    public ITextComponent header(ServerPlayerEntity player) {
        TabConfig cfg = config();
        TabConfig.Header h = cfg.header;
        IFormattableTextComponent root = new StringTextComponent("");
        if (h.showRule) {
            line(root, rule(h));
        }
        boolean logo = !h.logoMode.equalsIgnoreCase("NONE") && showLogo(player);
        if (logo) {
            IFormattableTextComponent glyph = new StringTextComponent(h.logoGlyph);
            ResourceLocation font = ResourceLocation.tryCreate(h.logoFont);
            if (font != null) {
                glyph.modifyStyle(style -> style.setFontId(font).setColor(Color.fromInt(0xFFFFFF)));
            }
            line(root, glyph);
            for (int i = 0; i < Math.max(0, Math.min(20, h.logoSpacerLines)); i++) {
                line(root, new StringTextComponent(" "));
            }
        }
        if (!h.logoMode.equalsIgnoreCase("NONE") && (!logo || h.showTitleWithLogo) && !h.title.isEmpty()) {
            line(root, text(h.title, h.titleColor, 0x55FFFF, h.titleBold));
        }
        if (h.showSubtitle && !h.subtitle.isEmpty()) {
            line(root, text(h.subtitle, h.subtitleColor, 0xFFAA00, h.subtitleBold));
        }
        if (h.showPlayerCount) {
            line(root, new StringTextComponent(" "));
            int online = server.getPlayerList().getCurrentPlayerCount();
            int max = server.getPlayerList().getMaxPlayers();
            IFormattableTextComponent count = text(TabLogic.count(h.playerCountLabel, online, max), h.playerCountLabelColor, 0xAAAAAA, false);
            count.appendSibling(text(TabLogic.count(h.playerCountValue, online, max), h.playerCountValueColor, 0x55FF55, true));
            line(root, count);
        }
        line(root, new StringTextComponent(" "));
        return root;
    }

    public ITextComponent footer() {
        TabConfig cfg = config();
        IFormattableTextComponent root = new StringTextComponent("");
        line(root, new StringTextComponent(" "));
        for (TabConfig.FooterLine fl : cfg.footer.lines) {
            if (fl == null) {
                continue;
            }
            IFormattableTextComponent l = text(fl.label == null ? "" : fl.label, fl.labelColor, 0xAAAAAA, false);
            l.appendSibling(text(fl.value == null ? "" : fl.value, fl.valueColor, 0xFFFFFF, fl.bold));
            line(root, l);
        }
        if (cfg.footer.showRule) {
            line(root, rule(cfg.header));
        }
        return root;
    }

    private void sendHeader(ServerPlayerEntity player) {
        TabConfig cfg = config();
        ITextComponent header = cfg.enabled ? header(player) : new StringTextComponent("");
        ITextComponent footer = cfg.enabled ? footer() : new StringTextComponent("");
        String key = ITextComponent.Serializer.toJson(header) + "|" + ITextComponent.Serializer.toJson(footer);
        if (key.equals(sentHeaders.get(player.getUniqueID()))) {
            return;
        }
        SPlayerListHeaderFooterPacket packet = new SPlayerListHeaderFooterPacket();
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try {
            // Filled through the packet's own decoder, so no private fields are touched.
            buffer.writeTextComponent(header);
            buffer.writeTextComponent(footer);
            packet.readPacketData(buffer);
        } catch (IOException e) {
            GrandTab.LOG.error("Grand Tab: cannot build the tab header", e);
            return;
        } finally {
            buffer.release();
        }
        player.connection.sendPacket(packet);
        sentHeaders.put(player.getUniqueID(), key);
    }

    // ------------------------------------------------------------------ player names

    /** The rank of a player, resolved against the current config; null if unranked or unknown. */
    public static final class Rank {
        public final TabConfig.Kingdom kingdom;
        public final TabConfig.Role role;

        Rank(TabConfig.Kingdom kingdom, TabConfig.Role role) {
            this.kingdom = kingdom;
            this.role = role;
        }
    }

    public Rank rankOf(UUID player) {
        ConfigStore.Assignment a = store.assignments().get(player);
        if (a == null) {
            return null;
        }
        TabConfig.Kingdom k = config().kingdom(a.kingdom);
        TabConfig.Role r = config().role(a.role);
        return k == null || r == null ? null : new Rank(k, r);
    }

    private boolean useTabRank() {
        return !"SERVER_UTILITIES".equalsIgnoreCase(config().players.rankSource);
    }

    private boolean useServerUtilities() {
        String src = config().players.rankSource;
        return "SERVER_UTILITIES".equalsIgnoreCase(src) || "TABRANK_THEN_SERVER_UTILITIES".equalsIgnoreCase(src);
    }

    /** Server Utilities rank shown for this player (only when /tabrank does not apply). */
    public ServerUtilitiesBridge.SuRank shownSuRank(UUID player) {
        if (!useServerUtilities() || (useTabRank() && rankOf(player) != null)) {
            return null;
        }
        ServerUtilitiesBridge.SuRank su = suCache.containsKey(player) ? suCache.get(player) : ServerUtilitiesBridge.rankOf(player);
        return su == null || su.prefix.isEmpty() ? null : su;
    }

    /** One character of a prefix with its formatting (colours are replaced by a gradient). */
    private static final class StyledChar {
        final String ch;
        final boolean bold;
        final boolean italic;
        final boolean underlined;
        final boolean obfuscated;
        final Integer color;

        StyledChar(String ch, boolean bold, boolean italic, boolean underlined, boolean obfuscated, Integer color) {
            this.ch = ch;
            this.bold = bold;
            this.italic = italic;
            this.underlined = underlined;
            this.obfuscated = obfuscated;
            this.color = color;
        }
    }

    private List<StyledChar> chars(String legacyText) {
        List<StyledChar> out = new ArrayList<>();
        for (TabLogic.Segment seg : TabLogic.parseLegacy(legacyText, holidayCodes)) {
            int[] cps = seg.text.codePoints().toArray();
            for (int cp : cps) {
                out.add(new StyledChar(new String(Character.toChars(cp)), seg.bold, seg.italic, seg.underlined, seg.obfuscated, seg.color));
            }
        }
        return out;
    }

    /** Builds a component; with a gradient, each character's colour comes from it. */
    private static IFormattableTextComponent render(List<StyledChar> chars, int[] gradient, Boolean forceBold) {
        IFormattableTextComponent root = new StringTextComponent("");
        int i = 0;
        while (i < chars.size()) {
            StyledChar first = chars.get(i);
            Integer color = gradient != null ? Integer.valueOf(gradient[i]) : first.color;
            boolean bold = forceBold != null ? forceBold : first.bold;
            StringBuilder run = new StringBuilder(first.ch);
            int j = i + 1;
            while (j < chars.size()) {
                StyledChar c = chars.get(j);
                Integer cc = gradient != null ? Integer.valueOf(gradient[j]) : c.color;
                boolean cb = forceBold != null ? forceBold : c.bold;
                if (!java.util.Objects.equals(cc, color) || cb != bold || c.italic != first.italic
                        || c.underlined != first.underlined || c.obfuscated != first.obfuscated) {
                    break;
                }
                run.append(c.ch);
                j++;
            }
            IFormattableTextComponent part = new StringTextComponent(run.toString());
            if (color != null) {
                final int rgb = color;
                part.modifyStyle(style -> style.setColor(Color.fromInt(rgb)));
            }
            if (bold) {
                part.mergeStyle(TextFormatting.BOLD);
            }
            if (first.italic) {
                part.mergeStyle(TextFormatting.ITALIC);
            }
            if (first.underlined) {
                part.mergeStyle(TextFormatting.UNDERLINE);
            }
            if (first.obfuscated) {
                part.mergeStyle(TextFormatting.OBFUSCATED);
            }
            root.appendSibling(part);
            i = j;
        }
        return root;
    }

    /** Colour stops of a gradient, or null if invalid. */
    public static List<Integer> stops(ConfigStore.GradientDef def) {
        if (def == null || def.colors == null || def.colors.size() < 2) {
            return null;
        }
        List<Integer> stops = new ArrayList<>();
        for (String c : def.colors) {
            try {
                stops.add(GradientLogic.parseHex(c));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return stops;
    }

    /** A prefix rendered with a gradient at the current frame (also used for previews). */
    public IFormattableTextComponent gradientText(String legacyText, ConfigStore.GradientDef def) {
        List<StyledChar> chars = chars(legacyText);
        List<Integer> stops = stops(def);
        if (stops == null) {
            return render(chars, null, null);
        }
        GradientLogic.Animation anim = GradientLogic.parseAnimation(def.animation);
        int[] colors = GradientLogic.colors(stops, anim, chars.size(), GradientLogic.phase(frame, def.speed));
        return render(chars, colors, def.bold);
    }

    /** What is shown in front of a player's name: the prefix and the name colour. */
    public static final class Prefix {
        public final IFormattableTextComponent text;
        public final int nameColor;
        public final boolean animated;
        /** Raw Server Utilities prefix when it came from there (for chat), else null. */
        public final String suPrefix;
        public final ConfigStore.GradientDef gradient;

        Prefix(IFormattableTextComponent text, int nameColor, boolean animated, String suPrefix, ConfigStore.GradientDef gradient) {
            this.text = text;
            this.nameColor = nameColor;
            this.animated = animated;
            this.suPrefix = suPrefix;
            this.gradient = gradient;
        }
    }

    /** Gradient keys that apply to a /tabrank rank: "K1.monarch", then "monarch". */
    private ConfigStore.GradientDef gradientFor(Rank rank) {
        ConfigStore.GradientDef d = store.gradient(rank.kingdom.id + "." + rank.role.id);
        return d != null ? d : store.gradient(rank.role.id);
    }

    private boolean gradientsOn() {
        return config().gradients.enabled;
    }

    /** The player's prefix, or null if they have none. */
    public Prefix prefixOf(ServerPlayerEntity player) {
        TabConfig.Players p = config().players;
        Rank rank = useTabRank() ? rankOf(player.getUniqueID()) : null;
        if (rank != null) {
            int kingdomColor = TabLogic.colorOr(rank.kingdom.color, 0xFFFFFF);
            int prefixColor = TabLogic.colorOr(rank.role.color, kingdomColor);
            String prefix = TabLogic.prefix(p.prefixFormat, rank.kingdom.tag, rank.kingdom.name, rank.role.name);
            if (prefix.isEmpty()) {
                return null;
            }
            int nameColor;
            if ("KINGDOM".equalsIgnoreCase(p.nameColor)) {
                nameColor = kingdomColor;
            } else if ("ROLE".equalsIgnoreCase(p.nameColor)) {
                nameColor = prefixColor;
            } else {
                nameColor = TabLogic.colorOr(p.nameColor, 0xFFFFFF);
            }
            String legacy = TabLogic.legacyHex(prefixColor) + (rank.role.bold ? "&l" : "") + prefix;
            ConfigStore.GradientDef def = gradientsOn() ? gradientFor(rank) : null;
            if (def != null && stops(def) != null) {
                return new Prefix(gradientText(legacy, def), nameColor,
                        GradientLogic.animated(GradientLogic.parseAnimation(def.animation)), null, def);
            }
            return new Prefix(render(chars(legacy), null, null), nameColor, false, null, null);
        }
        ServerUtilitiesBridge.SuRank su = shownSuRank(player.getUniqueID());
        if (su == null) {
            return null;
        }
        ConfigStore.GradientDef def = gradientsOn() ? store.gradient(su.rankName) : null;
        List<Integer> stops = stops(def);
        int nameColor = 0xFFFFFF;
        if ("PREFIX".equalsIgnoreCase(p.serverUtilitiesNameColor)) {
            if (stops != null) {
                nameColor = stops.get(stops.size() - 1);
            } else {
                for (StyledChar c : chars(su.prefix)) {
                    if (c.color != null) {
                        nameColor = c.color;
                    }
                }
            }
        } else {
            nameColor = TabLogic.colorOr(p.serverUtilitiesNameColor, 0xFFFFFF);
        }
        if (stops != null) {
            return new Prefix(gradientText(su.prefix, def), nameColor,
                    GradientLogic.animated(GradientLogic.parseAnimation(def.animation)), su.prefix, def);
        }
        return new Prefix(render(chars(su.prefix), null, null), nameColor, false, su.prefix, null);
    }

    /**
     * The prefix text (with &amp; codes) that a gradient key applies to, for previews: a Server
     * Utilities rank name, a /tabrank "kingdom.role" or a role id. Null if the key is unknown.
     */
    public String sourceText(String key) {
        String su = ServerUtilitiesBridge.prefixOfRank(key);
        if (su != null) {
            return su;
        }
        TabConfig cfg = config();
        String kingdomId = null;
        String roleId = key;
        int dot = key.indexOf('.');
        if (dot > 0) {
            kingdomId = key.substring(0, dot);
            roleId = key.substring(dot + 1);
        }
        TabConfig.Role role = cfg.role(roleId);
        TabConfig.Kingdom kingdom = kingdomId == null ? (cfg.kingdoms.isEmpty() ? null : cfg.kingdoms.get(0)) : cfg.kingdom(kingdomId);
        if (role == null || kingdom == null) {
            return null;
        }
        return (role.bold ? "&l" : "") + TabLogic.prefix(cfg.players.prefixFormat, kingdom.tag, kingdom.name, role.name);
    }

    /** Valid gradient keys: Server Utilities ranks, then "kingdom.role" and role ids. */
    public List<String> knownKeys() {
        List<String> keys = new ArrayList<>(ServerUtilitiesBridge.rankNames());
        for (TabConfig.Kingdom k : config().kingdoms) {
            for (TabConfig.Role r : config().roles) {
                keys.add(k.id + "." + r.id);
            }
        }
        for (TabConfig.Role r : config().roles) {
            keys.add(r.id);
        }
        return keys;
    }

    /** Tab-list name, or null to leave the vanilla name (display disabled). */
    public ITextComponent displayName(ServerPlayerEntity player) {
        ITextComponent cached = names.get(player.getUniqueID());
        return cached != null ? cached : buildName(player);
    }

    private ITextComponent buildName(ServerPlayerEntity player) {
        TabConfig cfg = config();
        if (!cfg.enabled) {
            return null;
        }
        TabConfig.Players p = cfg.players;
        String name = player.getGameProfile().getName();
        Prefix prefix = prefixOf(player);
        IFormattableTextComponent root = new StringTextComponent("");
        if (prefix != null) {
            if (!cfg.gradients.inTabList && prefix.gradient != null) {
                // gradients switched off for the tab list: show the prefix's own colours
                root.appendSibling(render(chars(prefix.suPrefix != null ? prefix.suPrefix : prefix.text.getString()), null, null));
            } else {
                root.appendSibling(prefix.text);
            }
            root.appendSibling(new StringTextComponent(" "));
            root.appendSibling(text(name, TabLogic.formatColor(prefix.nameColor), prefix.nameColor, p.nameBold));
        } else {
            root.appendSibling(text(name, p.unrankedNameColor, 0xFFFFFF, p.nameBold));
        }
        if (p.showPingMs) {
            int ms = player.ping;
            root.appendSibling(text(" " + ms + "ms", TabLogic.formatColor(TabLogic.pingColor(ms)), 0xAAAAAA, false));
        }
        return root;
    }

    /**
     * Chat: Server Utilities writes the chat line (its prefix converted to section codes). If the
     * player's Server Utilities rank has a gradient, the prefix inside that line is replaced by
     * the gradient version; everything else of the line is kept as Server Utilities made it
     * (links stay clickable). Returns null to leave the line unchanged.
     */
    public ITextComponent chatLine(ServerPlayerEntity player, ITextComponent original) {
        if (!config().enabled || !gradientsOn() || !config().gradients.inChat || original == null) {
            return null;
        }
        ServerUtilitiesBridge.SuRank su = ServerUtilitiesBridge.rankOf(player.getUniqueID());
        if (su == null || su.prefix.isEmpty()) {
            return null;
        }
        ConfigStore.GradientDef def = store.gradient(su.rankName);
        if (stops(def) == null) {
            return null;
        }
        String formatted = ServerUtilitiesBridge.formatString(su.prefix);
        String line = original.getString();
        int idx = formatted == null || formatted.isEmpty() ? -1 : line.indexOf(formatted);
        if (idx < 0) {
            return null; // the line is not Server Utilities' format (another chat mod): leave it alone
        }
        IFormattableTextComponent out = new StringTextComponent("");
        if (idx > 0) {
            out.appendSibling(net.minecraftforge.common.ForgeHooks.newChatWithLinks(line.substring(0, idx)));
        }
        out.appendSibling(gradientText(su.prefix, def));
        String rest = line.substring(idx + formatted.length());
        if (!rest.isEmpty()) {
            // Reset first: the gradient's last style must not leak into the name and message.
            out.appendSibling(net.minecraftforge.common.ForgeHooks.newChatWithLinks("\u00a7r" + rest));
        }
        return out;
    }

    // ------------------------------------------------------------------ sorting teams

    private void applySortTeams() {
        ServerScoreboard board = server.getScoreboard();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            Rank rank = useTabRank() ? rankOf(player.getUniqueID()) : null;
            ServerUtilitiesBridge.SuRank su = rank == null ? shownSuRank(player.getUniqueID()) : null;
            String wanted = rank != null ? TabLogic.sortTeam(config().kingdomIndex(rank.kingdom), rank.role.order)
                    : su != null ? TabLogic.weightTeam(su.weight) : TabLogic.UNRANKED_TEAM;
            String entry = player.getScoreboardName();
            ScorePlayerTeam current = board.getPlayersTeam(entry);
            if (current != null && !TabLogic.isSortTeam(current.getName())) {
                continue; // another mod's or datapack's team: leave it alone (vanilla order for this player)
            }
            if (current != null && current.getName().equals(wanted)) {
                continue;
            }
            ScorePlayerTeam team = board.getTeam(wanted);
            if (team == null) {
                team = board.createTeam(wanted);
                team.setSeeFriendlyInvisiblesEnabled(false);
            }
            board.addPlayerToTeam(entry, team);
        }
        // Drop empty sorting teams.
        for (ScorePlayerTeam team : new ArrayList<>(board.getTeams())) {
            if (TabLogic.isSortTeam(team.getName()) && team.getMembershipCollection().isEmpty()) {
                board.removeTeam(team);
            }
        }
    }

    /** Removes sorting teams (all of them, or none if sorting stays on). */
    public void cleanupTeams(boolean all) {
        if (!all) {
            return;
        }
        ServerScoreboard board = server.getScoreboard();
        for (ScorePlayerTeam team : new ArrayList<>(board.getTeams())) {
            if (TabLogic.isSortTeam(team.getName())) {
                board.removeTeam(team);
            }
        }
    }

    /** Number of online players who are in another mod's team (so not sorted by rank). */
    public List<String> playersInForeignTeams() {
        List<String> names = new ArrayList<>();
        ServerScoreboard board = server.getScoreboard();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            ScorePlayerTeam team = board.getPlayersTeam(player.getScoreboardName());
            if (team != null && !TabLogic.isSortTeam(team.getName())) {
                names.add(player.getGameProfile().getName() + " (team " + team.getName() + ")");
            }
        }
        return names;
    }
}
