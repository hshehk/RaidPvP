package me.tsukieru.raidpvp.command;

import me.tsukieru.raidpvp.RaidPvPPlugin;
import me.tsukieru.raidpvp.combat.CombatManager;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.npc.CombatNpcService;
import me.tsukieru.raidpvp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class RaidPvPCommand implements TabExecutor {
    private final RaidPvPPlugin plugin;
    private final CombatManager combat;
    private final CombatNpcService npcService;
    private final PluginConfig config;

    public RaidPvPCommand(RaidPvPPlugin plugin, CombatManager combat, CombatNpcService npcService, PluginConfig config) {
        this.plugin = plugin;
        this.combat = combat;
        this.npcService = npcService;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("raidpvp.admin")) {
            sender.sendMessage(Text.color(config.message("no-permission", "&c你沒有權限。")));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Text.color("&e/RaidPvP reload &7- 重載設定"));
            sender.sendMessage(Text.color("&e/RaidPvP status [player] &7- 查看戰鬥狀態"));
            sender.sendMessage(Text.color("&e/RaidPvP tag <player> [seconds] &7- 手動標記戰鬥"));
            sender.sendMessage(Text.color("&e/RaidPvP untag <player> &7- 解除戰鬥"));
            sender.sendMessage(Text.color("&e/RaidPvP newbie <add|remove|check> <player> [minutes]"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(Text.color(config.message("reload", "&aRaidPvP 設定已重新載入。")));
            }
            case "status" -> handleStatus(sender, args);
            case "tag" -> handleTag(sender, args);
            case "untag" -> handleUntag(sender, args);
            case "newbie" -> handleNewbie(sender, args);
            default -> sender.sendMessage(Text.color("&c未知子指令。"));
        }
        return true;
    }

    private void handleStatus(CommandSender sender, String[] args) {
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player player ? player : null);
        if (target == null) {
            sender.sendMessage(Text.color("&c玩家不在線。"));
            return;
        }
        target.getScheduler().run(plugin, task -> {
            String pvp = combat.getPvpEnabled(target)
                    ? config.message("status-enabled", "&a開啟")
                    : config.message("status-disabled", "&c關閉");
            String combatStatus = combat.isInCombat(target) ? "&c是 &7(" + combat.remainingSeconds(target.getUniqueId()) + "s / " + combat.enemyName(target.getUniqueId()) + ")" : "&a否";
            long newbie = combat.newbieRemainingSeconds(target.getUniqueId());
            sender.sendMessage(Text.color("&8&m-------------------------"));
            sender.sendMessage(Text.color("&e玩家：&f" + target.getName()));
            sender.sendMessage(Text.color(Text.replace(config.message("pvp-status", "&7PvP 狀態：%status%"), "%status%", pvp)));
            sender.sendMessage(Text.color("&e戰鬥中：" + combatStatus));
            sender.sendMessage(Text.color("&e新手保護：" + (newbie > 0 ? "&a" + newbie + "s" : "&7無")));
            sender.sendMessage(Text.color("&eNPC 可用：" + (npcService.isAvailable() ? "&a是" : "&c否")));
            sender.sendMessage(Text.color("&8&m-------------------------"));
        }, null);
    }

    private void handleTag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color("&e用法：/raidpvp tag <player> [seconds]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.color("&c玩家不在線。"));
            return;
        }
        long seconds = config.combatDurationNanos() / 1_000_000_000L;
        if (args.length >= 3) {
            try {
                seconds = Long.parseLong(args[2]);
            } catch (NumberFormatException ignored) {
                sender.sendMessage(Text.color("&c秒數必須是整數。"));
                return;
            }
        }
        final long duration = Math.max(1L, seconds);
        target.getScheduler().run(plugin, task -> combat.forceTag(target, sender.getName(), duration), null);
        sender.sendMessage(Text.color("&a已標記 &f" + target.getName() + " &a進入戰鬥 &f" + duration + "s&a。"));
    }

    private void handleUntag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color("&e用法：/raidpvp untag <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.color("&c玩家不在線。"));
            return;
        }
        combat.endCombat(target.getUniqueId(), true);
        sender.sendMessage(Text.color("&a已解除 &f" + target.getName() + " &a的戰鬥狀態。"));
    }

    private void handleNewbie(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.color("&e用法：/raidpvp newbie <add|remove|check> <player> [minutes]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Text.color("&c玩家不在線。"));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                long minutes = config.newbieDurationNanos() / 60_000_000_000L;
                if (args.length >= 4) {
                    try {
                        minutes = Long.parseLong(args[3]);
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage(Text.color("&c分鐘數必須是整數。"));
                        return;
                    }
                }
                final long finalMinutes = Math.max(1L, minutes);
                combat.addNewbie(target.getUniqueId(), finalMinutes);
                sender.sendMessage(Text.color("&a已給予 &f" + target.getName() + " &a新手保護 &f" + finalMinutes + " 分鐘&a。"));
            }
            case "remove" -> {
                combat.removeNewbie(target.getUniqueId());
                sender.sendMessage(Text.color("&a已移除 &f" + target.getName() + " &a的新手保護。"));
            }
            case "check" -> target.getScheduler().run(plugin, task -> sender.sendMessage(Text.color(
                    "&e" + target.getName() + " 新手保護：" + (combat.isNewbie(target.getUniqueId()) ? "&a" + combat.newbieRemainingSeconds(target.getUniqueId()) + "s" : "&7無")
            )), null);
            default -> sender.sendMessage(Text.color("&c未知操作：add/remove/check"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("raidpvp.admin")) return List.of();
        if (args.length == 1) return complete(args[0], "reload", "status", "tag", "untag", "newbie");
        if (args.length == 2 && args[0].equalsIgnoreCase("newbie")) return complete(args[1], "add", "remove", "check");
        if (args.length == 2 && List.of("status", "tag", "untag").contains(args[0].toLowerCase(Locale.ROOT))) return onlinePlayers(args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("newbie")) return onlinePlayers(args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("tag")) return List.of("15", "30", "60");
        return List.of();
    }

    private List<String> complete(String value, String... options) {
        String prefix = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(options).filter(option -> option.startsWith(prefix)).collect(Collectors.toList());
    }

    private List<String> onlinePlayers(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) names.add(player.getName());
        }
        return names;
    }
}
