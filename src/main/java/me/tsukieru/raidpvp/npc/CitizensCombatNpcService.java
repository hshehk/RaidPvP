package me.tsukieru.raidpvp.npc;

import com.destroystokyo.paper.profile.ProfileProperty;
import me.tsukieru.raidpvp.combat.CombatState;
import me.tsukieru.raidpvp.config.PluginConfig;
import me.tsukieru.raidpvp.util.Text;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat-logout NPCs backed by Citizens.
 *
 * <p>The logged-out player's inventory and experience are written to {@code pending.yml} <em>before</em> the
 * player data is cleared. They stay there until one of these happens:
 * <ul>
 *   <li>the NPC is killed and {@code drop-inventory} / {@code drop-experience} are enabled: the matching part
 *       is dropped and removed from the file;</li>
 *   <li>otherwise (NPC survived / expired, drop disabled, server restarted or crashed): the player gets
 *       everything back on the next join.</li>
 * </ul>
 */
public final class CitizensCombatNpcService implements CombatNpcService {
    private final Plugin plugin;
    private final PluginConfig config;
    private final NPCRegistry registry;
    private final NamespacedKey ownerKey;
    private final File dataFile;
    private final Object fileLock = new Object();
    private final Map<UUID, ActiveNpc> active = new ConcurrentHashMap<>();
    private final Map<UUID, PendingEntry> pending = new ConcurrentHashMap<>();
    private final Map<String, Integer> ticketRefs = new ConcurrentHashMap<>();

    public CitizensCombatNpcService(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.registry = CitizensAPI.getTemporaryNPCRegistry();
        this.ownerKey = new NamespacedKey(plugin, "combat-npc-owner");
        this.dataFile = new File(plugin.getDataFolder(), "pending.yml");
        loadPending();
    }

    @Override public boolean isAvailable() { return true; }
    @Override public boolean hasActiveNpc(UUID playerId) { return active.containsKey(playerId); }

    @Override
    public boolean isCombatNpc(Entity entity) {
        UUID owner = ownerOf(entity);
        if (owner == null) return false;
        ActiveNpc npc = active.get(owner);
        return npc != null && npc.entityId().equals(entity.getUniqueId());
    }

    private UUID ownerOf(Entity entity) {
        if (entity == null) return null;
        String raw = entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ logout

    @Override
    public void handleCombatLogout(Player player, CombatState state) {
        if (!"NPC".equals(config.combatLogMode())) {
            if (!player.isDead()) player.setHealth(0.0D);
            return;
        }
        if (player.isDead()) return;

        UUID owner = player.getUniqueId();
        // Leftovers of an earlier NPC must never be overwritten.
        restorePending(player);

        ItemStack[] contents = player.getInventory().getContents();
        PendingEntry entry = new PendingEntry();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) entry.items.put(slot, item.clone());
        }
        entry.exp = Math.max(0, player.getTotalExperience());

