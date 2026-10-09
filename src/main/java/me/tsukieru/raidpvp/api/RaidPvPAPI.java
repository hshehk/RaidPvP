package me.tsukieru.raidpvp.api;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Public API of RaidPvP for other plugins (duels, party systems, ...).
 *
 * <p>Get it either through Bukkit's services manager:
 * <pre>{@code
 * RegisteredServiceProvider<RaidPvPAPI> provider =
 *         Bukkit.getServicesManager().getRegistration(RaidPvPAPI.class);
 * RaidPvPAPI api = provider == null ? null : provider.getProvider();
 * }</pre>
 * or with {@link #get()} (returns {@code null} while RaidPvP is not enabled).
 *
 * <p>Add {@code softdepend: [RaidPvP]} (or {@code depend}) to your plugin.yml so RaidPvP loads first.
 * All methods are safe to call from any thread unless noted otherwise.
 */
public interface RaidPvPAPI {

    /** True while the player has newbie protection (such players must not be invited to duels). */
    boolean isNewbieProtected(UUID playerId);

    default boolean isNewbieProtected(Player player) {
        return player != null && isNewbieProtected(player.getUniqueId());
    }

    /** Remaining newbie protection in whole seconds, 0 if the player has none. */
    long getNewbieRemainingSeconds(UUID playerId);

    /** True while the player is combat tagged. */
    boolean isInCombat(UUID playerId);

    /** Remaining combat time in whole seconds, 0 if the player is not in combat. */
    long getCombatRemainingSeconds(UUID playerId);

    /**
     * Whether the player currently has PvP switched on (/pvp). Reads player data, so call it from the
     * player's own thread on Folia.
     */
    boolean isPvpEnabled(Player player);

    /** The API instance, or {@code null} if RaidPvP is not enabled. */
    static RaidPvPAPI get() {
        return RaidPvPAPIImpl.instance();
    }
}
