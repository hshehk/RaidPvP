package me.tsukieru.raidpvp.combat;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.npc.CombatNpcService;
import me.tsukieru.raidpvp.util.SchedulerUtil;
import me.tsukieru.raidpvp.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CombatManager {
    private final Plugin plugin;
    private final PluginConfig config;
    private final CombatNpcService npcService;
    private final NamespacedKey pvpEnabledKey;
    private final NamespacedKey newbieRemainingKey;
    private final Map<UUID, CombatState> combat = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> combatUiTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> newbie = new ConcurrentHashMap<>();
    /** Length of the protection a player started with; only used to scale the newbie boss bar. */
    private final Map<UUID, Long> newbieTotals = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> newbieUiTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> newbieBars = new ConcurrentHashMap<>();
    private final Map<UUID, Long> respawnProtection = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pvpToggleAt = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Deque<Long>>> killHistory = new ConcurrentHashMap<>();
    /** Combat state that must be restored once a dead / offline player is back (flight, game mode, invisibility). */
    private final Map<UUID, CombatState> pendingRestore = new ConcurrentHashMap<>();
    /** Players whose death is currently caused by a combat logout (must not count as a kill). */
    private final Set<UUID> logoutDeaths = ConcurrentHashMap.newKeySet();
    private ScheduledTask maintenanceTask;

    public CombatManager(Plugin plugin, PluginConfig config, CombatNpcService npcService) {
        this.plugin = plugin;
        this.config = config;
        this.npcService = npcService;
        this.pvpEnabledKey = new NamespacedKey(plugin, "pvp-enabled");
        this.newbieRemainingKey = new NamespacedKey(plugin, "newbie-remaining-ms");
    }

    public void startMaintenance() {
        maintenanceTask = Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(
                plugin,
                task -> {
                    long now = System.nanoTime();
                    combat.forEach((uuid, state) -> {
                        if (state.expiresAtNanos() <= now) endCombatIf(uuid, state, true);
                    });
                    respawnProtection.entrySet().removeIf(entry -> entry.getValue() <= now);
                    long cooldown = config.pvpToggleCooldownNanos();
                    pvpToggleAt.entrySet().removeIf(entry -> now - entry.getValue() >= cooldown);
                    cleanupKillHistory(now);
                },
                20L,
                20L
        );
    }

    // ------------------------------------------------------------------ queries

    public boolean isInCombat(UUID uuid) {
        CombatState state = combat.get(uuid);
        return state != null && state.expiresAtNanos() > System.nanoTime();
    }

    public boolean isInCombat(Player player) {
        return player != null && isInCombat(player.getUniqueId());
    }

    public long remainingSeconds(UUID uuid) {
        CombatState state = combat.get(uuid);
        if (state == null) return 0L;
        long remaining = state.expiresAtNanos() - System.nanoTime();
        return remaining <= 0L ? 0L : (remaining + 999_999_999L) / 1_000_000_000L;
    }

    public String enemyName(UUID uuid) {
        CombatState state = combat.get(uuid);
        return state == null || state.opponentName() == null ? "-" : state.opponentName();
    }

    // ------------------------------------------------------------------ pvp toggle

    public boolean canTogglePvp(UUID uuid) {
        Long last = pvpToggleAt.get(uuid);
        return last == null || System.nanoTime() - last >= config.pvpToggleCooldownNanos();
    }

    public long pvpToggleRemainingSeconds(UUID uuid) {
        Long last = pvpToggleAt.get(uuid);
        if (last == null) return 0L;
        long remaining = config.pvpToggleCooldownNanos() - (System.nanoTime() - last);
        return remaining <= 0L ? 0L : (remaining + 999_999_999L) / 1_000_000_000L;
    }

    public void markPvpToggle(UUID uuid) {
        pvpToggleAt.put(uuid, System.nanoTime());
    }

    public boolean getPvpEnabled(Player player) {
        // When toggling is disabled, nobody can change their status, so everybody follows the server default.
        if (!config.pvpToggleEnabled()) return config.defaultPvpEnabled();
        Byte value = player.getPersistentDataContainer().get(pvpEnabledKey, PersistentDataType.BYTE);
        return value == null ? config.defaultPvpEnabled() : value != 0;
    }

    public void setPvpEnabled(Player player, boolean enabled) {
        player.getPersistentDataContainer().set(pvpEnabledKey, PersistentDataType.BYTE, (byte) (enabled ? 1 : 0));
    }

    // ------------------------------------------------------------------ newbie protection

    public boolean isNewbie(UUID uuid) {
        Long until = newbie.get(uuid);
        if (until == null) return false;
        if (until <= System.nanoTime()) {
            newbie.remove(uuid, until);
            return false;
        }
        return true;
    }

    public long newbieRemainingSeconds(UUID uuid) {
        Long until = newbie.get(uuid);
        if (until == null) return 0L;
        long remaining = until - System.nanoTime();
        if (remaining <= 0L) {
            newbie.remove(uuid, until);
            return 0L;
        }
        return (remaining + 999_999_999L) / 1_000_000_000L;
    }

    /** Must be called on the player's own thread (join event). */
    public void startNewbie(Player player) {
        if (!config.newbieEnabled() || player.hasPermission("raidpvp.bypass.newbie")) return;
        newbie.put(player.getUniqueId(), System.nanoTime() + config.newbieDurationNanos());
        newbieTotals.put(player.getUniqueId(), config.newbieDurationNanos());
        saveNewbie(player);
        startNewbieUi(player);
        long minutes = Math.max(1L, config.newbieDurationNanos() / 60_000_000_000L);
        send(player, Text.replace(config.newbieJoinMessage(), "%time%", String.valueOf(minutes)));
    }

    /** Restores remaining protection time saved when the player last left. Join event / player thread. */
    public void restoreNewbie(Player player) {
        if (!config.newbieEnabled() || player.hasPermission("raidpvp.bypass.newbie")) return;
        Long remainingMs = player.getPersistentDataContainer().get(newbieRemainingKey, PersistentDataType.LONG);
        if (remainingMs == null || remainingMs <= 0L) return;
        long remainingNanos = remainingMs * 1_000_000L;
        newbie.put(player.getUniqueId(), System.nanoTime() + remainingNanos);
        newbieTotals.put(player.getUniqueId(), Math.max(config.newbieDurationNanos(), remainingNanos));
        startNewbieUi(player);
    }

    /** Persists the remaining protection time (offline time does not count). Must run on the player's thread. */
    public void saveNewbie(Player player) {
        Long until = newbie.get(player.getUniqueId());
        long remainingMs = until == null ? 0L : (until - System.nanoTime()) / 1_000_000L;
        if (remainingMs > 0L) player.getPersistentDataContainer().set(newbieRemainingKey, PersistentDataType.LONG, remainingMs);
        else player.getPersistentDataContainer().remove(newbieRemainingKey);
    }

    public void addNewbie(UUID uuid, long durationMinutes) {
        long nanos = Math.max(1L, durationMinutes) * 60_000_000_000L;
        newbie.put(uuid, System.nanoTime() + nanos);
        newbieTotals.put(uuid, nanos);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.getScheduler().run(plugin, task -> {
                saveNewbie(player);
                startNewbieUi(player);
            }, null);
        }
    }

    public void removeNewbie(UUID uuid) {
        newbie.remove(uuid);
        newbieTotals.remove(uuid);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            player.getScheduler().run(plugin, task -> {
                player.getPersistentDataContainer().remove(newbieRemainingKey);
                stopNewbieUi(player);
            }, null);
        }
    }

    // ------------------------------------------------------------------ newbie boss bar

    /** (Re)starts the per-tick newbie countdown. Must run on the player's thread. */
    private void startNewbieUi(Player player) {
        UUID uuid = player.getUniqueId();
        stopNewbieUi(player);
        if (!config.newbieEnabled()) return;

        long[] lastSecond = {-1L};
        long[] lastUntil = {0L};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            long now = System.nanoTime();
            if (!player.isOnline()) {
                scheduled.cancel();
                newbieUiTasks.remove(uuid, scheduled);
                removeNewbieBar(player);
                return;
            }

            Long until = newbie.get(uuid);
            if (until == null) {
                // Removed by /newbie disable, an admin, or lazily after it ran out.
                scheduled.cancel();
                newbieUiTasks.remove(uuid, scheduled);
                removeNewbieBar(player);
                if (lastUntil[0] != 0L && lastUntil[0] <= now) finishNewbie(player);
                return;
            }
            lastUntil[0] = until;

            long remaining = until - now;
            if (remaining <= 0L) {
                newbie.remove(uuid, until);
                scheduled.cancel();
                newbieUiTasks.remove(uuid, scheduled);
                removeNewbieBar(player);
                finishNewbie(player);
                return;
            }

            if (!config.newbieBossbarEnabled()) {
                removeNewbieBar(player);
                return;
            }

            long seconds = (remaining + 999_999_999L) / 1_000_000_000L;
            boolean isNew = !newbieBars.containsKey(uuid);
            BossBar bar = newbieBars.computeIfAbsent(uuid, ignored -> Bukkit.createBossBar(
                    "", config.newbieBossbarColor(), config.newbieBossbarStyle()));
            if (!bar.getPlayers().contains(player)) bar.addPlayer(player);

            // Progress is updated every tick, so the bar drains smoothly instead of stepping once a second.
            long total = Math.max(newbieTotals.getOrDefault(uuid, config.newbieDurationNanos()), remaining);
            bar.setProgress(Math.max(0.0D, Math.min(1.0D, remaining / (double) total)));

            if (isNew || seconds != lastSecond[0]) {
                lastSecond[0] = seconds;
                String text = Text.replace(config.newbieBossbarMessage(),
                        "%time%", String.valueOf(seconds),
                        "%minutes%", String.valueOf((seconds + 59L) / 60L),
                        "%mmss%", String.format("%d:%02d", seconds / 60L, seconds % 60L));
                bar.setTitle(LegacyComponentSerializer.legacySection().serialize(Text.color(text)));
            }
        }, null, 1L, 1L);
        if (task != null) newbieUiTasks.put(uuid, task);
    }

    private void finishNewbie(Player player) {
        newbieTotals.remove(player.getUniqueId());
        player.getPersistentDataContainer().remove(newbieRemainingKey);
        sendNow(player, config.newbieEndedMessage());
    }

    private void stopNewbieUi(Player player) {
        ScheduledTask task = newbieUiTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
        removeNewbieBar(player);
    }

    private void removeNewbieBar(Player player) {
        BossBar bar = newbieBars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    // ------------------------------------------------------------------ respawn protection

    public boolean isRespawnProtected(UUID uuid) {
        Long until = respawnProtection.get(uuid);
        if (until == null) return false;
        if (until <= System.nanoTime()) {
            respawnProtection.remove(uuid, until);
            return false;
        }
        return true;
    }

    public void startRespawnProtection(Player player) {
        if (!config.respawnProtectionEnabled() || config.respawnProtectionNanos() <= 0L) return;
        respawnProtection.put(player.getUniqueId(), System.nanoTime() + config.respawnProtectionNanos());
    }

    // ------------------------------------------------------------------ tagging

    public void tag(Player player, Player opponent) {
        if (!config.combatEnabled() || player.equals(opponent)) return;
        tag(player, opponent.getUniqueId(), opponent.getName(), config.combatDurationNanos());
    }

    public void forceTag(Player player, String opponentName, long durationSeconds) {
        tag(player, new UUID(0L, 0L), opponentName, Math.max(1L, durationSeconds) * 1_000_000_000L);
    }

    private void tag(Player player, UUID opponentId, String opponentName, long durationNanos) {
        player.getScheduler().run(plugin, task -> {
            if (!player.isOnline() || !config.combatEnabled()) return;

            long now = System.nanoTime();
            boolean[] created = {false};
            combat.compute(player.getUniqueId(), (id, old) -> {
                if (old == null) {
                    created[0] = true;
                    return new CombatState(
                            opponentId,
                            opponentName,
                            now + durationNanos,
                            durationNanos,
                            player.getAllowFlight(),
                            player.isFlying(),
                            player.isInvisible(),
                            player.getGameMode()
                    );
                }

                long expiry = config.refreshOnHit() ? now + durationNanos : old.expiresAtNanos();
                return new CombatState(
                        opponentId,
                        opponentName,
                        expiry,
                        old.durationNanos(),
                        old.previousAllowFlight(),
                        old.previousFlying(),
                        old.previousInvisible(),
                        old.previousGameMode()
                );
            });

            // Side effects happen outside of ConcurrentHashMap.compute().
            if (created[0]) {
                applyCombatStartNow(player);
                sendNow(player, Text.replace(config.combatEnterMessage(), "%time%", String.valueOf(Math.max(1L, durationNanos / 1_000_000_000L))));
                startCombatUiTask(player);
            }
        }, null);
    }

    private void applyCombatStartNow(Player player) {
        if (config.disableFly()) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
        if (config.disableGamemode() && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        if (config.disableInvisibility()) player.setInvisible(false);
        if (config.closeInventoryOnTag()) player.closeInventory();
    }

    private void startCombatUiTask(Player player) {
        UUID uuid = player.getUniqueId();
        long[] lastSecond = {-1L};
        String[] lastEnemy = {null};
        combatUiTasks.computeIfAbsent(uuid, ignored -> player.getScheduler().runAtFixedRate(
                plugin,
                task -> {
                    CombatState state = combat.get(uuid);
                    if (state == null || !player.isOnline()) {
                        task.cancel();
                        combatUiTasks.remove(uuid, task);
                        removeBossBar(player);
                        return;
                    }

                    long remainingNanos = state.expiresAtNanos() - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        // If the tag was refreshed meanwhile this does nothing and the task simply keeps running.
                        endCombatIf(uuid, state, true);
                        return;
                    }

                    long seconds = (remainingNanos + 999_999_999L) / 1_000_000_000L;
                    boolean textChanged = seconds != lastSecond[0] || !Objects.equals(lastEnemy[0], state.opponentName());
                    if (textChanged) {
                        lastSecond[0] = seconds;
                        lastEnemy[0] = state.opponentName();
                        if (config.actionbarEnabled()) {
                            player.sendActionBar(Text.color(Text.replace(config.actionbarMessage(),
                                    "%time%", String.valueOf(seconds),
                                    "%enemy%", state.opponentName())));
                        }
                    }
                    updateBossBar(player, state, remainingNanos, seconds, textChanged);
                },
                null,
                1L, // Paper/Folia reject an initial delay <= 0
                1L  // every tick: the boss bar drains smoothly, the text only changes once a second
        ));
    }

    private void updateBossBar(Player player, CombatState state, long remainingNanos, long seconds, boolean refreshTitle) {
        if (!config.bossbarEnabled()) {
            removeBossBar(player);
            return;
        }
        boolean isNew = !bossBars.containsKey(player.getUniqueId());
        BossBar bar = bossBars.computeIfAbsent(player.getUniqueId(), ignored -> Bukkit.createBossBar(
                "",
                config.bossbarColor(),
                config.bossbarStyle()
        ));
        if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
        double total = Math.max(1.0D, (double) state.durationNanos());
        bar.setProgress(Math.max(0.0D, Math.min(1.0D, remainingNanos / total)));
        if (isNew || refreshTitle) {
            Component title = Text.color(Text.replace(config.bossbarMessage(),
                    "%time%", String.valueOf(seconds),
                    "%enemy%", state.opponentName()));
            bar.setTitle(LegacyComponentSerializer.legacySection().serialize(title));
        }
    }

    private void removeBossBar(Player player) {
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) bar.removePlayer(player);
    }

    // ------------------------------------------------------------------ ending combat

    /** Ends the combat state unconditionally (admin untag, join cleanup...). */
    public void endCombat(UUID uuid, boolean sendMessage) {
        CombatState state = combat.get(uuid);
        if (state != null) endCombatIf(uuid, state, sendMessage);
    }

    /**
     * Ends combat only if the state is still exactly {@code expected}; a hit that refreshed the tag in the
     * meantime wins. Returns true if combat was ended by this call.
     */
    private boolean endCombatIf(UUID uuid, CombatState expected, boolean sendMessage) {
        if (!combat.remove(uuid, expected)) return false;

        ScheduledTask task = combatUiTasks.remove(uuid);
        if (task != null) task.cancel();

        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            bossBars.remove(uuid);
            return true;
        }
        player.getScheduler().run(plugin, scheduledTask -> {
            removeBossBar(player);
            restoreState(player, expected);
            if (sendMessage) sendNow(player, config.combatExitMessage());
        }, () -> bossBars.remove(uuid));
        return true;
    }

    /** Gives back flight / game mode / invisibility. Must run on the player's thread. */
    private void restoreState(Player player, CombatState state) {
        if (config.disableFly()) {
            player.setAllowFlight(state.previousAllowFlight());
            player.setFlying(state.previousAllowFlight() && state.previousFlying());
        }
        if (config.disableGamemode() && player.getGameMode() == GameMode.SURVIVAL && state.previousGameMode() != GameMode.SURVIVAL) {
            player.setGameMode(state.previousGameMode());
        }
        if (config.disableInvisibility() && state.previousInvisible()) player.setInvisible(true);
    }

    /** Called when the player died: the previous state is restored after the respawn. */
    public CombatState clearCombatOnDeath(UUID uuid) {
        CombatState state = combat.remove(uuid);
        ScheduledTask task = combatUiTasks.remove(uuid);
        if (task != null) task.cancel();
        BossBar bar = bossBars.remove(uuid);
        if (bar != null) bar.removeAll();
        if (state != null) pendingRestore.put(uuid, state);
        return state;
    }

    public CombatState clearCombatOnDeath(Player player) {
        CombatState state = clearCombatOnDeath(player.getUniqueId());
        removeBossBar(player);
        return state;
    }

    /** Applies the state saved on death / logout. Call from respawn and join handlers. */
    public void applyPendingRestore(Player player) {
        CombatState state = pendingRestore.remove(player.getUniqueId());
        if (state == null) return;
        player.getScheduler().runDelayed(plugin, task -> restoreState(player, state), null, 1L);
    }

    /** Drops the combat state without punishment (server stop, admin kick...). Player thread. */
    public void releaseCombat(Player player) {
        CombatState state = combat.remove(player.getUniqueId());
        ScheduledTask task = combatUiTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
        removeBossBar(player);
        if (state != null) restoreState(player, state);
    }

    public void punishCombatLogout(Player player) {
        UUID uuid = player.getUniqueId();
        CombatState state = combat.remove(uuid);
        ScheduledTask task = combatUiTasks.remove(uuid);
        if (task != null) task.cancel();
        removeBossBar(player);
        if (state == null) return;

        // Give back flight / game mode first so the saved player data is the player's real one.
        restoreState(player, state);
        if ("NONE".equals(config.combatLogMode())) return;

        logoutDeaths.add(uuid);
        try {
            npcService.handleCombatLogout(player, state);
        } finally {
            // setHealth(0) fires the death event synchronously, so the flag is only needed until here.
            logoutDeaths.remove(uuid);
        }
    }

    /** True while the given player's death is a combat-logout death (it must not count as a kill). */
    public boolean isLogoutDeath(UUID uuid) {
        return logoutDeaths.contains(uuid);
    }

    /** Quit bookkeeping that is independent of combat. Player thread. */
    public void handleQuit(Player player) {
        saveNewbie(player);
        stopNewbieUi(player);
        newbie.remove(player.getUniqueId());
        newbieTotals.remove(player.getUniqueId());
        respawnProtection.remove(player.getUniqueId());
    }

    // ------------------------------------------------------------------ anti kill abuse

    public void recordKill(Player killer, UUID victim) {
        if (!config.killAbuseEnabled()) return;
        long now = System.nanoTime();
        Map<UUID, Deque<Long>> perVictim = killHistory.computeIfAbsent(killer.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        Deque<Long> times = perVictim.computeIfAbsent(victim, ignored -> new ArrayDeque<>());
        synchronized (times) {
            long cutoff = now - config.killAbuseWindowNanos();
            while (!times.isEmpty() && times.peekFirst() < cutoff) times.removeFirst();
            times.addLast(now);
            if (times.size() >= config.killAbuseMaxKills()) {
                // Vanilla/Essentials kick reasons do not understand '&' codes.
                String command = LegacyComponentSerializer.legacySection().serialize(
                        Text.color(Text.replace(config.killAbuseCommand(), "%player%", killer.getName())));
                // The punished player must not additionally be punished as a "combat logger".
                endCombat(killer.getUniqueId(), false);
                Bukkit.getServer().getGlobalRegionScheduler().execute(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
                times.clear();
            }
        }
    }

    private void cleanupKillHistory(long now) {
        long cutoff = now - config.killAbuseWindowNanos();
        killHistory.entrySet().removeIf(playerEntry -> {
            playerEntry.getValue().entrySet().removeIf(victimEntry -> {
                Deque<Long> times = victimEntry.getValue();
                synchronized (times) {
                    while (!times.isEmpty() && times.peekFirst() < cutoff) times.removeFirst();
                    return times.isEmpty();
                }
            });
            return playerEntry.getValue().isEmpty();
        });
    }

    // ------------------------------------------------------------------ lifecycle / messaging

    public void shutdown() {
        if (maintenanceTask != null) maintenanceTask.cancel();
        combatUiTasks.values().forEach(ScheduledTask::cancel);
        combatUiTasks.clear();
        newbieUiTasks.values().forEach(ScheduledTask::cancel);
        newbieUiTasks.clear();
        newbieBars.values().forEach(BossBar::removeAll);
        newbieBars.clear();

        // Best effort: give everybody their flight / game mode back and keep newbie time across restarts.
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                CombatState state = combat.get(player.getUniqueId());
                if (state != null) restoreState(player, state);
                saveNewbie(player);
            } catch (Throwable ignored) {
                // Folia may refuse cross-thread access during shutdown; nothing else we can do.
            }
        }

        bossBars.values().forEach(BossBar::removeAll);
        bossBars.clear();
        combat.clear();
        newbie.clear();
        newbieTotals.clear();
        respawnProtection.clear();
        pvpToggleAt.clear();
        killHistory.clear();
        pendingRestore.clear();
        logoutDeaths.clear();
    }

    public void send(Player player, String message) {
        SchedulerUtil.run(player, plugin, () -> sendNow(player, message));
    }

    private void sendNow(Player player, String message) {
        player.sendMessage(Text.color(message));
    }
}
