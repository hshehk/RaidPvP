package me.tsukieru.raidpvp.listener;

import me.tsukieru.raidpvp.RaidPvPPlugin;
import me.tsukieru.raidpvp.combat.CombatManager;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.npc.CombatNpcService;
import me.tsukieru.raidpvp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerListener implements Listener {
    private final RaidPvPPlugin plugin;
    private final CombatManager combat;
    private final CombatNpcService npcService;
    private final PluginConfig config;
    /** Players kicked for a reason that must not count as combat logging; their quit event is not punished either. */
    private final Set<UUID> exemptQuits = ConcurrentHashMap.newKeySet();

    public PlayerListener(RaidPvPPlugin plugin, CombatManager combat, CombatNpcService npcService, PluginConfig config) {
        this.plugin = plugin;
        this.combat = combat;
        this.npcService = npcService;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (config.blockLoginWhileNpc() && npcService.hasActiveNpc(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Text.color(config.message("npc-login-blocked")));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        exemptQuits.remove(player.getUniqueId());
        npcService.handleJoin(player);       // returns items held for an expired / removed combat-logout NPC
        combat.applyPendingRestore(player);  // flight / game mode that could not be restored on death or logout
        if (!player.hasPlayedBefore()) combat.startNewbie(player);
        else combat.restoreNewbie(player);
        if (combat.isInCombat(player)) combat.endCombat(player.getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (combat.isNewbie(player.getUniqueId()) && config.newbieBlockPickup() && !player.hasPermission("raidpvp.bypass.newbie")) {
            event.setCancelled(true);
        }
    }

    /**
     * A combat-logout NPC is a player entity, so Citizens may report its death as a PlayerDeathEvent. That event
     * has its own handler list and never reaches the EntityDeathEvent handler below, so it is handled here, early
     * enough that the drops can still be changed.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcPlayerDeath(PlayerDeathEvent event) {
        Player dying = event.getEntity();
        if (npcService.isCombatNpc(dying)) {
            npcService.handleNpcDeath(event);
        } else if (npcService.consumeKeepOnDeath(dying.getUniqueId())) {
            // Died on login because the combat-logout NPC was killed: keep whatever the NPC did not drop.
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (victim.hasMetadata("NPC")) return; // Citizens NPC (combat-logout NPC), not a real player

        combat.clearCombatOnDeath(victim);
        // A combat logout is not a kill and must never feed the anti kill-abuse counter.
        if (combat.isLogoutDeath(victim.getUniqueId())) return;

        Player killer = victim.getKiller();
        if (killer != null && killer != victim && !killer.hasMetadata("NPC")) {
            combat.recordKill(killer, victim.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNpcDeath(EntityDeathEvent event) {
        npcService.handleNpcDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        combat.startRespawnProtection(event.getPlayer());
        combat.applyPendingRestore(event.getPlayer());
    }

    /**
     * Runs at MONITOR so the kick is final (not cancelled by another plugin). Kicks by admins, restarts, bans,
     * plugins... are not the player's fault and are not punished (see combat-log.kick-exempt-causes).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player)) return;

        boolean exempt = !config.punishOnKick()
                || plugin.getServer().isStopping()
                || config.kickExemptCauses().contains(event.getCause().name());
        if (exempt) {
            exemptQuits.add(player.getUniqueId());
            return;
        }
        combat.punishCombatLogout(player);
        announceCombatLogout(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        combat.handleQuit(player);

        if (!combat.isInCombat(player)) {
            exemptQuits.remove(player.getUniqueId());
            return;
        }
        if (exemptQuits.remove(player.getUniqueId()) || plugin.getServer().isStopping()) {
            combat.releaseCombat(player);
            return;
        }
        combat.punishCombatLogout(player);
        announceCombatLogout(player);
    }

    private void announceCombatLogout(Player player) {
        if (!config.combatLogBroadcast()) return;
        String message = Text.replace(config.combatLogBroadcastMessage(), "%player%", player.getName());
        Bukkit.getServer().getGlobalRegionScheduler().execute(plugin, () -> Bukkit.getServer().sendMessage(Text.color(message)));
    }
}
