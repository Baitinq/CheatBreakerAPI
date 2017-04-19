package com.cheatbreaker.api;

import com.cheatbreaker.api.message.*;
import com.cheatbreaker.api.object.CBNotification;
import com.cheatbreaker.api.util.Reflection;
import com.cheatbreaker.api.waypoint.WaypointManager;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CheatBreakerAPI extends JavaPlugin implements Listener {

    private static final String PLUGIN_MESSAGE_CHANNEL = "MC_CLIENT";

    @Getter private static CheatBreakerAPI instance;

    @Getter private WaypointManager waypointManager;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        this.waypointManager = new WaypointManager();

        Messenger messenger = getServer().getMessenger();

        messenger.registerOutgoingPluginChannel(this, PLUGIN_MESSAGE_CHANNEL);
        messenger.registerIncomingPluginChannel(this, PLUGIN_MESSAGE_CHANNEL, (channel, player, bytes) -> {

        });
    }

    public boolean isRunningCheatBreaker(Player player) {
        Map<String, Collection<Object>> multimap =  Reflection.getPropertyMap(player);

        if (multimap == null) {
            throw new IllegalStateException("Could not retrieve PropertyMap from " + player.getName() + "'s GameProfile.");
        }

        return multimap.containsKey("CB-version");
    }

    public void isCheatBreakerBanned(UUID playerUuid, Consumer<Boolean> resultListener) {
        resultListener.accept(false);
    }

    public void sendNotification(Player player, CBNotification notification) {
        sendMessage(player, new SendNotificationMessage(notification));
    }

    public void sendNotificationOrFallback(Player player, CBNotification notification, Runnable fallback) {
        if (isRunningCheatBreaker(player)) {
            sendNotification(player, notification);
        } else {
            fallback.run();
        }
    }

    public void setStaffModuleState(Player player, StaffModuleStateMessage.StaffModule module, boolean state) {
        sendMessage(player, new StaffModuleStateMessage(module, state));
    }

    public void setMinimapStatus(Player player, MinimapStatusMessage.MinimapStatus status) {
        sendMessage(player, new MinimapStatusMessage(status));
    }

    public void giveAllStaffModules(Player player) {
        for (StaffModuleStateMessage.StaffModule module : StaffModuleStateMessage.StaffModule.values()) {
            CheatBreakerAPI.getInstance().setStaffModuleState(player, module, true);
        }

        sendNotification(player, new CBNotification("Staff modules enabled", 3, TimeUnit.SECONDS));
    }

    public void disableAllStaffModules(Player player) {
        for (StaffModuleStateMessage.StaffModule module : StaffModuleStateMessage.StaffModule.values()) {
            CheatBreakerAPI.getInstance().setStaffModuleState(player, module, false);
        }

        sendNotification(player, new CBNotification("Staff modules disabled", 3, TimeUnit.SECONDS));
    }

    public void sendTeammates(Player player, AddTeammatesMessage message) {
        message.validatePlayers(player);
        sendMessage(player, message);
    }

    public void removeTeammates(Player player, RemoveTeammatesMessage message) {
        sendMessage(player, message);
    }

    public void sendMessage(Player player, CBMessage message) {
        Map<String, Object> data = new HashMap<>();
        data.put("action", message.getAction());
        data.putAll(message.toMap());

        byte[] bytes;

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new ObjectOutputStream(out).writeObject(data);

            bytes = out.toByteArray();
        } catch (IOException ex) {
            // just rethrow whatever we catch, we should never
            // run into an IOException while writing to a byte array
            throw new RuntimeException(ex);
        }

        player.sendPluginMessage(this, PLUGIN_MESSAGE_CHANNEL, bytes);
    }

}