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
            sender.sendMessage(Text.color(config.message("no-permission")));
            return true;
        }
        if (args.length == 0) {
            for (String line : config.messageList("admin-help")) sender.sendMessage(Text.color(line));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage(Text.color(config.message("reload")));
            }
            case "status" -> handleStatus(sender, args);
            case "tag" -> handleTag(sender, args);
            case "untag" -> handleUntag(sender, args);
            case "newbie" -> handleNewbie(sender, args);
            default -> sender.sendMessage(Text.color(config.message("unknown-subcommand")));
        }
        return true;
    }

    private void handleStatus(CommandSender sender, String[] args) {
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player player ? player : null);
        if (target == null) {
            sender.sendMessage(Text.color(config.message("player-offline")));
            return;
        }
        target.getScheduler().run(plugin, task -> {
            String pvp = combat.getPvpEnabled(target)
                    ? config.message("status-enabled")
                    : config.message("status-disabled");
            String combatStatus = combat.isInCombat(target)
                    ? Text.replace(config.message("admin-status-combat-yes"),
                            "%time%", String.valueOf(combat.remainingSeconds(target.getUniqueId())),
                            "%enemy%", combat.enemyName(target.getUniqueId()))
                    : config.message("admin-status-combat-no");
            long newbie = combat.newbieRemainingSeconds(target.getUniqueId());
            String newbieStatus = newbie > 0
                    ? Text.replace(config.message("admin-status-newbie-yes"), "%time%", String.valueOf(newbie))
                    : config.message("admin-status-none");
            String npcStatus = config.message(npcService.isAvailable() ? "admin-yes" : "admin-no");
            sender.sendMessage(Text.color(config.message("admin-status-separator")));
            sender.sendMessage(Text.color(Text.replace(config.message("admin-status-player"), "%player%", target.getName())));
            sender.sendMessage(Text.color(Text.replace(config.message("pvp-status"), "%status%", pvp)));
            sender.sendMessage(Text.color(Text.replace(config.message("admin-status-combat"), "%value%", combatStatus)));
            sender.sendMessage(Text.color(Text.replace(config.message("admin-status-newbie"), "%value%", newbieStatus)));
            sender.sendMessage(Text.color(Text.replace(config.message("admin-status-npc"), "%value%", npcStatus)));
            sender.sendMessage(Text.color(config.message("admin-status-separator")));
        }, null);
    }

    private void handleTag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color(config.message("admin-tag-usage")));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.color(config.message("player-offline")));
            return;
        }
        long seconds = config.combatDurationNanos() / 1_000_000_000L;
        if (args.length >= 3) {
            try {
                seconds = Long.parseLong(args[2]);
            } catch (NumberFormatException ignored) {
                sender.sendMessage(Text.color(config.message("invalid-seconds")));
                return;
            }
        }
        final long duration = Math.max(1L, seconds);
        target.getScheduler().run(plugin, task -> combat.forceTag(target, sender.getName(), duration), null);
        sender.sendMessage(Text.color(Text.replace(config.message("admin-tagged"),
                "%player%", target.getName(), "%time%", String.valueOf(duration))));
    }

    private void handleUntag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Text.color(config.message("admin-untag-usage")));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.color(config.message("player-offline")));
            return;
        }
        combat.endCombat(target.getUniqueId(), true);
        sender.sendMessage(Text.color(Text.replace(config.message("admin-untagged"), "%player%", target.getName())));
    }

    private void handleNewbie(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.color(config.message("admin-newbie-usage")));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Text.color(config.message("player-offline")));
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                long minutes = config.newbieDurationNanos() / 60_000_000_000L;
                if (args.length >= 4) {
                    try {
                        minutes = Long.parseLong(args[3]);
                    } catch (NumberFormatException ignored) {
                        sender.sendMessage(Text.color(config.message("invalid-minutes")));
                        return;
                    }
                }
                final long finalMinutes = Math.max(1L, minutes);
                combat.addNewbie(target.getUniqueId(), finalMinutes);
                sender.sendMessage(Text.color(Text.replace(config.message("admin-newbie-added"),
                        "%player%", target.getName(), "%minutes%", String.valueOf(finalMinutes))));
            }
            case "remove" -> {
                combat.removeNewbie(target.getUniqueId());
                sender.sendMessage(Text.color(Text.replace(config.message("admin-newbie-removed"), "%player%", target.getName())));
            }
            case "check" -> target.getScheduler().run(plugin, task -> {
                String value = combat.isNewbie(target.getUniqueId())
                        ? Text.replace(config.message("admin-status-newbie-yes"),
                                "%time%", String.valueOf(combat.newbieRemainingSeconds(target.getUniqueId())))
                        : config.message("admin-status-none");
                sender.sendMessage(Text.color(Text.replace(config.message("admin-newbie-check"),
                        "%player%", target.getName(), "%value%", value)));
            }, null);
            default -> sender.sendMessage(Text.color(config.message("unknown-action")));
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
