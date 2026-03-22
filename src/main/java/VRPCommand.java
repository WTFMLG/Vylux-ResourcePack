// src/main/java/com/vylux/vyluxresourcepack/VRPCommand.java
package com.vylux.vyluxresourcepack;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class VRPCommand implements CommandExecutor {

    private static final String ADMIN_PERMISSION = "vylux.rp.admin";

    private final VyluxResourcePack plugin;

    public VRPCommand(VyluxResourcePack plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage(ColorUtils.translate(
                        plugin.getConfig().getString("messages.reload-no-perm", "&cNo permission.")
                ));
                return true;
            }

            try {
                plugin.reloadConfigAndApply();
                sender.sendMessage(ColorUtils.translate(
                        plugin.getConfig().getString("messages.reload", "&aConfig reloaded.")
                ));
            } catch (Exception ex) {
                sender.sendMessage(ColorUtils.translate("&cFailed to reload config: &f" + ex.getMessage()));
                plugin.getLogger().warning("Reload failed: " + ex.getMessage());
            }
            return true;
        }

        sender.sendMessage(ColorUtils.translate("&7/vrp reload &f- перезагрузить конфиг"));
        return true;
    }
}