// src/main/java/com/vylux/vyluxresourcepack/VyluxResourcePack.java
package com.vylux.vyluxresourcepack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public final class VyluxResourcePack extends JavaPlugin implements Listener {

    private static final long REQUEST_TIMEOUT_TICKS = 20L * 30L;
    private static final String ADMIN_PERMISSION = "vylux.rp.admin";

    private String packUrl = "";
    private UUID packUuid = UUID.randomUUID();
    private byte[] packHash = null;
    private Set<String> targetWorlds = Collections.emptySet();
    private boolean force = false;
    private String commandNoResource = "spawn %player%";
    private String removePackUrl = "";
    private boolean debugEnabled = false;
    private String currentPackSignature = "";

    private final ConcurrentMap<UUID, PendingRequest> pending = new ConcurrentHashMap<>();
    private final Set<UUID> installedByPlugin = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Component> messageCache = new ConcurrentHashMap<>();

    private Method modernSetResourcePackMethod;
    private Method removeResourcePackMethod;
    private Method removeResourcePacksMethod;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        initReflectionMethods();
        reloadConfigAndApply();

        PluginCommand cmd = getCommand("vrp");
        if (cmd != null) {
            cmd.setExecutor(new VRPCommand(this));
        }

        getServer().getPluginManager().registerEvents(this, this);
        debug("Enabled");
    }

    @Override
    public void onDisable() {
        debug("Disabling");

        for (PendingRequest req : pending.values()) {
            req.cancelTimeout();
        }

        Bukkit.getScheduler().cancelTasks(this);
        pending.clear();
        installedByPlugin.clear();
        messageCache.clear();

        HandlerList.unregisterAll((Listener) this);
    }

    public synchronized void reloadConfigAndApply() {
        String oldSignature = currentPackSignature;

        reloadConfig();

        this.debugEnabled = getConfig().getBoolean("debug", false);
        this.packUrl = Objects.requireNonNullElse(getConfig().getString("resource-pack-url"), "").trim();
        String uuidStr = Objects.requireNonNullElse(getConfig().getString("resource-pack-uuid"), "").trim();
        this.force = getConfig().getBoolean("force", false);
        this.commandNoResource = Objects.requireNonNullElse(getConfig().getString("command-noresource"), "spawn %player%");
        this.removePackUrl = Objects.requireNonNullElse(getConfig().getString("remove-pack-url"), "").trim();

        Object worldObj = getConfig().get("world", "world");
        Set<String> worlds = new HashSet<>();

        if (worldObj instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String w = String.valueOf(o).trim();
                if (!w.isEmpty()) {
                    worlds.add(w.toLowerCase(Locale.ROOT));
                }
            }
        } else {
            String single = String.valueOf(worldObj);
            for (String part : single.split(",")) {
                String w = part.trim();
                if (!w.isEmpty()) {
                    worlds.add(w.toLowerCase(Locale.ROOT));
                }
            }
        }

        if (worlds.isEmpty()) {
            worlds.add("world");
        }
        this.targetWorlds = Collections.unmodifiableSet(worlds);

        try {
            this.packUuid = UUID.fromString(uuidStr);
        } catch (Exception ex) {
            this.packUuid = UUID.randomUUID();
            getLogger().warning("Invalid resource-pack-uuid, generated a new one: " + this.packUuid);
        }

        String hashHex = Objects.requireNonNullElse(getConfig().getString("resource-pack-hash"), "").trim();
        if (!hashHex.isEmpty()) {
            if (hashHex.length() == 40) {
                try {
                    this.packHash = hexToBytes(hashHex);
                } catch (Exception ex) {
                    this.packHash = null;
                    getLogger().log(Level.WARNING, "Invalid resource-pack-hash, ignoring it.", ex);
                }
            } else {
                this.packHash = null;
                getLogger().warning("resource-pack-hash must contain 40 hex characters; ignoring it.");
            }
        } else {
            this.packHash = null;
        }

        this.currentPackSignature = packSignature(packUrl, packUuid, packHash, force);

        for (PendingRequest req : pending.values()) {
            req.cancelTimeout();
        }
        pending.clear();

        if (!Objects.equals(oldSignature, currentPackSignature)) {
            installedByPlugin.clear();
            debug("Pack signature changed, clearing installed cache");
        }

        messageCache.clear();
        cacheMessage("messages.prompt");
        cacheMessage("messages.accepted");
        cacheMessage("messages.declined");
        cacheMessage("messages.error");
        cacheMessage("messages.removed");
        cacheMessage("messages.reload");
        cacheMessage("messages.reload-no-perm");

        debug("Reloaded config");

        reconcileOnlinePlayers();
    }

    private void initReflectionMethods() {
        try {
            modernSetResourcePackMethod = Player.class.getMethod(
                    "setResourcePack",
                    UUID.class,
                    String.class,
                    byte[].class,
                    Component.class,
                    boolean.class
            );
        } catch (NoSuchMethodException ignored) {
            modernSetResourcePackMethod = null;
        }

        try {
            removeResourcePackMethod = Player.class.getMethod("removeResourcePack", UUID.class);
        } catch (NoSuchMethodException ignored) {
            removeResourcePackMethod = null;
        }

        try {
            removeResourcePacksMethod = Player.class.getMethod("removeResourcePacks");
        } catch (NoSuchMethodException ignored) {
            removeResourcePacksMethod = null;
        }

        debug("Reflection initialized");
    }

    private void cacheMessage(String path) {
        String raw = getConfig().getString(path, "");
        String translated = ColorUtils.translate(raw);
        Component comp = LegacyComponentSerializer.legacySection().deserialize(translated);
        messageCache.put(path, comp);
    }

    private void reconcileOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean inTarget = isInTargetWorld(p.getWorld());
            UUID id = p.getUniqueId();

            if (inTarget) {
                if (!installedByPlugin.contains(id)) {
                    sendPack(p);
                }
            } else if (installedByPlugin.contains(id)) {
                removePackForPlayer(p);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent ev) {
        Player p = ev.getPlayer();
        if (isInTargetWorld(p.getWorld()) && !installedByPlugin.contains(p.getUniqueId())) {
            sendPack(p);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent ev) {
        Player p = ev.getPlayer();
        World from = ev.getFrom();
        boolean wasInTarget = isInTargetWorld(from);
        boolean nowInTarget = isInTargetWorld(p.getWorld());
        UUID id = p.getUniqueId();

        if (!wasInTarget && nowInTarget && !installedByPlugin.contains(id)) {
            sendPack(p);
            return;
        }

        if (wasInTarget && !nowInTarget && installedByPlugin.contains(id)) {
            removePackForPlayer(p);
        }
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent ev) {
        Player p = ev.getPlayer();
        UUID id = p.getUniqueId();
        PlayerResourcePackStatusEvent.Status status = ev.getStatus();

        PendingRequest req = pending.get(id);

        switch (status) {
            case ACCEPTED:
                return;

            case SUCCESSFULLY_LOADED:
                if (req != null) {
                    pending.remove(id);
                    req.cancelTimeout();
                }
                installedByPlugin.add(id);
                p.sendMessage(renderMessageComponent("messages.accepted", p.getName()));
                if (debugEnabled) {
                    getLogger().info("[VRP-DEBUG] Pack loaded for " + p.getName());
                }
                return;

            case DECLINED:
                if (req != null) {
                    pending.remove(id);
                    req.cancelTimeout();
                }
                installedByPlugin.remove(id);
                p.sendMessage(renderMessageComponent("messages.declined", p.getName()));
                if (debugEnabled) {
                    getLogger().info("[VRP-DEBUG] Pack declined by " + p.getName());
                }
                if (!force) {
                    runNoResourceCommand(p);
                }
                return;

            case FAILED_DOWNLOAD:
                if (req != null) {
                    pending.remove(id);
                    req.cancelTimeout();
                }
                installedByPlugin.remove(id);
                p.sendMessage(renderMessageComponent("messages.declined", p.getName()));
                if (debugEnabled) {
                    getLogger().info("[VRP-DEBUG] Pack failed for " + p.getName() + ": " + status);
                }
                runNoResourceCommand(p);
                return;

            default:
                return;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent ev) {
        cleanupPlayerState(ev.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent ev) {
        cleanupPlayerState(ev.getPlayer());
    }

    private void cleanupPlayerState(Player p) {
        UUID id = p.getUniqueId();

        PendingRequest req = pending.remove(id);
        if (req != null) {
            req.cancelTimeout();
        }

        installedByPlugin.remove(id);
    }

    private void sendPack(Player player) {
        Objects.requireNonNull(player, "player");
        if (packUrl.isEmpty()) {
            return;
        }

        UUID playerId = player.getUniqueId();

        PendingRequest old = pending.remove(playerId);
        if (old != null) {
            old.cancelTimeout();
        }

        Component promptTemplate = messageCache.getOrDefault("messages.prompt", Component.empty());
        Component prompt = promptTemplate.replaceText(builder -> builder
                .match("%world%").replacement(String.join(", ", targetWorlds))
                .match("%player%").replacement(player.getName()));

        PendingRequest pr = new PendingRequest(playerId);
        pending.put(playerId, pr);

        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(this, () -> {
            PendingRequest removed = pending.remove(playerId);
            if (removed == null) {
                return;
            }

            removed.cancelTimeout();

            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline() && isInTargetWorld(p.getWorld())) {
                p.sendMessage(renderMessageComponent("messages.error", p.getName()));
                runNoResourceCommand(p);
            }
        }, REQUEST_TIMEOUT_TICKS);
        pr.setTimeout(timeout);

        try {
            if (modernSetResourcePackMethod != null) {
                modernSetResourcePackMethod.invoke(player, packUuid, packUrl, packHash, prompt, force);
            } else {
                player.setResourcePack(packUrl, packHash, prompt, force);
            }

            if (debugEnabled) {
                getLogger().info("[VRP-DEBUG] Pack sent to " + player.getName() + " (force=" + force + ")");
            }
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            handlePackSendFailure(player, playerId, pr, cause);
        } catch (Exception ex) {
            handlePackSendFailure(player, playerId, pr, ex);
        }
    }

    private void handlePackSendFailure(Player player, UUID playerId, PendingRequest pr, Throwable t) {
        pr.cancelTimeout();
        pending.remove(playerId);
        getLogger().log(Level.WARNING, "Failed to send resource pack to " + player.getName(), t);
        player.sendMessage(renderMessageComponent("messages.error", player.getName()));
        runNoResourceCommand(player);
    }

    private void removePackForPlayer(Player player) {
        UUID id = player.getUniqueId();

        try {
            if (removeResourcePackMethod != null) {
                removeResourcePackMethod.invoke(player, packUuid);
            } else if (removeResourcePacksMethod != null) {
                removeResourcePacksMethod.invoke(player);
            } else if (!removePackUrl.isEmpty()) {
                player.setResourcePack(removePackUrl, null, Component.empty(), false);
            }

            player.sendMessage(renderMessageComponent("messages.removed", player.getName()));

            if (debugEnabled) {
                getLogger().info("[VRP-DEBUG] Pack removed for " + player.getName());
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Error while removing resource pack for " + player.getName(), ex);
        } finally {
            installedByPlugin.remove(id);
        }
    }

    private boolean isInTargetWorld(World world) {
        return world != null && targetWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    private void runNoResourceCommand(Player player) {
        if (!player.isOnline()) return;

        String cmdTemplate = commandNoResource == null ? "" : commandNoResource;
        String cmd = cmdTemplate
                .replace("%player%", player.getName())
                .replace("%world%", String.join(",", targetWorlds));

        boolean executed = false;
        if (!cmd.isBlank()) {
            try {
                executed = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Error while dispatching fallback command", ex);
                executed = false;
            }
        }

        if (executed) return;

        try {
            World spawnWorld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (spawnWorld != null) {
                player.teleport(spawnWorld.getSpawnLocation());
                player.sendMessage(renderMessageComponent("messages.removed", player.getName()));
                if (debugEnabled) {
                    getLogger().info("[VRP-DEBUG] Teleport fallback for " + player.getName());
                }
                return;
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "Teleport fallback failed for " + player.getName(), ex);
        }

        try {
            Component comp = renderMessageComponent("messages.declined", player.getName());
            try {
                player.kick(comp);
                return;
            } catch (NoSuchMethodError | NoClassDefFoundError ignored) {
                // legacy fallback below
            }

            String msg = LegacyComponentSerializer.legacySection().serialize(comp);
            player.kickPlayer(msg);
        } catch (Exception tt) {
            getLogger().log(Level.WARNING, "Final fallback kick failed for " + player.getName(), tt);
        }
    }

    private Component renderMessageComponent(String path, String playerName) {
        Component template = messageCache.getOrDefault(path, Component.empty());
        return template.replaceText(b -> b
                .match("%player%").replacement(playerName)
                .match("%world%").replacement(String.join(", ", targetWorlds)));
    }

    private void debug(String message) {
        if (debugEnabled) {
            getLogger().info("[VRP-DEBUG] " + message);
        }
    }

    private static String packSignature(String url, UUID uuid, byte[] hash, boolean force) {
        return url + "|" + uuid + "|" + force + "|" + (hash == null ? "null" : Arrays.toString(hash));
    }

    private static byte[] hexToBytes(String hex) {
        hex = hex.trim();
        int len = hex.length();
        if (len % 2 != 0) throw new IllegalArgumentException("Hex string length must be even");

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) throw new IllegalArgumentException("Invalid hex digit in: " + hex);
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }

    private static final class PendingRequest {
        final UUID playerId;
        private BukkitTask timeoutTask;

        PendingRequest(UUID id) {
            this.playerId = id;
        }

        void setTimeout(BukkitTask t) {
            this.timeoutTask = t;
        }

        void cancelTimeout() {
            if (this.timeoutTask != null) {
                try {
                    if (!this.timeoutTask.isCancelled()) {
                        this.timeoutTask.cancel();
                    }
                } catch (Exception ignored) {
                }
            }
            this.timeoutTask = null;
        }
    }
}