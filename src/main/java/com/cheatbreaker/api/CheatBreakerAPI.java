package com.cheatbreaker.api;

import com.cheatbreaker.api.event.PlayerRegisterCBEvent;
import com.cheatbreaker.api.event.PlayerUnregisterCBEvent;
import com.cheatbreaker.api.net.CBNetHandler;
import com.cheatbreaker.api.net.CBNetHandlerImpl;
import com.cheatbreaker.api.object.CBNotification;
import com.cheatbreaker.api.object.MinimapStatus;
import com.cheatbreaker.api.object.StaffModule;
import com.cheatbreaker.api.object.TitleType;
import com.cheatbreaker.api.voice.VoiceChannel;
import com.cheatbreaker.nethandler.CBPacket;
import com.cheatbreaker.nethandler.client.CBPacketVoiceMute;
import com.cheatbreaker.nethandler.obj.ServerRule;
import com.cheatbreaker.nethandler.server.*;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class CheatBreakerAPI extends JavaPlugin implements Listener {

    private static final String MESSAGE_CHANNEL = "CB-Client";

    @Getter private static CheatBreakerAPI instance;
    private final Set<UUID> playersRunningCheatBreaker = new HashSet<>();

    private final Set<UUID> playersNotRegistered = new HashSet<>();

    @Setter private CBNetHandler netHandlerServer = new CBNetHandlerImpl();

    private boolean voiceEnabled;

    @Getter private List<VoiceChannel> voiceChannels = new ArrayList<>();

    @Getter private final Map<UUID, VoiceChannel> playerActiveChannels = new HashMap<>();

    private final Map<UUID, List<CBPacket>> packetQueue = new HashMap<>();

    private final Map<UUID, List<UUID>> muteMap = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        Messenger messenger = getServer().getMessenger();

        messenger.registerOutgoingPluginChannel(this, MESSAGE_CHANNEL);
        messenger.registerIncomingPluginChannel(this, MESSAGE_CHANNEL, (channel, player, bytes) -> CBPacket.handle(netHandlerServer, bytes, player));

        getServer().getPluginManager().registerEvents(
                new Listener() {

                    @EventHandler
                    public void onRegister(PlayerRegisterChannelEvent event) {
                        playersNotRegistered.remove(event.getPlayer().getUniqueId());
                        if (event.getChannel().equals(MESSAGE_CHANNEL)) {
                            playersRunningCheatBreaker.add(event.getPlayer().getUniqueId());
                            muteMap.put(event.getPlayer().getUniqueId(), new ArrayList<>());

                            if (voiceEnabled) {
                                sendMessage(event.getPlayer(), new CBPacketServerRule(ServerRule.VOICE_ENABLED, true));
                            }

                            if (packetQueue.containsKey(event.getPlayer().getUniqueId())) {
                                packetQueue.get(event.getPlayer().getUniqueId()).forEach(p -> sendMessage(event.getPlayer(), p));
                            }

                            getServer().getPluginManager().callEvent(new PlayerRegisterCBEvent(event.getPlayer()));
                        }
                        packetQueue.remove(event.getPlayer().getUniqueId());
                    }

                    @EventHandler
                    public void onUnregister(PlayerUnregisterChannelEvent event) {
                        if (event.getChannel().equals(MESSAGE_CHANNEL)) {
                            playersRunningCheatBreaker.remove(event.getPlayer().getUniqueId());
                            playerActiveChannels.remove(event.getPlayer().getUniqueId());
                            muteMap.remove(event.getPlayer().getUniqueId());

                            getServer().getPluginManager().callEvent(new PlayerUnregisterCBEvent(event.getPlayer()));
                        }
                    }

                    @EventHandler
                    public void onUnregister(PlayerQuitEvent event) {
                        playersRunningCheatBreaker.remove(event.getPlayer().getUniqueId());
                        playersNotRegistered.remove(event.getPlayer().getUniqueId());
                        playerActiveChannels.remove(event.getPlayer().getUniqueId());
                        muteMap.remove(event.getPlayer().getUniqueId());

                        getPlayerChannels(event.getPlayer()).forEach(channel -> channel.removePlayer(event.getPlayer()));
                    }

                    @EventHandler(priority = EventPriority.LOWEST)
                    public void onJoin(PlayerJoinEvent event) {
                        Bukkit.getScheduler().runTaskLater(instance, () -> {
                            if (!isRunningCheatBreaker(event.getPlayer())) {
                                playersNotRegistered.add(event.getPlayer().getUniqueId());
                            }
                        }, 2L);
                    }

                }
        , this);
    }

    public boolean isRunningCheatBreaker(Player player) {
        return isRunningCheatBreaker(player.getUniqueId());
    }

    public boolean isRunningCheatBreaker(UUID playerUuid) {
        return playersRunningCheatBreaker.contains(playerUuid);
    }

    public Set<Player> getPlayersRunningCheatBreaker() {
        return ImmutableSet.copyOf(playersRunningCheatBreaker.stream().map(Bukkit::getPlayer).collect(Collectors.toSet()));
    }

    public void isCheatBreakerBanned(UUID playerUuid, Consumer<Boolean> resultListener) {
        resultListener.accept(false);
    }

    public void sendNotification(Player player, CBNotification notification) {
        sendMessage(player, new CBPacketNotification(
                notification.getMessage(),
                notification.getDurationMs(),
                notification.getLevel().name()
        ));
    }

    public void sendNotificationOrFallback(Player player, CBNotification notification, Runnable fallback) {
        if (isRunningCheatBreaker(player)) {
            sendNotification(player, notification);
        } else {
            fallback.run();
        }
    }

    public void setStaffModuleState(Player player, StaffModule module, boolean state) {
        sendMessage(player, new CBPacketStaffModState(module.name(), state));
    }

    public void setMinimapStatus(Player player, MinimapStatus status) {
        sendMessage(player, new CBPacketServerRule(ServerRule.MINIMAP_STATUS, status.name()));
    }

    public void setCompetitiveGame(Player player, boolean isCompetitive) {
        sendMessage(player, new CBPacketServerRule(ServerRule.COMPETITIVE_GAMEMODE, isCompetitive));
    }

    public void giveAllStaffModules(Player player) {
        for (StaffModule module : StaffModule.values()) {
            CheatBreakerAPI.getInstance().setStaffModuleState(player, module, true);
        }

        sendNotification(player, new CBNotification("Staff modules enabled", 3, TimeUnit.SECONDS));
    }

    public void disableAllStaffModules(Player player) {
        for (StaffModule module : StaffModule.values()) {
            CheatBreakerAPI.getInstance().setStaffModuleState(player, module, false);
        }

        sendNotification(player, new CBNotification("Staff modules disabled", 3, TimeUnit.SECONDS));
    }

    public void sendTeammates(Player player, CBPacketTeammates packet) {
        validatePlayers(player, packet);
        sendMessage(player, packet);
    }

    public void validatePlayers(Player sendingTo, CBPacketTeammates packet) {
        packet.getPlayers().entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) != null && !Bukkit.getPlayer(entry.getKey()).getWorld().equals(sendingTo.getWorld()));
    }

    public void addHologram(Player player, UUID id, Vector position, String[] lines) {
        sendMessage(player, new CBPacketAddHologram(id, position.getX(), position.getY(), position.getZ(), Arrays.asList(lines)));
    }

    public void updateHologram(Player player, UUID id, String[] lines) {
        sendMessage(player, new CBPacketUpdateHologram(id, Arrays.asList(lines)));
    }

    public void removeHologram(Player player, UUID id) {
        sendMessage(player, new CBPacketRemoveHologram(id));
    }

    public void overrideNametag(LivingEntity target, List<String> nametag, Player viewer) {
        sendMessage(viewer, new CBPacketOverrideNametags(target.getEntityId(), nametag));
    }

    public void resetNametag(LivingEntity target, Player viewer) {
        sendMessage(viewer, new CBPacketOverrideNametags(target.getEntityId(), null));
    }

    public void hideNametag(LivingEntity target, Player viewer) {
        sendMessage(viewer, new CBPacketOverrideNametags(target.getEntityId(), ImmutableList.of()));
    }

    public void sendTitle(Player player, TitleType type, String message, Duration displayTime) {
        sendTitle(player, type, message, Duration.ofMillis(500), displayTime, Duration.ofMillis(500));
    }

    public void sendTitle(Player player, TitleType type, String message, Duration fadeInTime, Duration displayTime, Duration fadeOutTime) {
         sendMessage(player, new CBPacketTitle(type.name().toLowerCase(), message, fadeInTime.toMillis(), displayTime.toMillis(), fadeOutTime.toMillis()));
    }

    public void voiceEnabled(boolean enabled) {
        voiceEnabled = enabled;
    }

    public void createVoiceChannels(VoiceChannel... voiceChannels) {
        this.voiceChannels.addAll(Arrays.asList(voiceChannels));
        for (VoiceChannel channel : voiceChannels) {
            for (Player player : channel.getPlayersInChannel()) {
                sendVoiceChannel(player, channel);
            }
        }
    }

    public void deleteVoiceChannel(VoiceChannel channel) {
        this.voiceChannels.removeIf(c -> {
            boolean remove = c == channel;
            if (remove) {
                channel.validatePlayers();
                for (Player player : channel.getPlayersInChannel()) {
                    sendMessage(player, new CBPacketDeleteVoiceChannel(channel.getUuid()));
                }
            }
            return remove;
        });
    }

    public void deleteVoiceChannel(UUID channelUUID) {
        getChannel(channelUUID).ifPresent(c -> deleteVoiceChannel(c));
    }

    public List<VoiceChannel> getPlayerChannels(Player player) {
        return this.voiceChannels.stream().filter(channel -> channel.hasPlayer(player)).collect(Collectors.toList());
    }

    public void sendVoiceChannel(Player player, VoiceChannel channel) {
        channel.validatePlayers();
        sendMessage(player, new CBPacketVoiceChannel(channel.getUuid(), channel.getName(), channel.toPlayersMap(), channel.toListeningMap()));
    }

    public void setActiveChannel(Player player, UUID uuid) {
        getChannel(uuid).ifPresent(channel -> setActiveChannel(player, channel));
    }

    public Optional<VoiceChannel> getChannel(UUID uuid) {
        return voiceChannels.stream().filter(channel -> channel.getUuid().equals(uuid)).findFirst();
    }

    public void setActiveChannel(Player player, VoiceChannel channel) {
        channel.setActive(player);
    }

    public void toggleVoiceMute(Player player, UUID other) {
        if (!muteMap.get(player.getUniqueId()).removeIf(uuid -> uuid.equals(other))) {
            muteMap.get(player.getUniqueId()).add(other);
        }
    }

    public boolean playerHasPlayerMuted(Player player, Player other) {
        return muteMap.get(other.getUniqueId()).contains(player.getUniqueId());
    }

    /*
    *  This is a boolean to indicate whether or not a CB message was sent.
    *  An example use-case is when you want to send a CheatBreaker
    *  notification if a player is running CheatBreaker, and a chat
    *  message if not.
    */
    public boolean sendMessage(Player player, CBPacket packet) {
        if (isRunningCheatBreaker(player)) {
            player.sendPluginMessage(this, MESSAGE_CHANNEL, CBPacket.getPacketData(packet));
            return true;
        } else if (!playersNotRegistered.contains(player.getUniqueId())) {
            packetQueue.putIfAbsent(player.getUniqueId(), new ArrayList<>());
            packetQueue.get(player.getUniqueId()).add(packet);
            return false;
        }
        return false;
    }

}