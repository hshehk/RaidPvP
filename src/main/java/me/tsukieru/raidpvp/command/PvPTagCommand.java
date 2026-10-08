package me.tsukieru.raidpvp.command;

import me.tsukieru.raidpvp.RaidPvPPlugin;
import me.tsukieru.raidpvp.combat.CombatManager;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PvPTagCommand implements CommandExecutor {
    private final CombatManager combat;
    private final PluginConfig config;

    public PvPTagCommand(RaidPvPPlugin plugin, CombatManager combat, PluginConfig config) {
        this.combat = combat;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.color(config.message("player-only", "&c這個指令只能由玩家使用。")));
            return true;
        }
        if (!combat.isInCombat(player)) {
            combat.send(player, config.message("not-in-combat", "&a你目前不在戰鬥中。"));
            return true;
        }
        combat.send(player, Text.replace(config.message("combat-time", "&c你還在戰鬥中：&f%time% 秒。"),
                "%time%", String.valueOf(combat.remainingSeconds(player.getUniqueId()))));
        return true;
    }
}
