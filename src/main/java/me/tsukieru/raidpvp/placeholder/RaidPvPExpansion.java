package me.tsukieru.raidpvp.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.tsukieru.raidpvp.RaidPvPPlugin;
import me.tsukieru.raidpvp.combat.CombatManager;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/**
 * PlaceholderAPI expansion. Countdown placeholders return a plain number (no unit), so the text around them
 * decides how it is displayed, e.g. {@code %raidpvp_combat_time%s} or {@code %raidpvp_newbie_time% 秒}.
 *
 * <ul>
 *   <li>{@code %raidpvp_in_combat%}, {@code %raidpvp_combat_time%}, {@code %raidpvp_combat_enemy%}</li>
 *   <li>{@code %raidpvp_newbie%}, {@code %raidpvp_newbie_time%} (seconds), {@code %raidpvp_newbie_minutes%},
 *       {@code %raidpvp_newbie_mmss%}</li>
 *   <li>{@code %raidpvp_pvp%}</li>
 * </ul>
 */
public final class RaidPvPExpansion extends PlaceholderExpansion {
    private final RaidPvPPlugin plugin;
    private final CombatManager combat;

    public RaidPvPExpansion(RaidPvPPlugin plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
    }

    @Override public String getIdentifier() { return "raidpvp"; }
    @Override public String getAuthor() { return "Tsukieru"; }
    @Override public String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        switch (params.toLowerCase(Locale.ROOT)) {
            case "in_combat":
                return String.valueOf(combat.isInCombat(id));
            case "combat_time":
                return String.valueOf(combat.remainingSeconds(id));
            case "combat_enemy":
                return combat.isInCombat(id) ? combat.enemyName(id) : "";
            case "newbie":
                return String.valueOf(combat.isNewbie(id));
            case "newbie_time":
                return String.valueOf(combat.newbieRemainingSeconds(id));
            case "newbie_minutes":
                return String.valueOf((combat.newbieRemainingSeconds(id) + 59L) / 60L);
            case "newbie_mmss": {
                long seconds = combat.newbieRemainingSeconds(id);
                return String.format("%d:%02d", seconds / 60L, seconds % 60L);
            }
            case "pvp": {
                Player online = player.getPlayer();
                return online == null ? "" : String.valueOf(combat.getPvpEnabled(online));
            }
            default:
                return null;
        }
    }
}
