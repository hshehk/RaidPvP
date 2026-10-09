package me.tsukieru.raidpvp.npc;

import me.tsukieru.raidpvp.combat.CombatState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class NoopCombatNpcService implements CombatNpcService {
    private final Plugin plugin;

    public NoopCombatNpcService(Plugin plugin) { this.plugin = plugin; }
    @Override public boolean isAvailable() { return false; }
    @Override public boolean hasActiveNpc(java.util.UUID playerId) { return false; }
    @Override public boolean isCombatNpc(Entity entity) { return false; }
    @Override public void handleNpcDeath(org.bukkit.event.entity.EntityDeathEvent event) { }
    @Override public void handleJoin(Player player) { }
    @Override public boolean consumeKeepOnDeath(java.util.UUID playerId) { return false; }

    @Override
    public void handleCombatLogout(Player player, CombatState state) {
        if (!player.isDead()) player.setHealth(0.0D);
    }

    @Override public void shutdown() { }
}
