package net.grandtab.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.grandtab.GrandTab;
import net.grandtab.ServerUtilitiesBridge;
import net.grandtab.TabService;
import net.grandtab.config.ConfigStore;
import net.grandtab.logic.GradientLogic;
import net.grandtab.logic.TabLogic;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.ISuggestionProvider;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;
import net.minecraft.util.text.event.HoverEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rank gradients, keyed by Server Utilities rank name (e.g. testking), or for /tabrank ranks by
 * "kingdom.role" (K1.monarch) or role id (monarch).
 * <pre>
 * /rankgradient set &lt;rank&gt; &lt;#hex&gt; &lt;#hex&gt; [...]   two or more colour stops
 * /rankgradient addcolor &lt;rank&gt; &lt;#hex&gt; | setcolor &lt;rank&gt; &lt;n&gt; &lt;#hex&gt; | removecolor &lt;rank&gt; &lt;n&gt;
 * /rankgradient animate &lt;rank&gt; static|wave|pulse|rainbow|shine
 * /rankgradient speed &lt;rank&gt; &lt;1-10&gt;
 * /rankgradient static &lt;rank&gt;                    same as animate static
 * /rankgradient bold &lt;rank&gt; true|false|default
 * /rankgradient remove &lt;rank&gt; | list | preview &lt;rank&gt; | reload
 * /rankgradient gui [rank]                         clickable editor in chat
 * </pre>
 * Every change is saved to config/grandtab/gradients.json at once and shows within a second.
 */
public final class GradientCommand {
    private GradientCommand() {
    }

    private static final List<String> PALETTE = Arrays.asList(
            "#FFFFFF", "#AAAAAA", "#555555", "#000000", "#FF5555", "#AA0000", "#DC143C", "#FF4500",
            "#FFAA00", "#FFD700", "#FFFF55", "#7CFC00", "#55FF55", "#00AA00", "#00FA9A", "#55FFFF",
            "#00AAAA", "#1E90FF", "#5555FF", "#0000AA", "#9400D3", "#AA00AA", "#FF55FF", "#FF69B4");

    private static TabService service() {
        return GrandTab.service();
    }

    private static int level() {
        try {
            return GrandTab.store().config().commandPermissionLevel;
        } catch (RuntimeException e) {
            return 2;
        }
    }

    private static final SuggestionProvider<CommandSource> RANKS = (ctx, b) -> {
        Set<String> keys = new LinkedHashSet<>(GrandTab.store().gradients().keySet());
        TabService s = service();
        if (s != null) {
            keys.addAll(s.knownKeys());
        }
        return ISuggestionProvider.suggest(keys, b);
    };

    private static final SuggestionProvider<CommandSource> ANIMATIONS = (ctx, b) ->
            ISuggestionProvider.suggest(Arrays.asList("static", "wave", "pulse", "rainbow", "shine"), b);

    private static final SuggestionProvider<CommandSource> COLORS = (ctx, b) -> ISuggestionProvider.suggest(PALETTE, b);

