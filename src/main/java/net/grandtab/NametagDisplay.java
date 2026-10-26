package net.grandtab;

import io.netty.buffer.Unpooled;
import net.grandtab.config.TabConfig;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.item.ArmorStandEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.IPacket;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SDestroyEntitiesPacket;
import net.minecraft.network.play.server.SEntityMetadataPacket;
import net.minecraft.network.play.server.SSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.fml.network.PacketDistributor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rank prefixes over players' heads, without scoreboard teams and without a client mod.
 *
 * <h2>Why b1-b3 left labels floating in the air</h2>
 * Up to 1.0.0-b3 the label was a real (server-side) marker armour stand riding the player. For
 * an entity that is a passenger, the server never sends position updates (vanilla
 * {@code TrackedEntity} only sends rotation and metadata for passengers): the only thing that
 * keeps the label in the right place on a client is the client-side "riding" link. That link is
 * created and destroyed by packets from two <i>independent</i> trackers (the player's and the
 * label's). Whenever a client lost the link while still knowing the label (the player was
 * despawned on that client by range, death/respawn, a dimension change, a teleport or a
 * passenger update from the player's tracker that didn't list the label, or the passenger packet
 * arrived before the label existed on that client), the client kept a free label that never got
 * another position update, so it froze in the sky where the link broke. On the server the chain
 * was still valid, so the old check ({@code chainValid}, server state only) never noticed.
 *
 * <h2>How labels work now</h2>
 * The label entities only exist on the clients. The server creates entity objects for them
 * (unique entity ids, metadata) but never adds them to a world, so there is nothing to strand,
 * save, tick or orphan on the server, and other mods never see extra passengers on players.
 * They are sent with exactly the life cycle of the player as each client sees it:
 * <ul>
 *   <li>when a client starts tracking the player (Forge {@code PlayerEvent.StartTracking}, fired
 *   after the player's own spawn packets), it receives the label spawn, its metadata and the
 *   riding links;</li>
 *   <li>when it stops tracking the player (range, death, respawn, dimension change, logout:
 *   Forge {@code StopTracking}), it receives the label's destroy packet;</li>
 *   <li>the riding links are re-sent to everyone tracking the player every 2 seconds and
 *   whenever the player's real passengers change, so any client-side loss of the link heals
 *   within 2 seconds (the label can never stay stranded);</li>
 *   <li>name, animation frames and sneaking visibility are metadata updates to the same
 *   clients.</li>
 * </ul>
 * BELOW: one marker armour stand riding the player (name about 1.85 blocks up, under the
 * player's name). ABOVE: two radius-0 area-effect clouds and the stand (name about 2.6 blocks
 * up, above the player's name). A player never sees their own label (like vanilla nametags).
 */
public final class NametagDisplay {
    /** Tag of the real label entities created by 1.0.0-b1 to b3; any such entity is a leftover. */
    public static final String ENTITY_TAG = "grandtab_label";
    private static final int RELINK_TICKS = 40;

    private final MinecraftServer server;
    private final TabService service;
    private final Map<UUID, Label> labels = new HashMap<>();

    /** A client-side label chain for one player. */
    private static final class Label {
        final String position;
        /** Bottom to top; the last one carries the name. Never added to a world. */
        final List<Entity> parts;
        ServerPlayerEntity player;
        boolean shown;
        String json;
        boolean nameVisible = true;
        List<Integer> realPassengers = new ArrayList<>();

        Label(String position, List<Entity> parts, ServerPlayerEntity player) {
            this.position = position;
            this.parts = parts;
            this.player = player;
        }

        Entity top() {
            return parts.get(parts.size() - 1);
        }

        int[] ids() {
            int[] ids = new int[parts.size()];
            for (int i = 0; i < parts.size(); i++) {
                ids[i] = parts.get(i).getEntityId();
            }
            return ids;
        }
    }

    NametagDisplay(MinecraftServer server, TabService service) {
        this.server = server;
        this.service = service;
    }

    private TabConfig.Nametags settings() {
        return service.config().gradients.nametags;
    }

    // ------------------------------------------------------------------ ticking

    void tick(long ticks, boolean refresh, boolean newFrame) {
        boolean check = refresh || ticks % 5 == 0;
        boolean relink = ticks % RELINK_TICKS == 0;
        if (!check && !newFrame && !relink) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
            Label label = labels.get(player.getUniqueID());
            if (check || (newFrame && label != null && label.shown)) {
                apply(player, refresh || newFrame);
            }
            label = labels.get(player.getUniqueID());
            if (relink && label != null && label.shown) {
                sendToTrackers(player, links(label, player));
            }
        }
        labels.keySet().removeIf(id -> server.getPlayerList().getPlayerByUUID(id) == null);
    }

    private void apply(ServerPlayerEntity player, boolean render) {
        TabConfig cfg = service.config();
        TabService.Prefix prefix = null;
        if (cfg.enabled && cfg.gradients.enabled && settings().enabled) {
            prefix = service.prefixOf(player);
            if (prefix != null && prefix.gradient == null && !settings().showAllPrefixes) {
                prefix = null;
            }
        }
        Label label = labels.get(player.getUniqueID());
        if (prefix == null) {
            if (label != null) {
                hide(label);
                labels.remove(player.getUniqueID());
            }
            return;
        }
        String position = "BELOW".equalsIgnoreCase(settings().position) ? "BELOW" : "ABOVE";
        if (label == null || !label.position.equals(position) || label.player != player) {
            if (label != null) {
                hide(label); // the old player object (respawn) or the old layout
            }
            label = create(player, position);
            if (label == null) {
                return;
            }
            labels.put(player.getUniqueID(), label);
            render = true;
        }
        boolean visible = player.isAlive() && !player.isSpectator() && !player.isInvisible();
        if (!visible) {
            hide(label);
            return;
        }
        boolean metadataChanged = false;
        if (render || label.json == null) {
            ITextComponent text = prefix.text;
            String json = ITextComponent.Serializer.toJson(text);
            if (!json.equals(label.json)) {
                label.top().setCustomName(text);
                label.json = json;
                metadataChanged = true;
            }
        }
        boolean nameVisible = !player.isSneaking(); // like vanilla: no label through walls while sneaking
        if (nameVisible != label.nameVisible) {
            label.top().setCustomNameVisible(nameVisible);
            label.nameVisible = nameVisible;
            metadataChanged = true;
        }
        if (!label.shown) {
            sendToTrackers(player, spawn(label, player));
            label.shown = true;
            label.realPassengers = realPassengers(player);
            return;
        }
        if (metadataChanged) {
            sendToTrackers(player, new SEntityMetadataPacket(label.top().getEntityId(), label.top().getDataManager(), true));
        }
        List<Integer> real = realPassengers(player);
        if (!real.equals(label.realPassengers)) {
            // The player's own tracker just sent its passenger list without the label: re-link.
            label.realPassengers = real;
            sendToTrackers(player, links(label, player));
        }
    }

    // ------------------------------------------------------------------ label entities (client-side only)

    private Label create(ServerPlayerEntity player, String position) {
        ServerWorld world = player.getServerWorld();
        List<Entity> parts = new ArrayList<>();
        if (position.equals("ABOVE")) {
            parts.add(cloud(world));
            parts.add(cloud(world));
        }
        parts.add(marker(world));
        for (Entity e : parts) {
            if (e == null) {
                return null;
            }
        }
        return new Label(position, parts, player);
    }

    private static ArmorStandEntity marker(ServerWorld world) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) {
            return null;
        }
        CompoundNBT nbt = stand.writeWithoutTypeId(new CompoundNBT());
        nbt.putBoolean("Marker", true);
        nbt.putBoolean("Invisible", true);
        nbt.putBoolean("NoGravity", true);
        nbt.putBoolean("Invulnerable", true);
        nbt.putBoolean("Silent", true);
        nbt.putBoolean("NoBasePlate", true);
        stand.read(nbt);
        stand.setCustomNameVisible(true);
        return stand;
    }

    private static AreaEffectCloudEntity cloud(ServerWorld world) {
        AreaEffectCloudEntity cloud = EntityType.AREA_EFFECT_CLOUD.create(world);
        if (cloud == null) {
            return null;
        }
        cloud.setRadius(0.0F);
        cloud.setWaitTime(0);
        cloud.setDuration(Integer.MAX_VALUE);
        cloud.setSilent(true);
        return cloud;
    }

    private static List<Integer> realPassengers(ServerPlayerEntity player) {
        List<Integer> ids = new ArrayList<>();
        for (Entity e : player.getPassengers()) {
            ids.add(e.getEntityId());
        }
        return ids;
    }

    /** Riding links: player -> first part (plus the player's real passengers), part -> next part. */
    private List<IPacket<?>> links(Label label, ServerPlayerEntity player) {
        List<IPacket<?>> packets = new ArrayList<>();
        List<Integer> riders = realPassengers(player);
        riders.add(label.parts.get(0).getEntityId());
        packets.add(passengers(player.getEntityId(), riders));
        for (int i = 0; i + 1 < label.parts.size(); i++) {
            List<Integer> one = new ArrayList<>();
            one.add(label.parts.get(i + 1).getEntityId());
            packets.add(passengers(label.parts.get(i).getEntityId(), one));
        }
        return packets;
    }

    private static IPacket<?> passengers(int vehicle, List<Integer> riders) {
        int[] ids = new int[riders.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = riders.get(i);
        }
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        SSetPassengersPacket packet = new SSetPassengersPacket();
        try {
            buffer.writeVarInt(vehicle);
            buffer.writeVarIntArray(ids);
            packet.readPacketData(buffer); // filled through its own decoder: no private fields touched
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            buffer.release();
        }
        return packet;
    }

    /** Everything a client needs to show the label: spawn, metadata, riding links. */
    private List<IPacket<?>> spawn(Label label, ServerPlayerEntity player) {
        List<IPacket<?>> packets = new ArrayList<>();
        double y = player.getPosY() + player.getHeight();
        for (Entity part : label.parts) {
            part.setPosition(player.getPosX(), y, player.getPosZ()); // only the first frame; riding takes over
            packets.add(part.createSpawnPacket());
            packets.add(new SEntityMetadataPacket(part.getEntityId(), part.getDataManager(), true));
        }
        packets.addAll(links(label, player));
        return packets;
    }

    private void hide(Label label) {
        if (label.shown) {
            sendToTrackers(label.player, new SDestroyEntitiesPacket(label.ids()));
            label.shown = false;
        }
    }

    private static void sendToTrackers(ServerPlayerEntity player, IPacket<?> packet) {
        PacketDistributor.TRACKING_ENTITY.with(() -> player).send(packet);
    }

    private static void sendToTrackers(ServerPlayerEntity player, List<IPacket<?>> packets) {
        for (IPacket<?> p : packets) {
            sendToTrackers(player, p);
        }
    }

    // ------------------------------------------------------------------ events

    /** A client starts seeing {@code target} (sent after the target's own spawn packets). */
    public void onStartTracking(ServerPlayerEntity observer, Entity target) {
        if (!(target instanceof ServerPlayerEntity)) {
            return;
        }
        Label label = labels.get(target.getUniqueID());
        if (label != null && label.shown && label.player == target) {
            for (IPacket<?> p : spawn(label, (ServerPlayerEntity) target)) {
                observer.connection.sendPacket(p);
            }
        }
    }

    /** A client stops seeing {@code target}: its label must go with it on that client. */
    public void onStopTracking(ServerPlayerEntity observer, Entity target) {
        if (!(target instanceof ServerPlayerEntity)) {
            return;
        }
        Label label = labels.get(target.getUniqueID());
        if (label != null && label.player == target) {
            observer.connection.sendPacket(new SDestroyEntitiesPacket(label.ids()));
        }
    }

    void onLogout(ServerPlayerEntity player) {
        Label label = labels.remove(player.getUniqueID());
        if (label != null) {
            hide(label);
        }
    }

    /** Death, respawn or dimension change: re-checked on the next tick. */
    public void onPlayerChanged(ServerPlayerEntity player) {
        Label label = labels.get(player.getUniqueID());
        if (label != null && (!player.isAlive() || label.player != player)) {
            hide(label);
        }
    }

    void stop() {
        for (Label label : labels.values()) {
            hide(label);
        }
        labels.clear();
    }

    /** Number of labels currently shown (for /tabrank info). */
    public int shownLabels() {
        int n = 0;
        for (Label label : labels.values()) {
            if (label.shown) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ leftovers of b1-b3

    /**
     * True for a real label entity created by 1.0.0-b1 to b3. This version creates none, so every
     * such entity is a leftover. Only marker armour stands and area-effect clouds that carry this
     * mod's tag match: other mods' or players' armour stands are never touched.
     */
    public static boolean isLeftover(Entity entity) {
        return (entity instanceof ArmorStandEntity || entity instanceof AreaEffectCloudEntity)
                && entity.getTags().contains(ENTITY_TAG);
    }

    /** Removes leftover label entities from all loaded worlds. Returns how many were removed. */
    public int removeLeftovers() {
        int removed = 0;
        for (ServerWorld world : server.getWorlds()) {
            List<Entity> found = new ArrayList<>();
            world.getEntities().forEach(e -> {
                if (isLeftover(e)) {
                    found.add(e);
                }
            });
            for (Entity e : found) {
                e.stopRiding();
                e.remove();
                removed++;
            }
        }
        if (removed > 0) {
            GrandTab.LOG.info("Grand Tab: removed {} leftover label entities from older versions", removed);
        }
        return removed;
    }
}
