package me.tsukieru.raidpvp.api;

import me.tsukieru.raidpvp.combat.CombatManager;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Implementation of {@link RaidPvPAPI}; other plugins should only use the interface. */
public final class RaidPvPAPIImpl implements RaidPvPAPI {
    private static volatile RaidPvPAPIImpl instance;

    private final CombatManager combat;

    public RaidPvPAPIImpl(CombatManager combat) {
        this.combat = combat;
    }

    static RaidPvPAPI instance() {
        return instance;
    }

    public static void setInstance(RaidPvPAPIImpl api) {
        instance = api;
    }

    @Override
    public boolean isNewbieProtected(UUID playerId) {
        return playerId != null && combat.isNewbie(playerId);
    }

    @Override
    public long getNewbieRemainingSeconds(UUID playerId) {
        return playerId == null ? 0L : combat.newbieRemainingSeconds(playerId);
    }

    @Override
    public boolean isInCombat(UUID playerId) {
        return playerId != null && combat.isInCombat(playerId);
    }

    @Override
    public long getCombatRemainingSeconds(UUID playerId) {
        return playerId == null ? 0L : combat.remainingSeconds(playerId);
    }

    @Override
    public boolean isPvpEnabled(Player player) {
        return player != null && combat.getPvpEnabled(player);
    }
}
