package net.grandtab;

import net.grandtab.config.TabConfig;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.item.ArmorStandEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.server.ServerWorld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Rank prefixes over players' heads, without scoreboard teams (which another mod on the server
 * keeps clearing) and without a client mod.
 *
 * <p>The vanilla nametag itself can only be decorated through a scoreboard team, so the prefix is
 * shown on its own line by invisible label entities that ride the player:
 * <ul>
 *   <li><b>BELOW</b>: one marker armour stand riding the player. Its name floats about 1.85
 *   blocks above the feet, just under the player's own name.</li>
 *   <li><b>ABOVE</b>: two radius-0 area-effect clouds and a marker armour stand stacked on the
 *   player, so the label sits about 2.6 blocks up, above the player's name.</li>
 * </ul>
 * Passengers are positioned by every client each frame, so the label follows smoothly. Label
 * entities:
 * <ul>
 *   <li>have no hitbox, so they never block hits, arrows or clicks;</li>
 *   <li>carry the tag {@link #ENTITY_TAG}, and any tagged entity this session didn't create is
 *   refused when it would join a world (leftovers after a crash);</li>
 *   <li>are removed on logout, death, respawn, dimension change and server stop.</li>
 * </ul>
 * Labels hide while the player sneaks or is invisible, and disappear for spectators, dead players
 * and under water (vanilla drops passengers there). Animated gradients are updated by renaming the
 * label, which costs one small entity-metadata packet per frame and only for animated ranks.
 */
public final class NametagDisplay {
    public static final String ENTITY_TAG = "grandtab_label";

    private final MinecraftServer server;
    private final TabService service;
    private final Map<UUID, State> states = new HashMap<>();
    private static final Set<UUID> OWN_ENTITIES = new HashSet<>();

    private static final class State {
        String position;
        final List<Entity> chain = new ArrayList<>();
        ServerPlayerEntity vehicle;
        String lastJson;
        boolean hidden;
        boolean animated;
        boolean waterHold;
    }

    NametagDisplay(MinecraftServer server, TabService service) {
        this.server = server;
        this.service = service;
    }

    private TabConfig.Nametags settings() {
        return service.config().gradients.nametags;
    }

    void tick(long ticks, boolean refresh, boolean newFrame) {
        if (!refresh && !newFrame && ticks % 5 != 0) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            State state = states.get(player.getUniqueID());
            boolean render = refresh || (newFrame && state != null && state.animated);
            if (refresh || ticks % 5 == 0 || render) {
                apply(player, render);
            }
        }
    }

    private void apply(ServerPlayerEntity player, boolean render) {
        State state = states.computeIfAbsent(player.getUniqueID(), k -> new State());
        TabConfig cfg = service.config();
        TabService.Prefix prefix = null;
        if (cfg.enabled && cfg.gradients.enabled && settings().enabled) {
            prefix = service.prefixOf(player);
            if (prefix != null && prefix.gradient == null && !settings().showAllPrefixes) {
                prefix = null;
            }
        }
        String position = "BELOW".equalsIgnoreCase(settings().position) ? "BELOW" : "ABOVE";
        if (prefix == null) {
            removeChain(state);
            state.position = null;
            return;
        }
        if (!position.equals(state.position)) {
            removeChain(state);
            state.position = position;
            render = true;
        }
        state.animated = prefix.animated;
        if (!player.isAlive() || player.isSpectator()) {
            removeChain(state);
            return;
        }
        if (state.waterHold && !player.isInWater()) {
            state.waterHold = false;
        }
        if (!chainValid(player, state)) {
            boolean broken = !state.chain.isEmpty() && state.vehicle == player;
            removeChain(state);
            if (broken && player.isInWater()) {
                state.waterHold = true; // vanilla drops passengers under water; retry once out of it
            }
            if (state.waterHold) {
                return;
            }
            spawnChain(player, state, position);
            render = true;
        }
        if (state.chain.isEmpty()) {
            return;
        }
        Entity label = state.chain.get(state.chain.size() - 1);
        if (render || state.lastJson == null) {
            ITextComponent text = prefix.text;
            String json = ITextComponent.Serializer.toJson(text);
            if (!json.equals(state.lastJson)) {
                label.setCustomName(text);
                state.lastJson = json;
            }
        }
        boolean hidden = player.isSneaking() || player.isInvisible();
        if (hidden != state.hidden || label.isCustomNameVisible() == hidden) {
            label.setCustomNameVisible(!hidden);
            state.hidden = hidden;
        }
    }

    private static boolean chainValid(ServerPlayerEntity player, State state) {
        if (state.chain.isEmpty() || state.vehicle != player) {
            return false;
        }
        Entity below = player;
        for (Entity e : state.chain) {
            if (!e.isAlive() || e.world != player.world || e.getRidingEntity() != below) {
                return false;
            }
            below = e;
        }
        return true;
    }

    private void spawnChain(ServerPlayerEntity player, State state, String position) {
        ServerWorld world = player.getServerWorld();
        List<Entity> chain = new ArrayList<>();
        if (position.equals("ABOVE")) {
            chain.add(cloud(world, player));
            chain.add(cloud(world, player));
        }
        chain.add(marker(world, player));
        for (Entity e : chain) {
            if (e == null) {
                return;
            }
        }
        Entity below = player;
        List<Entity> added = new ArrayList<>();
        for (Entity e : chain) {
            synchronized (OWN_ENTITIES) {
                OWN_ENTITIES.add(e.getUniqueID());
            }
            if (!world.addEntity(e)) {
                for (Entity created : added) {
                    discard(created);
                }
                synchronized (OWN_ENTITIES) {
                    OWN_ENTITIES.remove(e.getUniqueID());
                }
                return;
            }
            added.add(e);
            e.startRiding(below, true);
            below = e;
        }
        state.chain.addAll(chain);
        state.vehicle = player;
        state.lastJson = null;
        state.hidden = false;
    }

    private static ArmorStandEntity marker(ServerWorld world, ServerPlayerEntity player) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) {
            return null;
        }
        stand.setPosition(player.getPosX(), player.getPosY() + 1.85, player.getPosZ());
        CompoundNBT nbt = stand.writeWithoutTypeId(new CompoundNBT());
        nbt.putBoolean("Marker", true);
        nbt.putBoolean("Invisible", true);
        nbt.putBoolean("NoGravity", true);
        nbt.putBoolean("Invulnerable", true);
        nbt.putBoolean("Silent", true);
        nbt.putBoolean("NoBasePlate", true);
        nbt.putInt("DisabledSlots", 0x1F1F1F);
        stand.read(nbt);
        stand.addTag(ENTITY_TAG);
        stand.setCustomNameVisible(true);
        return stand;
    }

    private static AreaEffectCloudEntity cloud(ServerWorld world, ServerPlayerEntity player) {
        AreaEffectCloudEntity cloud = EntityType.AREA_EFFECT_CLOUD.create(world);
        if (cloud == null) {
            return null;
        }
        cloud.setPosition(player.getPosX(), player.getPosY() + 1.35, player.getPosZ());
        cloud.setRadius(0.0F);
        cloud.setWaitTime(0);
        cloud.setDuration(Integer.MAX_VALUE);
        cloud.setInvulnerable(true);
        cloud.setSilent(true);
        cloud.addTag(ENTITY_TAG);
        return cloud;
    }

    private static void discard(Entity e) {
        if (e != null) {
            synchronized (OWN_ENTITIES) {
                OWN_ENTITIES.remove(e.getUniqueID());
            }
            e.stopRiding();
            e.remove();
        }
    }

    private static void removeChain(State state) {
        for (int i = state.chain.size() - 1; i >= 0; i--) {
            discard(state.chain.get(i));
        }
        state.chain.clear();
        state.vehicle = null;
        state.lastJson = null;
    }

    void onLogout(ServerPlayerEntity player) {
        State state = states.remove(player.getUniqueID());
        if (state != null) {
            removeChain(state);
        }
    }

    /** Death, respawn or dimension change: labels are rebuilt on the next check. */
    public void onPlayerChanged(ServerPlayerEntity player) {
        State state = states.get(player.getUniqueID());
        if (state != null) {
            removeChain(state);
        }
    }

    void stop() {
        for (State state : states.values()) {
            removeChain(state);
        }
        states.clear();
    }

    /** True for a label entity this session didn't create (left over from a crash). */
    public static boolean isStray(Entity entity) {
        if (!entity.getTags().contains(ENTITY_TAG)) {
            return false;
        }
        synchronized (OWN_ENTITIES) {
            return !OWN_ENTITIES.contains(entity.getUniqueID());
        }
    }
}
