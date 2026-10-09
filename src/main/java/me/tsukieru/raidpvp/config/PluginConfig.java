package me.tsukieru.raidpvp.config;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PluginConfig {
    private final JavaPlugin plugin;
    private FileConfiguration config;
    private volatile FileConfiguration messages;

    // Values that are read on hot paths (every damage / interact event) are parsed once per reload.
    private volatile Set<String> cachedDisabledWorlds = Set.of();
    private volatile Set<String> cachedCommandList = Set.of();
    private volatile Set<Material> cachedBlockedItems = Set.of();
    private volatile Set<Material> cachedBlockedInteractMaterials = Set.of();
    private volatile Map<Material, Long> cachedItemCooldowns = Map.of();
    private volatile Set<String> cachedKickExemptCauses = Set.of();

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.config = plugin.getConfig();
        this.messages = loadMessages();
        this.cachedDisabledWorlds = Collections.unmodifiableSet(parseDisabledWorlds());
        this.cachedCommandList = Collections.unmodifiableSet(parseCommandList());
        this.cachedBlockedItems = Collections.unmodifiableSet(parseMaterials(config.getStringList("combat.restrictions.blocked-items")));
        this.cachedBlockedInteractMaterials = Collections.unmodifiableSet(parseMaterials(config.getStringList("combat.restrictions.blocked-interact-materials")));
        this.cachedItemCooldowns = Collections.unmodifiableMap(parseItemCooldowns());
        this.cachedKickExemptCauses = Collections.unmodifiableSet(parseKickExemptCauses());
    }

    // ------------------------------------------------------------------ messages.yml

    /**
     * Loads messages.yml. Missing keys fall back to the defaults bundled in the jar. The first time the file is
     * created, messages that an older version stored in config.yml are carried over.
     */
    private FileConfiguration loadMessages() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        boolean created = false;
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
            created = true;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (created) migrateLegacyMessages(yaml, file);
        try (InputStream stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read bundled messages.yml defaults: " + exception.getMessage());
        }
        return yaml;
    }

    private void migrateLegacyMessages(YamlConfiguration yaml, File file) {
        boolean changed = false;
        ConfigurationSection legacy = config.getConfigurationSection("messages");
        if (legacy != null) {
            for (String key : legacy.getKeys(false)) {
                String value = legacy.getString(key);
                if (value != null) {
                    yaml.set(key, value);
                    changed = true;
                }
            }
        }
        Map<String, String> oldPaths = Map.of(
                "combat-enter", "combat.messages.on-enter",
                "combat-exit", "combat.messages.on-exit",
                "on-attack-newbie", "combat.messages.on-attack-newbie",
                "combat-log-broadcast", "combat-log.broadcast-message",
                "newbie-join", "newbie.message.join",
                "newbie-disabled", "newbie.message.disabled",
                "newbie-time", "newbie.message.time",
                "newbie-protected", "newbie.message.protected"
        );
        for (Map.Entry<String, String> entry : oldPaths.entrySet()) {
            String value = config.getString(entry.getValue());
            if (value != null) {
                yaml.set(entry.getKey(), value);
                changed = true;
            }
        }
        if (changed) {
            try {
                yaml.save(file);
                plugin.getLogger().info("Moved existing plugin messages from config.yml to messages.yml.");
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not save migrated messages.yml: " + exception.getMessage());
            }
        }
    }

    /** A message from messages.yml; {@code fallback} is only used if the key exists nowhere. */
    public String message(String key, String fallback) {
        String value = messages.getString(key);
        return value != null ? value : fallback;
    }

    public String message(String key) {
        return message(key, "&c[missing message: " + key + "]");
    }

    public List<String> messageList(String key) {
        return new ArrayList<>(messages.getStringList(key));
    }

    /** Worlds in which RaidPvP does nothing at all (no PvP rules, no combat tag). */
    public Set<String> disabledWorlds() { return cachedDisabledWorlds; }

    private Set<String> parseDisabledWorlds() {
        Set<String> worlds = new HashSet<>();
        for (String world : config.getStringList("settings.disabled-worlds")) {
            if (world != null && !world.isBlank()) worlds.add(world.toLowerCase(Locale.ROOT));
        }
        return worlds;
    }

    public boolean isPvPWorld(World world) {
        return world != null && !disabledWorlds().contains(world.getName().toLowerCase(Locale.ROOT));
    }

    public boolean pvpToggleEnabled() { return config.getBoolean("settings.pvp-toggle.enabled", true); }
    public boolean defaultPvpEnabled() { return config.getBoolean("settings.pvp-toggle.default-enabled", true); }
    public long pvpToggleCooldownNanos() { return Math.max(0L, config.getLong("settings.pvp-toggle.change-cooldown-seconds", 10L)) * 1_000_000_000L; }

    public boolean combatEnabled() { return config.getBoolean("combat.enabled", true); }
    public boolean refreshOnHit() { return config.getBoolean("combat.refresh-on-hit", true); }
    public long combatDurationNanos() { return Math.max(1L, config.getLong("combat.duration-seconds", 15L)) * 1_000_000_000L; }
    public boolean actionbarEnabled() { return config.getBoolean("combat.actionbar.enabled", true); }
    public String actionbarMessage() { return config.getString("combat.actionbar.message", "&c⚔ 戰鬥中 &7| &f%time%s &7| &c對手: &f%enemy%"); }
    public boolean bossbarEnabled() { return config.getBoolean("combat.bossbar.enabled", true); }
    public String bossbarMessage() { return config.getString("combat.bossbar.message", "&c⚔ 戰鬥中 &f%time%s &7| &c對手: &f%enemy%"); }
    public org.bukkit.boss.BarColor bossbarColor() {
        try { return org.bukkit.boss.BarColor.valueOf(config.getString("combat.bossbar.color", "RED").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return org.bukkit.boss.BarColor.RED; }
    }
    public org.bukkit.boss.BarStyle bossbarStyle() {
        try { return org.bukkit.boss.BarStyle.valueOf(config.getString("combat.bossbar.style", "SOLID").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return org.bukkit.boss.BarStyle.SOLID; }
    }
    public String combatEnterMessage() { return message("combat-enter"); }
    public String combatExitMessage() { return message("combat-exit"); }

    public boolean blockCommandsInCombat() { return config.getBoolean("combat.restrictions.block-commands", true); }
    public boolean commandWhitelistMode() { return config.getString("combat.restrictions.command-mode", "BLACKLIST").equalsIgnoreCase("WHITELIST"); }
    public Set<String> commandList() { return cachedCommandList; }

    private Set<String> parseCommandList() {
        Set<String> commands = new HashSet<>();
        for (String command : config.getStringList("combat.restrictions.commands")) {
            if (command == null) continue;
            String normalized = command.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", "");
            if (!normalized.isBlank()) commands.add(normalized);
        }
        return commands;
    }
    public boolean blockTeleport() { return config.getBoolean("combat.restrictions.block-teleport", true); }
    public boolean blockEnderPearl() { return config.getBoolean("combat.restrictions.block-ender-pearl", true); }
    public boolean blockChorusFruit() { return config.getBoolean("combat.restrictions.block-chorus-fruit", true); }
    public boolean blockSpectate() { return config.getBoolean("combat.restrictions.block-spectate", true); }
    public boolean blockPlace() { return config.getBoolean("combat.restrictions.block-block-place", true); }
    public boolean blockBreak() { return config.getBoolean("combat.restrictions.block-block-break", true); }
    public boolean blockInteract() { return config.getBoolean("combat.restrictions.block-interact", false); }
    public boolean blockContainerOpen() { return config.getBoolean("combat.restrictions.block-container-open", false); }
    public Set<Material> blockedInteractMaterials() { return cachedBlockedInteractMaterials; }
    public boolean closeInventoryOnTag() { return config.getBoolean("combat.restrictions.close-inventory-on-tag", true); }
    public boolean disableFly() { return config.getBoolean("combat.restrictions.disable-fly", true); }
    public boolean disableGamemode() { return config.getBoolean("combat.restrictions.disable-gamemode", true); }
    public boolean disableInvisibility() { return config.getBoolean("combat.restrictions.disable-invisibility", true); }
    public boolean preventTotem() { return config.getBoolean("combat.restrictions.prevent-totem", false); }
    public Set<Material> blockedItems() { return cachedBlockedItems; }

    public Map<Material, Long> itemCooldownNanos() { return cachedItemCooldowns; }

    private Map<Material, Long> parseItemCooldowns() {
        Map<Material, Long> result = new HashMap<>();
        if (!config.getBoolean("combat.item-cooldowns.enabled", true)) return result;
        var section = config.getConfigurationSection("combat.item-cooldowns");
        if (section == null) return result;
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("enabled")) continue;
            Material material = Material.matchMaterial(key);
            if (material == null) continue;
            double seconds = section.getDouble(key, 0.0D);
            if (seconds > 0.0D) result.put(material, (long) (seconds * 1_000_000_000L));
        }
        return result;
    }

    public String combatLogMode() { return config.getString("combat-log.mode", "KILL").toUpperCase(Locale.ROOT); }
    public boolean punishOnKick() { return config.getBoolean("combat-log.punish-on-kick", true); }
    public boolean blockLoginWhileNpc() { return config.getBoolean("combat-log.block-login-while-npc-exists", false); }
    /** PlayerKickEvent.Cause names that never count as combat logging (admin kicks, restarts, bans...). */
    public Set<String> kickExemptCauses() { return cachedKickExemptCauses; }

    private Set<String> parseKickExemptCauses() {
        List<String> raw = config.isList("combat-log.kick-exempt-causes")
                ? config.getStringList("combat-log.kick-exempt-causes")
                : List.of("KICK_COMMAND", "RESTART_COMMAND", "PLUGIN", "BANNED", "IP_BANNED", "NOT_WHITELISTED", "SERVER_FULL");
        Set<String> causes = new HashSet<>();
        for (String cause : raw) {
            if (cause != null && !cause.isBlank()) causes.add(cause.trim().toUpperCase(Locale.ROOT));
        }
        return causes;
    }

    public boolean combatLogBroadcast() { return config.getBoolean("combat-log.broadcast", true); }
    public String combatLogBroadcastMessage() { return message("combat-log-broadcast"); }
    public String npcName() { return config.getString("combat-log.npc.name", "%player%"); }
    public long npcDespawnSeconds() { return Math.max(1L, config.getLong("combat-log.npc.despawn-seconds", 120L)); }
    public boolean npcDropInventory() { return config.getBoolean("combat-log.npc.drop-inventory", true); }
    public boolean npcDropExperience() { return config.getBoolean("combat-log.npc.drop-experience", true); }
    public boolean npcKeepChunkLoaded() { return config.getBoolean("combat-log.npc.keep-chunk-loaded", false); }

    public boolean newbieEnabled() { return config.getBoolean("newbie.enabled", true); }
    public long newbieDurationNanos() { return Math.max(1L, config.getLong("newbie.duration-minutes", 30L)) * 60_000_000_000L; }
    public boolean newbieProtectFromPvp() { return config.getBoolean("newbie.protect-from-pvp", true); }
    public boolean newbieProtectFromEverything() { return config.getBoolean("newbie.protect-from-everything", false); }
    public boolean newbieBlockPickup() { return config.getBoolean("newbie.block-pickup", false); }
    public String newbieJoinMessage() { return message("newbie-join"); }
    public String newbieDisabledMessage() { return message("newbie-disabled"); }
    public String newbieTimeMessage() { return message("newbie-time"); }
    public String newbieProtectedMessage() { return message("newbie-protected"); }
    public String newbieEndedMessage() { return message("newbie-ended"); }

    public boolean newbieBossbarEnabled() { return config.getBoolean("newbie.bossbar.enabled", true); }
    public String newbieBossbarMessage() { return config.getString("newbie.bossbar.message", "&a🛡 新手保護 &7| &f%mmss%"); }
    public org.bukkit.boss.BarColor newbieBossbarColor() {
        try { return org.bukkit.boss.BarColor.valueOf(config.getString("newbie.bossbar.color", "GREEN").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return org.bukkit.boss.BarColor.GREEN; }
    }
    public org.bukkit.boss.BarStyle newbieBossbarStyle() {
        try { return org.bukkit.boss.BarStyle.valueOf(config.getString("newbie.bossbar.style", "SOLID").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException exception) { return org.bukkit.boss.BarStyle.SOLID; }
    }

    public boolean respawnProtectionEnabled() { return config.getBoolean("respawn-protection.enabled", true); }
    public long respawnProtectionNanos() { return Math.max(0L, config.getLong("respawn-protection.seconds", 2L)) * 1_000_000_000L; }
    public boolean killAbuseEnabled() { return config.getBoolean("anti-kill-abuse.enabled", true); }
    public int killAbuseMaxKills() { return Math.max(1, config.getInt("anti-kill-abuse.max-kills", 5)); }
    public long killAbuseWindowNanos() { return Math.max(1L, config.getLong("anti-kill-abuse.window-seconds", 60L)) * 1_000_000_000L; }
    public String killAbuseCommand() { return config.getString("anti-kill-abuse.command", "kick %player% &c禁止短時間重複擊殺同一玩家。"); }


    private Set<Material> parseMaterials(List<String> raw) {
        Set<Material> materials = new HashSet<>();
        for (String value : new ArrayList<>(raw)) {
            if (value == null || value.isBlank()) continue;
            Material material = Material.matchMaterial(value.trim());
            if (material != null) materials.add(material);
        }
        return materials;
    }
}
