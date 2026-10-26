package net.grandtab;

import net.grandtab.command.GradientCommand;
import net.grandtab.command.TabCommand;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

/** Forge events the mod listens to. */
public final class TabEvents {

    @SubscribeEvent
    public void onServerStarted(FMLServerStartedEvent event) {
        GrandTab.store().load();
        TabService service = new TabService(event.getServer(), GrandTab.store());
        GrandTab.setService(service);
        service.start();
        GrandTab.LOG.info("Grand Tab List {} ready: {} kingdoms, {} roles, {} assigned players, logo mode {}, "
                        + "rank source {}, Server Utilities: {}",
                GrandTab.version(), service.config().kingdoms.size(), service.config().roles.size(),
                GrandTab.store().assignments().size(), service.config().header.logoMode,
                service.config().players.rankSource, ServerUtilitiesBridge.status());
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        TabService service = GrandTab.service();
        if (service != null) {
            service.stop();
        }
        GrandTab.setService(null);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        TabService service = GrandTab.service();
        if (event.phase == TickEvent.Phase.END && service != null) {
            service.tick();
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        TabCommand.register(event.getDispatcher());
        GradientCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        TabService service = GrandTab.service();
        if (service != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.onLogin((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TabService service = GrandTab.service();
        if (service != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.onLogout((ServerPlayerEntity) event.getPlayer());
        }
    }

    /** Respawning creates a new player object; make sure its tab name is set again. */
    @SubscribeEvent
    public void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        TabService service = GrandTab.service();
        if (service != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.onLogin((ServerPlayerEntity) event.getPlayer());
            if (service.nametags() != null) {
                service.nametags().onPlayerChanged((ServerPlayerEntity) event.getPlayer());
            }
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        TabService service = GrandTab.service();
        if (service != null && service.nametags() != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.nametags().onPlayerChanged((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        TabService service = GrandTab.service();
        if (service != null && service.nametags() != null && !event.isCanceled()
                && event.getEntityLiving() instanceof ServerPlayerEntity) {
            service.nametags().onPlayerChanged((ServerPlayerEntity) event.getEntityLiving());
        }
    }

    /**
     * Real label entities were only created by 1.0.0-b1 to b3; any that is loaded from a chunk now
     * is a leftover and is refused. Only armour stands / area-effect clouds with this mod's tag.
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.getWorld().isRemote() && NametagDisplay.isLeftover(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        TabService service = GrandTab.service();
        if (service != null && service.nametags() != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.nametags().onStartTracking((ServerPlayerEntity) event.getPlayer(), event.getTarget());
        }
    }

    @SubscribeEvent
    public void onStopTracking(PlayerEvent.StopTracking event) {
        TabService service = GrandTab.service();
        if (service != null && service.nametags() != null && event.getPlayer() instanceof ServerPlayerEntity) {
            service.nametags().onStopTracking((ServerPlayerEntity) event.getPlayer(), event.getTarget());
        }
    }

    /**
     * Chat gradients. LOWEST priority, so Server Utilities (normal priority) has already built
     * its chat line; only its prefix inside that line is replaced.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent event) {
        TabService service = GrandTab.service();
        if (service == null || event.isCanceled() || event.getPlayer() == null) {
            return;
        }
        ITextComponent line = service.chatLine(event.getPlayer(), event.getComponent());
        if (line != null) {
            event.setComponent(line);
        }
    }

    /**
     * Sets the tab-list name. LOWEST priority, so this mod's name is the one shown even if
     * another mod also formats tab names (otherwise prefixes could appear twice).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTabListName(PlayerEvent.TabListNameFormat event) {
        TabService service = GrandTab.service();
        if (service == null || !(event.getPlayer() instanceof ServerPlayerEntity)) {
            return;
        }
        ITextComponent name = service.displayName((ServerPlayerEntity) event.getPlayer());
        if (name != null) {
            event.setDisplayName(name);
        }
    }
}
