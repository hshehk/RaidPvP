package me.tsukieru.raidpvp.combat;

import org.bukkit.GameMode;

import java.util.UUID;

public record CombatState(
        UUID opponent,
        String opponentName,
        long expiresAtNanos,
        long durationNanos,
        boolean previousAllowFlight,
        boolean previousFlying,
        boolean previousInvisible,
        GameMode previousGameMode
) {}
