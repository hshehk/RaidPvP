package me.tsukieru.raidpvp.listener;

import me.tsukieru.raidpvp.RaidPvPPlugin;
import me.tsukieru.raidpvp.combat.CombatManager;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.npc.CombatNpcService;
import me.tsukieru.raidpvp.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Egg;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CombatListener implements Listener {
    /** Teleports that are blocked while in combat; portals, bed exits, dismounts... are left alone. */
    private static final Set<PlayerTeleportEvent.TeleportCause> BLOCKED_TELEPORT_CAUSES = EnumSet.of(
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
            PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT,
            PlayerTeleportEvent.TeleportCause.COMMAND,
            PlayerTeleportEvent.TeleportCause.PLUGIN,
            PlayerTeleportEvent.TeleportCause.SPECTATE
    );

    private static final Set<PotionEffectType> HARMFUL_EFFECTS = Set.of(
            PotionEffectType.SLOWNESS,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.NAUSEA,
            PotionEffectType.INSTANT_DAMAGE,
            PotionEffectType.POISON,
            PotionEffectType.WITHER,
            PotionEffectType.WEAKNESS,
            PotionEffectType.BLINDNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION,
            PotionEffectType.DARKNESS,
            PotionEffectType.UNLUCK
    );

    private enum Verdict { IGNORE, DENY, ALLOW }

    private final CombatManager combat;
    private final PluginConfig config;
    private final CombatNpcService npcService;

    public CombatListener(RaidPvPPlugin plugin, CombatManager combat, PluginConfig config) {
        this.combat = combat;
        this.config = config;
        this.npcService = plugin.getCombatNpcService();
    }

    // ------------------------------------------------------------------ damage

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!config.isPvPWorld(victim.getWorld())) return;
        if (combat.isRespawnProtected(victim.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (config.newbieProtectFromEverything()
                && combat.isNewbie(victim.getUniqueId())
                && !victim.hasPermission("raidpvp.bypass.newbie")) {
            event.setCancelled(true);
        }
    }

    /**
     * Single place that decides whether {@code attacker} may hurt {@code victim}:
     * disabled worlds and bypass permissions are ignored, /pvp off and newbie protection deny.
     */
    private Verdict evaluate(Player attacker, Player victim, boolean notify) {
        if (attacker == null || victim == null || attacker.equals(victim)) return Verdict.IGNORE;
        if (!config.isPvPWorld(attacker.getWorld()) || !config.isPvPWorld(victim.getWorld())) return Verdict.IGNORE;
        if (attacker.hasPermission("raidpvp.bypass.combat") || victim.hasPermission("raidpvp.bypass.combat")) return Verdict.IGNORE;

        if (!combat.getPvpEnabled(attacker) || !combat.getPvpEnabled(victim)) return Verdict.DENY;

        boolean attackerNewbie = config.newbieProtectFromPvp() && combat.isNewbie(attacker.getUniqueId());
        boolean victimNewbie = config.newbieProtectFromPvp() && combat.isNewbie(victim.getUniqueId());
        if (attackerNewbie || victimNewbie) {
            if (notify) {
                if (attackerNewbie) combat.send(attacker, config.newbieProtectedMessage());
                if (victimNewbie) {
                    combat.send(attacker, Text.replace(
                            config.message("on-attack-newbie", "&e%player% &f目前有新手保護。"),
                            "%player%", victim.getName()));
                }
            }
            return Verdict.DENY;
        }
        return Verdict.ALLOW;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvPDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (npcService.isCombatNpc(victim) || npcService.isCombatNpc(event.getDamager())) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null && event.getDamageSource().getCausingEntity() instanceof Player causing) {
            attacker = causing; // end crystals, TNT cannons and other indirect sources
        }
        if (attacker == null) return;

        Verdict verdict = evaluate(attacker, victim, true);
        if (verdict == Verdict.DENY) {
            event.setCancelled(true);
            return;
        }
        if (verdict == Verdict.ALLOW && !isHarmlessProjectile(event.getDamager())) {
            combat.tag(attacker, victim);
            combat.tag(victim, attacker);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof Player thrower)) return;
        if (!isHarmful(potion.getEffects())) return;

        for (LivingEntity affected : new ArrayList<>(event.getAffectedEntities())) {
            if (!(affected instanceof Player victim) || npcService.isCombatNpc(victim)) continue;
            Verdict verdict = evaluate(thrower, victim, true);
            if (verdict == Verdict.DENY) {
                event.setIntensity(victim, 0.0D);
            } else if (verdict == Verdict.ALLOW) {
                combat.tag(thrower, victim);
                combat.tag(victim, thrower);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCloudApply(AreaEffectCloudApplyEvent event) {
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower)) return;

        List<PotionEffect> effects = new ArrayList<>(cloud.getCustomEffects());
        PotionType base = cloud.getBasePotionType();
        if (base != null) effects.addAll(base.getPotionEffects());
        if (!isHarmful(effects)) return;

        for (LivingEntity affected : new ArrayList<>(event.getAffectedEntities())) {
            if (!(affected instanceof Player victim) || npcService.isCombatNpc(victim)) continue;
            Verdict verdict = evaluate(thrower, victim, false); // clouds tick repeatedly: no chat spam
            if (verdict == Verdict.DENY) {
                event.getAffectedEntities().remove(victim);
            } else if (verdict == Verdict.ALLOW) {
                combat.tag(thrower, victim);
                combat.tag(victim, thrower);
            }
        }
    }

    // ------------------------------------------------------------------ commands / teleport / gamemode

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player) || player.hasPermission("raidpvp.bypass.command") || !config.blockCommandsInCombat()) return;

        String line = event.getMessage().trim();
        if (line.startsWith("/")) line = line.substring(1);
        line = line.trim();

        // "/minecraft:tp" and "/essentials:spawn" are the same commands as "/tp" and "/spawn".
        int space = line.indexOf(' ');
        String head = space < 0 ? line : line.substring(0, space);
        String tail = space < 0 ? "" : line.substring(space);
        int colon = head.indexOf(':');
        if (colon >= 0) head = head.substring(colon + 1);
        String lower = (head + tail).toLowerCase(Locale.ROOT);

        boolean listed = config.commandList().stream()
                .anyMatch(command -> lower.equals(command) || lower.startsWith(command + " "));
        boolean blocked = config.commandWhitelistMode() ? !listed : listed;
        if (blocked) {
            event.setCancelled(true);
            combat.send(player, config.message("command-blocked", "&c戰鬥中不能使用這個指令。"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player) || player.hasPermission("raidpvp.bypass.teleport")) return;
        if (!config.blockTeleport()) return;
        if (!BLOCKED_TELEPORT_CAUSES.contains(event.getCause())) return;
        event.setCancelled(true);
        combat.send(player, config.message("teleport-blocked", "&c戰鬥中禁止傳送。"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player) || player.hasPermission("raidpvp.bypass.combat")) return;
        if (config.blockSpectate() && event.getNewGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            combat.send(player, config.message("spectate-blocked", "&c戰鬥中禁止進入觀察者模式。"));
            return;
        }
        if (config.disableGamemode()
                && (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR)) {
            event.setCancelled(true);
            combat.send(player, config.message("gamemode-blocked", "&c戰鬥中禁止切換到此遊戲模式。"));
        }
    }

    // ------------------------------------------------------------------ items / interaction

    /**
     * Not {@code ignoreCancelled}: a right click into the air is "cancelled" for the block part from the very
     * start, so an ignoreCancelled handler would never see ender pearls, rockets or tridents used in mid air.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.PHYSICAL) return;
        Player player = event.getPlayer();
        if (!combat.isInCombat(player) || player.hasPermission("raidpvp.bypass.item")) return;

        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        ItemStack item = event.getItem();
        Material material = item == null ? null : item.getType();
        Block clickedBlock = event.getClickedBlock();

        if (rightClick && material != null) {
            if (material == Material.ENDER_PEARL && config.blockEnderPearl()) {
                event.setCancelled(true);
                combat.send(player, config.message("pearl-blocked", "&c戰鬥中禁止使用終界珍珠。"));
                return;
            }
            if (material == Material.CHORUS_FRUIT && config.blockChorusFruit()) {
                event.setCancelled(true);
                combat.send(player, config.message("chorus-blocked", "&c戰鬥中禁止使用歌萊果。"));
                return;
            }
            if (config.blockedItems().contains(material)) {
                event.setCancelled(true);
                combat.send(player, config.message("item-blocked", "&c戰鬥中不能使用這個物品。"));
                return;
            }
        }

        // blocked-items also covers using the block itself (ender chest, respawn anchor...).
        if (action == Action.RIGHT_CLICK_BLOCK && clickedBlock != null && config.blockedItems().contains(clickedBlock.getType())) {
            event.setCancelled(true);
            combat.send(player, config.message("item-blocked", "&c戰鬥中不能使用這個物品。"));
            return;
        }

        if (config.blockInteract() && clickedBlock != null) {
            Material clicked = clickedBlock.getType();
            boolean onlyListed = !config.blockedInteractMaterials().isEmpty();
            if (!onlyListed || config.blockedInteractMaterials().contains(clicked)) {
                event.setCancelled(true);
                combat.send(player, config.message("interact-blocked", "&c戰鬥中不能互動。"));
                return;
            }
        }

        // Cooldowns only for real item use (right click) that nobody else has already denied.
        if (rightClick && event.useItemInHand() != Event.Result.DENY) {
            applyItemCooldown(player, material, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) return;
        if (!combat.isInCombat(player) || !config.blockEnderPearl() || player.hasPermission("raidpvp.bypass.item")) return;
        event.setCancelled(true);
        combat.send(player, config.message("pearl-blocked", "&c戰鬥中禁止使用終界珍珠。"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!combat.isInCombat(player) || player.hasPermission("raidpvp.bypass.item")) return;
        Material material = event.getItem().getType();

        if (material == Material.CHORUS_FRUIT && config.blockChorusFruit()) {
            event.setCancelled(true);
            combat.send(player, config.message("chorus-blocked", "&c戰鬥中禁止使用歌萊果。"));
            return;
        }
        if (config.blockedItems().contains(material)) {
            event.setCancelled(true);
            combat.send(player, config.message("item-blocked", "&c戰鬥中不能使用這個物品。"));
            return;
        }
        applyItemCooldown(player, material, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!combat.isInCombat(player) || player.hasPermission("raidpvp.bypass.item") || !config.blockContainerOpen()) return;
        InventoryType type = event.getInventory().getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.PLAYER) return;
        event.setCancelled(true);
        combat.send(player, config.message("container-blocked", "&c戰鬥中不能開啟容器。"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (combat.isInCombat(player) && config.preventTotem() && !player.hasPermission("raidpvp.bypass.item")) {
            event.setCancelled(true);
            combat.send(player, config.message("totem-blocked", "&c戰鬥中禁止使用不死圖騰。"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (combat.isInCombat(player) && config.blockPlace() && !player.hasPermission("raidpvp.bypass.build")) {
            event.setCancelled(true);
            combat.send(player, config.message("build-blocked", "&c戰鬥中不能放置或破壞方塊。"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (combat.isInCombat(player) && config.blockBreak() && !player.hasPermission("raidpvp.bypass.build")) {
            event.setCancelled(true);
            combat.send(player, config.message("build-blocked", "&c戰鬥中不能放置或破壞方塊。"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (combat.isInCombat(player) && config.disableFly() && event.isFlying() && !player.hasPermission("raidpvp.bypass.combat")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPotion(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!combat.isInCombat(player) || !config.disableInvisibility() || player.hasPermission("raidpvp.bypass.combat")) return;
        if (event.getNewEffect() != null && event.getNewEffect().getType().equals(PotionEffectType.INVISIBILITY)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void applyItemCooldown(Player player, Material material, org.bukkit.event.Cancellable event) {
        if (material == null) return;
        Long cooldownNanos = config.itemCooldownNanos().get(material);
        if (cooldownNanos == null || cooldownNanos <= 0L) return;

        if (player.hasCooldown(material)) {
            event.setCancelled(true);
            combat.send(player, Text.replace(
                    config.message("item-cooldown", "&c這個物品冷卻中：&f%time% 秒。"),
                    "%time%", String.valueOf(Math.max(1, (player.getCooldown(material) + 19) / 20))));
            return;
        }
        int ticks = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (long) Math.ceil(cooldownNanos / 50_000_000.0D)));
        player.setCooldown(material, ticks);
    }

    private boolean isHarmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (HARMFUL_EFFECTS.contains(effect.getType())) return true;
        }
        return false;
    }

    private Player resolveAttacker(org.bukkit.entity.Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) return player;
        return null;
    }

    private boolean isHarmlessProjectile(org.bukkit.entity.Entity entity) {
        return entity instanceof Snowball || entity instanceof Egg || entity instanceof FishHook;
    }
}