        NPC npc = null;
        ActiveNpc created = null;
        try {
            String name = Text.replace(config.npcName(), "%player%", player.getName());
            if (name.length() > 16) name = name.substring(0, 16);
            npc = registry.createNPC(EntityType.PLAYER, name);
            npc.setProtected(false);
            npc.data().set(NPC.Metadata.DROPS_ITEMS, false);
            applySkin(npc, player);

            Location location = player.getLocation().clone();
            if (!npc.spawn(location)) throw new IllegalStateException("Citizens failed to spawn the combat NPC");
            Entity entity = npc.getEntity();
            if (!(entity instanceof Player npcPlayer)) throw new IllegalStateException("Citizens did not create a player NPC");

            entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
            npcPlayer.getInventory().setContents(contents.clone());

            // Keep the player's real health: logging out must not heal.
            double maxHealth = player.getMaxHealth();
            npcPlayer.setMaxHealth(maxHealth);
            npcPlayer.setHealth(Math.max(1.0D, Math.min(maxHealth, player.getHealth())));
            npcPlayer.setAbsorptionAmount(player.getAbsorptionAmount());
            npcPlayer.setFallDistance(0.0F);

            boolean ticket = config.npcKeepChunkLoaded() && acquireTicket(location);
            created = new ActiveNpc(npc, entity.getUniqueId(), location, ticket);
            active.put(owner, created);

            // Persist first, then wipe the player's own data, so nothing can be lost in between.
            pending.put(owner, entry);
            savePending();
            clearPlayerState(player);

            final UUID entityId = entity.getUniqueId();
            // Global scheduler on purpose: an entity scheduler task is dropped when the NPC's chunk unloads,
            // which would leave the player locked out of the server forever.
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                    task -> expire(owner, entityId), config.npcDespawnSeconds() * 20L);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not create combat NPC for " + player.getName() + ": " + throwable.getMessage());
            if (created != null) {
                active.remove(owner, created);
                releaseTicket(created);
            }
            pending.remove(owner);
            savePending();
            if (npc != null) {
                try { npc.destroy(); } catch (Throwable ignored) { }
            }
            if (!player.isDead()) player.setHealth(0.0D);
        }
    }

    private void applySkin(NPC npc, Player player) {
        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
        try {
            for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                if ("textures".equals(property.getName()) && property.getSignature() != null) {
                    skin.setSkinPersistent(player.getName(), property.getSignature(), property.getValue());
                    return;
                }
            }
        } catch (Throwable ignored) {
            // fall through to the name based lookup
        }
        skin.setSkinName(player.getName());
    }

    private void clearPlayerState(Player player) {
        player.getInventory().clear();
        player.setExp(0.0F);
        player.setLevel(0);
        player.setTotalExperience(0);
    }

    private void expire(UUID owner, UUID entityId) {
        ActiveNpc npc = active.get(owner);
        if (npc == null || !npc.entityId().equals(entityId)) return;
        if (!active.remove(owner, npc)) return;
        // The NPC survived: its owner gets the held items back on the next join (see pending.yml).
        releaseTicket(npc);
        destroyNpc(npc);
    }

    // ------------------------------------------------------------------ death

    @Override
    public void handleNpcDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        UUID owner = ownerOf(entity);
        if (owner == null) return;
        ActiveNpc npc = active.get(owner);
        if (npc == null || !npc.entityId().equals(entity.getUniqueId())) return;
        if (!active.remove(owner, npc)) return; // already handled (Entity- and PlayerDeathEvent can both fire)

        event.getDrops().clear();
        event.setDroppedExp(0);
        if (event instanceof PlayerDeathEvent playerDeath) {
            playerDeath.deathMessage(null);
            playerDeath.setKeepInventory(false);
            playerDeath.setKeepLevel(false);
        }

        PendingEntry entry = pending.get(owner);
        if (entry != null) {
            Location location = entity.getLocation();
            if (config.npcDropInventory()) {
                dropItems(entry, location);
                entry.items.clear();
            }
            if (config.npcDropExperience()) {
                event.setDroppedExp(entry.exp);
                entry.exp = 0;
            }
            // Whatever was not dropped stays reserved for the owner.
            if (entry.isEmpty()) pending.remove(owner);
            savePending();
        }

        releaseTicket(npc);
        try {
            npc.npc().destroy();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Could not remove combat NPC: " + throwable.getMessage());
        }
    }

    private void dropItems(PendingEntry entry, Location location) {
        if (location.getWorld() == null) return;
        for (ItemStack item : entry.items.values()) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                location.getWorld().dropItemNaturally(location, item.clone());
            }
        }
    }

    // ------------------------------------------------------------------ join

    @Override
    public void handleJoin(Player player) {
        // Login can only happen while an NPC exists when block-login-while-npc-exists is off.
        // Remove the NPC so the same items cannot be obtained twice.
        ActiveNpc npc = active.remove(player.getUniqueId());
        if (npc != null) {
            releaseTicket(npc);
            destroyNpc(npc);
        }
        restorePending(player);
    }

    /** Returns held items and experience to the player. Must run on the player's thread. */
    private void restorePending(Player player) {
        PendingEntry entry = pending.remove(player.getUniqueId());
        if (entry == null) return;
        savePending();

        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Integer, ItemStack> slotItem : entry.items.entrySet()) {
            ItemStack item = slotItem.getValue();
            int slot = slotItem.getKey();
            ItemStack current = slot >= 0 && slot < inventory.getSize() ? inventory.getItem(slot) : null;
            if (slot >= 0 && slot < inventory.getSize() && (current == null || current.getType().isAir())) {
                inventory.setItem(slot, item);
                continue;
            }
            for (ItemStack left : inventory.addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), left);
            }
        }
        if (entry.exp > 0) player.giveExp(entry.exp, false);
        player.sendMessage(Text.color(config.message("npc-returned", "&a你戰鬥登出時留下的物品與經驗已歸還。")));
    }

    // ------------------------------------------------------------------ NPC / chunk helpers

    private void destroyNpc(ActiveNpc npc) {
        Runnable destroy = () -> {
            try {
                npc.npc().destroy();
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Could not remove combat NPC: " + throwable.getMessage());
            }
        };
        Location location = npc.spawnLocation();
        if (location.getWorld() == null) {
            destroy.run();
            return;
        }
        plugin.getServer().getRegionScheduler().execute(plugin, location, destroy);
    }

    private String ticketKey(World world, int chunkX, int chunkZ) {
        return world.getUID() + ":" + chunkX + ":" + chunkZ;
    }

    private boolean acquireTicket(Location location) {
        World world = location.getWorld();
        if (world == null) return false;
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        try {
            ticketRefs.merge(ticketKey(world, chunkX, chunkZ), 1, Integer::sum);
            world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** Releases the ticket of the chunk the NPC was spawned in (not wherever it wandered off to). */
    private void releaseTicket(ActiveNpc npc) {
        if (!npc.ticketHeld()) return;
        World world = npc.spawnLocation().getWorld();
        if (world == null) return;
        int chunkX = npc.spawnLocation().getBlockX() >> 4;
        int chunkZ = npc.spawnLocation().getBlockZ() >> 4;
        ticketRefs.compute(ticketKey(world, chunkX, chunkZ), (key, count) -> {
            if (count == null) return null;
            if (count <= 1) {
                try {
                    world.removePluginChunkTicket(chunkX, chunkZ, plugin);
                } catch (Throwable ignored) {
                    // chunk ticket removal is best effort
                }
                return null;
            }
            return count - 1;
        });
    }

    // ------------------------------------------------------------------ persistence

    private void loadPending() {
        if (!dataFile.isFile()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection("pending");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            PendingEntry entry = new PendingEntry();
            entry.exp = Math.max(0, section.getInt("exp", 0));
            ConfigurationSection items = section.getConfigurationSection("items");
            if (items != null) {
                for (String slotKey : items.getKeys(false)) {
                    ItemStack item = items.getItemStack(slotKey);
                    if (item == null || item.getType().isAir()) continue;
                    try {
                        entry.items.put(Integer.parseInt(slotKey), item);
                    } catch (NumberFormatException ignored) {
                        // skip corrupt slot
                    }
                }
            }
            if (!entry.isEmpty()) pending.put(id, entry);
        }
        if (!pending.isEmpty()) {
            plugin.getLogger().info("Loaded " + pending.size() + " pending combat-logout inventory record(s); they are returned on the owners' next login.");
        }
    }

    private void savePending() {
        synchronized (fileLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, PendingEntry> record : new ArrayList<>(pending.entrySet())) {
                String base = "pending." + record.getKey();
                yaml.set(base + ".exp", record.getValue().exp);
                for (Map.Entry<Integer, ItemStack> item : record.getValue().items.entrySet()) {
                    yaml.set(base + ".items." + item.getKey(), item.getValue());
                }
            }
            try {
                File folder = dataFile.getParentFile();
                if (folder != null && !folder.exists() && !folder.mkdirs()) {
                    throw new IOException("Could not create " + folder);
                }
                File temp = new File(folder, dataFile.getName() + ".tmp");
                yaml.save(temp);
                Files.move(temp.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not save pending.yml: " + exception.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ shutdown

    @Override
    public void shutdown() {
        // pending.yml already holds every item; the owners get them back on their next login.
        for (ActiveNpc npc : new ArrayList<>(active.values())) {
            releaseTicket(npc);
            try {
                npc.npc().destroy();
            } catch (Throwable ignored) {
                // best effort while shutting down
            }
        }
        active.clear();
        ticketRefs.clear();
        savePending();
    }

    private record ActiveNpc(NPC npc, UUID entityId, Location spawnLocation, boolean ticketHeld) { }

    private static final class PendingEntry {
        final Map<Integer, ItemStack> items = new LinkedHashMap<>();
        int exp;

        boolean isEmpty() { return items.isEmpty() && exp <= 0; }
    }
}
