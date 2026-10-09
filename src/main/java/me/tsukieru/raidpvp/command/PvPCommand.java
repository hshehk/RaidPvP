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

public final class PvPCommand implements CommandExecutor, TabCompleter {
    private final RaidPvPPlugin plugin;
    private final CombatManager combat;
    private final PluginConfig config;

    public PvPCommand(RaidPvPPlugin plugin, CombatManager combat, PluginConfig config) {
        this.plugin = plugin;
        this.combat = combat;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color(config.message("player-only", "&c這個指令只能由玩家使用。")));
            return true;
        }
        if (!config.pvpToggleEnabled()) {
            player.sendMessage(Text.color(config.message("pvp-toggle-disabled")));
            return true;
        }
        if (combat.isInCombat(player) && !player.hasPermission("raidpvp.bypass.combat")) {
            combat.send(player, Text.replace(config.message("combat-time", "&c你還在戰鬥中：&f%time% 秒。"),
                    "%time%", String.valueOf(combat.remainingSeconds(player.getUniqueId()))));
            return true;
        }
        if (!combat.canTogglePvp(player.getUniqueId())) {
            combat.send(player, Text.replace(config.message("pvp-cooldown", "&c請等待 &f%time% &c秒後再切換 PvP。"),
                    "%time%", String.valueOf(combat.pvpToggleRemainingSeconds(player.getUniqueId()))));
            return true;
        }

        boolean enabled = args.length == 0
                ? !combat.getPvpEnabled(player)
                : args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable") || args[0].equalsIgnoreCase("true");
        if (args.length > 0 && !(enabled || args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable") || args[0].equalsIgnoreCase("false"))) {
            player.sendMessage(Text.color(config.message("pvp-usage")));
            return true;
        }

        combat.setPvpEnabled(player, enabled);
        combat.markPvpToggle(player.getUniqueId());
        combat.send(player, config.message(enabled ? "pvp-enabled" : "pvp-disabled", enabled ? "&aPvP 已開啟。" : "&ePvP 已關閉。"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("on", "off").stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
