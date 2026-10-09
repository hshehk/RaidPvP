package me.tsukieru.raidpvp.npc;

import me.tsukieru.raidpvp.combat.CombatState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public interface CombatNpcService {
    boolean isAvailable();
    boolean hasActiveNpc(java.util.UUID playerId);
    boolean isCombatNpc(Entity entity);
    void handleCombatLogout(Player player, CombatState state);
    void handleNpcDeath(org.bukkit.event.entity.EntityDeathEvent event);

    /**
     * Called when a player joins: hands back inventory / experience that was held for a combat-logout NPC
     * which expired, was removed or whose server restarted. Must run on the player's own thread.
     */
    void handleJoin(Player player);

    /**
     * True once if this player is dying because their combat-logout NPC was killed and they keep what the NPC
     * did not drop; the death event must then keep inventory and level.
     */
    boolean consumeKeepOnDeath(java.util.UUID playerId);

    void shutdown();

    static CombatNpcService create(Plugin plugin, me.tsukieru.raidpvp.config.PluginConfig config) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("Citizens")) {
            try {
                return new CitizensCombatNpcService(plugin, config);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Citizens NPC integration failed; using KILL fallback: " + throwable.getClass().getSimpleName());
            }
        }
        return new NoopCombatNpcService(plugin);
    }
}
