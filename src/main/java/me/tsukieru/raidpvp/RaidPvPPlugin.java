package me.tsukieru.raidpvp;

import me.tsukieru.raidpvp.api.RaidPvPAPI;
import me.tsukieru.raidpvp.api.RaidPvPAPIImpl;
import me.tsukieru.raidpvp.combat.CombatManager;
import me.tsukieru.raidpvp.command.NewbieCommand;
import me.tsukieru.raidpvp.command.PvPCommand;
import me.tsukieru.raidpvp.command.PvPTagCommand;
import me.tsukieru.raidpvp.command.RaidPvPCommand;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.listener.CombatListener;
import me.tsukieru.raidpvp.listener.PlayerListener;
import me.tsukieru.raidpvp.npc.CombatNpcService;
import me.tsukieru.raidpvp.placeholder.RaidPvPExpansion;
import me.tsukieru.raidpvp.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class RaidPvPPlugin extends JavaPlugin {
    private PluginConfig pluginConfig;
    private CombatManager combatManager;
    private CombatNpcService combatNpcService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadPluginConfig();

        this.combatNpcService = CombatNpcService.create(this, pluginConfig);
        this.combatManager = new CombatManager(this, pluginConfig, combatNpcService);

        getServer().getPluginManager().registerEvents(new CombatListener(this, combatManager, pluginConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this, combatManager, combatNpcService, pluginConfig), this);

        PluginCommand raidPvP = requireCommand("raidpvp");
        RaidPvPCommand raidPvPCommand = new RaidPvPCommand(this, combatManager, combatNpcService, pluginConfig);
        raidPvP.setExecutor(raidPvPCommand);
        raidPvP.setTabCompleter(raidPvPCommand);

        PluginCommand pvpTag = requireCommand("pvptag");
        PvPTagCommand pvpTagCommand = new PvPTagCommand(this, combatManager, pluginConfig);
        pvpTag.setExecutor(pvpTagCommand);

        PluginCommand newbie = requireCommand("newbie");
        NewbieCommand newbieCommand = new NewbieCommand(this, combatManager, pluginConfig);
        newbie.setExecutor(newbieCommand);
        newbie.setTabCompleter(newbieCommand);

        PluginCommand pvp = requireCommand("pvp");
        PvPCommand pvpCommand = new PvPCommand(this, combatManager, pluginConfig);
        pvp.setExecutor(pvpCommand);
        pvp.setTabCompleter(pvpCommand);

        combatManager.startMaintenance();

        // Public API for other plugins (e.g. a duel plugin asking whether a player has newbie protection).
        RaidPvPAPIImpl api = new RaidPvPAPIImpl(combatManager);
        RaidPvPAPIImpl.setInstance(api);
        getServer().getServicesManager().register(RaidPvPAPI.class, api, this, ServicePriority.Normal);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new RaidPvPExpansion(this, combatManager).register();
                getLogger().info("PlaceholderAPI expansion registered (%raidpvp_...%).");
            } catch (Throwable throwable) {
                getLogger().warning("Could not register the PlaceholderAPI expansion: " + throwable.getMessage());
            }
        }
        if ("NPC".equals(pluginConfig.combatLogMode()) && !combatNpcService.isAvailable()) {
            getLogger().warning(PlainTextComponentSerializer.plainText().serialize(Text.color(
                    pluginConfig.message("npc-unavailable"))));
        }
        getLogger().info("RaidPvP enabled. Citizens integration: " + (combatNpcService.isAvailable() ? "available" : "unavailable"));
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        RaidPvPAPIImpl.setInstance(null);
        if (combatManager != null) combatManager.shutdown();
        if (combatNpcService != null) combatNpcService.shutdown();
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (pluginConfig == null) pluginConfig = new PluginConfig(this);
        else pluginConfig.reload();
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    public CombatNpcService getCombatNpcService() {
        return combatNpcService;
    }

    private PluginCommand requireCommand(String name) {
        PluginCommand command = getCommand(name);
        if (command == null) throw new IllegalStateException("Command not defined in plugin.yml: " + name);
        return command;
    }
}