    private static RequiredArgumentBuilder<CommandSource, String> rank() {
        return Commands.argument("rank", StringArgumentType.word()).suggests(RANKS);
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("rankgradient")
                .requires(src -> src.hasPermissionLevel(level()))
                .then(Commands.literal("set").then(rank().then(Commands.argument("colors", StringArgumentType.greedyString())
                        .suggests(COLORS).executes(GradientCommand::set))))
                .then(Commands.literal("addcolor").then(rank().then(Commands.argument("color", StringArgumentType.word())
                        .suggests(COLORS).executes(GradientCommand::addColor))))
                .then(Commands.literal("setcolor").then(rank().then(Commands.argument("index", IntegerArgumentType.integer(1, 64))
                        .then(Commands.argument("color", StringArgumentType.word()).suggests(COLORS).executes(GradientCommand::setColor)))))
                .then(Commands.literal("removecolor").then(rank().then(Commands.argument("index", IntegerArgumentType.integer(1, 64))
                        .executes(GradientCommand::removeColor))))
                .then(Commands.literal("animate").then(rank().then(Commands.argument("animation", StringArgumentType.word())
                        .suggests(ANIMATIONS).executes(ctx -> animate(ctx, StringArgumentType.getString(ctx, "animation"))))))
                .then(Commands.literal("static").then(rank().executes(ctx -> animate(ctx, "static"))))
                .then(Commands.literal("speed").then(rank().then(Commands.argument("speed",
                        IntegerArgumentType.integer(GradientLogic.MIN_SPEED, GradientLogic.MAX_SPEED)).executes(GradientCommand::speed))))
                .then(Commands.literal("bold").then(rank().then(Commands.argument("value", StringArgumentType.word())
                        .suggests((c, b) -> ISuggestionProvider.suggest(Arrays.asList("true", "false", "default"), b))
                        .executes(GradientCommand::bold))))
                .then(Commands.literal("remove").then(rank().executes(GradientCommand::remove)))
                .then(Commands.literal("preview").then(rank().executes(GradientCommand::preview)))
                .then(Commands.literal("list").executes(GradientCommand::list))
                .then(Commands.literal("reload").executes(GradientCommand::reload))
                .then(Commands.literal("gui")
                        .executes(ctx -> gui(ctx, null))
                        .then(rank().executes(ctx -> gui(ctx, key(ctx))))));
    }

    // ------------------------------------------------------------------ helpers

    private static String key(CommandContext<CommandSource> ctx) {
        return StringArgumentType.getString(ctx, "rank").toLowerCase(Locale.ROOT);
    }

    private static int fail(CommandContext<CommandSource> ctx, String text) {
        ctx.getSource().sendErrorMessage(new StringTextComponent("[Gradient] " + text));
        return 0;
    }

    private static void info(CommandContext<CommandSource> ctx, IFormattableTextComponent text) {
        ctx.getSource().sendFeedback(text, false);
    }

    private static IFormattableTextComponent plain(String text, TextFormatting color) {
        return new StringTextComponent(text).mergeStyle(color);
    }

    /** True if the key names a Server Utilities rank or a /tabrank kingdom.role / role. */
    private static boolean known(String key) {
        if (GrandTab.store().gradients().containsKey(key)) {
            return true;
        }
        TabService s = service();
        if (s == null) {
            return true; // cannot check without a running server
        }
        for (String k : s.knownKeys()) {
            if (k.equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static String unknownMessage(String key) {
        List<String> su = ServerUtilitiesBridge.rankNames();
        return "No rank '" + key + "'. Server Utilities ranks: " + (su.isEmpty() ? "(none found: " + ServerUtilitiesBridge.status() + ")"
                : String.join(", ", su)) + ". For /tabrank ranks use kingdom.role (K1.monarch) or a role id.";
    }

    private static String hex(String text) {
        return TabLogic.formatColor(GradientLogic.parseHex(text));
    }

    /** Saves, refreshes the displays and confirms (re-opening the editor for players). */
    private static int saved(CommandContext<CommandSource> ctx, String key, String message) {
        if (!GrandTab.store().saveGradients()) {
            return fail(ctx, "Changed for now, but gradients.json could not be written (see the server log).");
        }
        TabService s = service();
        if (s != null) {
            s.markDirty();
        }
        ctx.getSource().sendFeedback(plain("[Gradient] " + message, TextFormatting.GREEN), true);
        if (key != null && ctx.getSource().getEntity() instanceof ServerPlayerEntity) {
            showEditor(ctx, key);
        }
        return 1;
    }

    private static ConfigStore.GradientDef existing(CommandContext<CommandSource> ctx, String key) {
        ConfigStore.GradientDef def = GrandTab.store().gradient(key);
        if (def == null) {
            fail(ctx, "'" + key + "' has no gradient yet. Start with /rankgradient set " + key + " #FFAA00 #FFFFFF");
        }
        return def;
    }

    // ------------------------------------------------------------------ commands

    private static int set(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        if (!known(key)) {
            return fail(ctx, unknownMessage(key));
        }
        String[] parts = StringArgumentType.getString(ctx, "colors").trim().split("[\\s,]+");
        if (parts.length < 2) {
            return fail(ctx, "A gradient needs at least two colours, e.g. #FFAA00 #FFFF55 #FFFFFF");
        }
        List<String> colors = new ArrayList<>();
        for (String p : parts) {
            try {
                colors.add(hex(p));
            } catch (IllegalArgumentException e) {
                return fail(ctx, "'" + p + "' is not a colour. Use #RRGGBB (e.g. #FFAA00) or a name like gold.");
            }
        }
        ConfigStore.GradientDef def = GrandTab.store().gradient(key);
        if (def == null) {
            def = new ConfigStore.GradientDef();
            GrandTab.store().gradients().put(key, def);
        }
        def.colors = colors;
        return saved(ctx, key, "Gradient of " + key + ": " + String.join(" → ", colors));
    }

    private static int addColor(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        String color;
        try {
            color = hex(StringArgumentType.getString(ctx, "color"));
        } catch (IllegalArgumentException e) {
            return fail(ctx, "Not a colour. Use #RRGGBB or a name like gold.");
        }
        ConfigStore.GradientDef def = GrandTab.store().gradient(key);
        if (def == null) {
            if (!known(key)) {
                return fail(ctx, unknownMessage(key));
            }
            // A new gradient starts as the colour twice, so it is valid right away.
            def = new ConfigStore.GradientDef();
            def.colors.add(color);
            def.colors.add(color);
            GrandTab.store().gradients().put(key, def);
            return saved(ctx, key, "Gradient of " + key + " started with " + color + ". Add more colours.");
        }
        def.colors.add(color);
        return saved(ctx, key, "Added " + color + " to " + key + ".");
    }

    private static int setColor(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        ConfigStore.GradientDef def = existing(ctx, key);
        if (def == null) {
            return 0;
        }
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (index > def.colors.size()) {
            return fail(ctx, key + " has only " + def.colors.size() + " colours.");
        }
        try {
            def.colors.set(index - 1, hex(StringArgumentType.getString(ctx, "color")));
        } catch (IllegalArgumentException e) {
            return fail(ctx, "Not a colour. Use #RRGGBB or a name like gold.");
        }
        return saved(ctx, key, "Colour " + index + " of " + key + " set.");
    }

    private static int removeColor(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        ConfigStore.GradientDef def = existing(ctx, key);
        if (def == null) {
            return 0;
        }
        int index = IntegerArgumentType.getInteger(ctx, "index");
        if (index > def.colors.size()) {
            return fail(ctx, key + " has only " + def.colors.size() + " colours.");
        }
        if (def.colors.size() <= 2) {
            return fail(ctx, "A gradient needs at least two colours. Use /rankgradient remove " + key + " to delete it.");
        }
        def.colors.remove(index - 1);
        return saved(ctx, key, "Colour " + index + " removed from " + key + ".");
    }

    private static int animate(CommandContext<CommandSource> ctx, String animation) {
        String key = key(ctx);
        ConfigStore.GradientDef def = existing(ctx, key);
        if (def == null) {
            return 0;
        }
        GradientLogic.Animation a = GradientLogic.parseAnimation(animation);
        if (a == null) {
            return fail(ctx, "Unknown animation. Use static, wave, pulse, rainbow or shine.");
        }
        def.animation = a.name();
        return saved(ctx, key, key + " animation: " + a.name().toLowerCase(Locale.ROOT) + ".");
    }

    private static int speed(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        ConfigStore.GradientDef def = existing(ctx, key);
        if (def == null) {
            return 0;
        }
        def.speed = IntegerArgumentType.getInteger(ctx, "speed");
        return saved(ctx, key, key + " speed: " + def.speed + ".");
    }

    private static int bold(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        ConfigStore.GradientDef def = existing(ctx, key);
        if (def == null) {
            return 0;
        }
        String v = StringArgumentType.getString(ctx, "value").toLowerCase(Locale.ROOT);
        def.bold = v.equals("default") ? null : Boolean.valueOf(v.equals("true"));
        return saved(ctx, key, key + " bold: " + (def.bold == null ? "as in the rank prefix" : def.bold) + ".");
    }

    private static int remove(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        if (GrandTab.store().gradients().remove(key) == null) {
            return fail(ctx, "'" + key + "' has no gradient.");
        }
        return saved(ctx, null, "Gradient of " + key + " removed; its normal prefix colours are back.");
    }

    private static int list(CommandContext<CommandSource> ctx) {
        Map<String, ConfigStore.GradientDef> all = GrandTab.store().gradients();
        info(ctx, plain("Rank gradients: " + all.size(), TextFormatting.GOLD));
        TabService s = service();
        for (Map.Entry<String, ConfigStore.GradientDef> e : all.entrySet()) {
            ConfigStore.GradientDef d = e.getValue();
            IFormattableTextComponent line = plain(" " + e.getKey() + "  ", TextFormatting.YELLOW);
            if (s != null) {
                String source = s.sourceText(e.getKey());
                line.appendSibling(s.gradientText(source == null ? e.getKey() : source, d));
                line.appendSibling(plain("  ", TextFormatting.GRAY));
            }
            line.appendSibling(plain(String.join(" ", d.colors) + ", " + d.animation.toLowerCase(Locale.ROOT)
                    + (GradientLogic.animated(GradientLogic.parseAnimation(d.animation)) ? " speed " + d.speed : ""), TextFormatting.GRAY));
            line.modifyStyle(st -> st.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rankgradient gui " + e.getKey()))
                    .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent("Open the editor"))));
            info(ctx, line);
        }
        return 1;
    }

    private static int preview(CommandContext<CommandSource> ctx) {
        String key = key(ctx);
        ConfigStore.GradientDef def = existing(ctx, key);
        TabService s = service();
        if (def == null || s == null) {
            return 0;
        }
        String source = s.sourceText(key);
        IFormattableTextComponent line = plain("Preview: ", TextFormatting.GRAY);
        line.appendSibling(s.gradientText(source == null ? key : source, def));
        line.appendSibling(plain(" PlayerName", TextFormatting.WHITE));
        info(ctx, line);
        return 1;
    }

    private static int reload(CommandContext<CommandSource> ctx) {
        String problem = GrandTab.store().loadGradients();
        TabService s = service();
        if (s != null) {
            s.markDirty();
        }
        if (problem != null) {
            return fail(ctx, problem);
        }
        ctx.getSource().sendFeedback(plain("[Gradient] Reloaded gradients.json (" + GrandTab.store().gradients().size()
                + " gradients).", TextFormatting.GREEN), true);
        return 1;
    }

    // ------------------------------------------------------------------ chat editor

    private static IFormattableTextComponent button(String label, TextFormatting color, String command, boolean run, String hover) {
        IFormattableTextComponent b = plain(label, color);
        b.modifyStyle(st -> st.setClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(hover))));
        return b;
    }

    private static IFormattableTextComponent swatch(String hexColor, String command, boolean run, String hover) {
        int rgb = GradientLogic.parseHex(hexColor);
        IFormattableTextComponent b = new StringTextComponent("█");
        b.modifyStyle(st -> st.setColor(Color.fromInt(rgb))
                .setClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
                .setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new StringTextComponent(hover))));
        return b;
    }

    private static int gui(CommandContext<CommandSource> ctx, String key) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayerEntity)) {
            return fail(ctx, "The editor is for players in game. From the console use the other /rankgradient commands.");
        }
        if (key == null) {
            showRankPicker(ctx);
            return 1;
        }
        if (!known(key)) {
            return fail(ctx, unknownMessage(key));
        }
        showEditor(ctx, key);
        return 1;
    }

    private static void showRankPicker(CommandContext<CommandSource> ctx) {
        info(ctx, plain("==== Rank gradient editor: pick a rank ====", TextFormatting.GOLD));
        TabService s = service();
        List<String> keys = new ArrayList<>(ServerUtilitiesBridge.rankNames());
        for (String k : GrandTab.store().gradients().keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        if (keys.isEmpty()) {
            info(ctx, plain(" No Server Utilities ranks found. Type /rankgradient gui <rank> (e.g. K1.monarch).", TextFormatting.GRAY));
        }
        for (String k : keys) {
            IFormattableTextComponent line = button(" [" + k + "] ", TextFormatting.YELLOW, "/rankgradient gui " + k, true, "Edit " + k);
            if (s != null) {
                String source = s.sourceText(k);
                ConfigStore.GradientDef def = GrandTab.store().gradient(k);
                if (source != null) {
                    line.appendSibling(def != null ? s.gradientText(source, def) : s.gradientText(source, null));
                }
            }
            info(ctx, line);
        }
    }

    private static void showEditor(CommandContext<CommandSource> ctx, String key) {
        TabService s = service();
        ConfigStore.GradientDef def = GrandTab.store().gradient(key);
        String source = s == null ? null : s.sourceText(key);
        String cmd = "/rankgradient ";

        info(ctx, plain("==== Gradient editor: " + key + " ====", TextFormatting.GOLD));
        IFormattableTextComponent preview = plain(" Preview: ", TextFormatting.GRAY);
        if (s != null) {
            preview.appendSibling(s.gradientText(source == null ? key : source, def));
            preview.appendSibling(plain(" PlayerName", TextFormatting.WHITE));
        }
        info(ctx, preview);

        IFormattableTextComponent colors = plain(" Colours: ", TextFormatting.GRAY);
        if (def == null) {
            colors.appendSibling(plain("none yet - click a colour below to start ", TextFormatting.DARK_GRAY));
        } else {
            for (int i = 0; i < def.colors.size(); i++) {
                String c = def.colors.get(i);
                int n = i + 1;
                colors.appendSibling(swatch(c, cmd + "setcolor " + key + " " + n + " " + c, false, n + ": " + c + "\nClick to change"));
                colors.appendSibling(button("×", TextFormatting.RED, cmd + "removecolor " + key + " " + n, true, "Remove colour " + n));
                colors.appendSibling(plain(" ", TextFormatting.GRAY));
            }
        }
        colors.appendSibling(button("[+ hex]", TextFormatting.GREEN, cmd + "addcolor " + key + " #", false, "Type any #RRGGBB colour"));
        info(ctx, colors);

        IFormattableTextComponent palette = plain(" Add: ", TextFormatting.GRAY);
        for (String c : PALETTE) {
            palette.appendSibling(swatch(c, cmd + "addcolor " + key + " " + c, true, "Add " + c));
        }
        info(ctx, palette);

        if (def != null) {
            IFormattableTextComponent anim = plain(" Animation: ", TextFormatting.GRAY);
            for (GradientLogic.Animation a : GradientLogic.Animation.values()) {
                boolean current = a.name().equalsIgnoreCase(def.animation);
                String name = a.name().toLowerCase(Locale.ROOT);
                anim.appendSibling(button(current ? "[" + name + "]" : " " + name + " ",
                        current ? TextFormatting.AQUA : TextFormatting.DARK_AQUA, cmd + "animate " + key + " " + name, true, "Use " + name));
            }
            info(ctx, anim);

            IFormattableTextComponent speed = plain(" Speed: ", TextFormatting.GRAY);
            int sp = def.speed;
            speed.appendSibling(button("[-]", TextFormatting.RED, cmd + "speed " + key + " " + Math.max(GradientLogic.MIN_SPEED, sp - 1), true, "Slower"));
            speed.appendSibling(plain(" " + sp + " ", TextFormatting.WHITE));
            speed.appendSibling(button("[+]", TextFormatting.GREEN, cmd + "speed " + key + " " + Math.min(GradientLogic.MAX_SPEED, sp + 1), true, "Faster"));
            speed.appendSibling(plain("   Bold: ", TextFormatting.GRAY));
            speed.appendSibling(button(def.bold == null ? "[default]" : def.bold ? "[on]" : "[off]", TextFormatting.YELLOW,
                    cmd + "bold " + key + " " + (def.bold == null ? "true" : def.bold ? "false" : "default"), true, "Cycle default / on / off"));
            info(ctx, speed);

            IFormattableTextComponent actions = plain(" ", TextFormatting.GRAY);
            actions.appendSibling(button("[Remove gradient]", TextFormatting.RED, cmd + "remove " + key, false, "Click, then press Enter to confirm"));
            actions.appendSibling(plain("  ", TextFormatting.GRAY));
            actions.appendSibling(button("[Other ranks]", TextFormatting.YELLOW, cmd + "gui", true, "Pick another rank"));
            info(ctx, actions);
        }
        info(ctx, plain(" Changes are saved instantly.", TextFormatting.DARK_GRAY));
    }
}
