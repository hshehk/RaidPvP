package me.tsukieru.raidpvp.command;

import me.tsukieru.raidpvp.RaidPvPPlugin;
import me.tsukieru.raidpvp.combat.CombatManager;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class NewbieCommand implements CommandExecutor, TabCompleter {
    private final CombatManager combat;
    private final PluginConfig config;

    public NewbieCommand(RaidPvPPlugin plugin, CombatManager combat, PluginConfig config) {
        this.combat = combat;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color(config.message("player-only", "&c這個指令只能由玩家使用。")));
            return true;
        }
        if (args.length == 0) {
            long remaining = combat.newbieRemainingSeconds(player.getUniqueId());
            if (remaining <= 0L) {
                player.sendMessage(Text.color("&7你目前沒有新手保護。"));
            } else {
                combat.send(player, Text.replace(config.newbieTimeMessage(), "%time%", String.valueOf(remaining)));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("disable") || args[0].equalsIgnoreCase("off")) {
            combat.removeNewbie(player.getUniqueId());
            combat.send(player, config.newbieDisabledMessage());
            return true;
        }
        player.sendMessage(Text.color("&e用法：/newbie [disable]"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("disable").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
