package com.kotacraft.wayliths;

import com.nexomc.nexo.api.NexoItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class WaylithManager {

    private final Wayliths plugin;
    private final File file;
    private FileConfiguration config;

    private final Map<String, Location> spawnLocations = new HashMap<>();
    private final Map<String, Location> blockLocations = new HashMap<>();
    private final Map<String, String> owners = new HashMap<>();
    private final Map<String, String> creators = new HashMap<>();

    public WaylithManager(Wayliths plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "waypoints.yml");
        loadWaypoints();
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    public ItemStack createWaylithBlockItem() {
        String currentBlockType = plugin.getConfig().getString("items.waylith-block.type", "Minecraft").trim();
        String currentWaylithId = plugin.getConfig().getString("items.waylith-block.id", "LODESTONE").trim();

        ItemStack item = null;
        if (currentBlockType.equalsIgnoreCase("Nexo") && plugin.isNexoEnabled()) {
            var builder = NexoItems.itemFromId(currentWaylithId);
            if (builder != null) {
                item = builder.build();
            }
        }

        if (item == null) {
            Material targetMaterial = Material.matchMaterial(currentWaylithId);
            item = new ItemStack(targetMaterial != null ? targetMaterial : Material.LODESTONE);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component name = plugin.getLangMessage("items.waylith_block.name", null, null);
            List<Component> lore = plugin.getLangLore("items.waylith_block.lore");

            meta.displayName(name);
            meta.lore(lore);
            plugin.applyItemGlowing(meta, "waylith-block");

            NamespacedKey key = new NamespacedKey(plugin, "is_waylith_block");
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }

        return item;
    }

    public void loadWaypoints() {
        if (!file.exists()) {
            try {
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    boolean createdDirs = parentDir.mkdirs();
                    if (!createdDirs) {
                        plugin.getLogger().warning("Failed to create parent directories for waypoints.yml");
                    }
                }
                boolean createdFile = file.createNewFile();
                if (!createdFile) {
                    debug("File waypoints.yml already existed or failed to create.");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create waypoints.yml!", e);
            }
        }

        config = YamlConfiguration.loadConfiguration(file);

        spawnLocations.clear();
        blockLocations.clear();
        owners.clear();
        creators.clear();

        ConfigurationSection section = config.getConfigurationSection("waypoints");
        if (section != null) {
            debug("Found 'waypoints' section in waypoints.yml with keys: " + section.getKeys(false));
            for (String key : section.getKeys(false)) {
                Location spawnLoc = section.getLocation(key + ".spawn");
                Location blockLoc = section.getLocation(key + ".block");
                String owner = section.getString(key + ".owner", "");
                String creator = section.getString(key + ".creator", "");

                String lowerKey = key.toLowerCase();
                if (spawnLoc != null) spawnLocations.put(lowerKey, spawnLoc);
                if (blockLoc != null) blockLocations.put(lowerKey, blockLoc);
                owners.put(lowerKey, owner);
                creators.put(lowerKey, creator);
                debug("Loaded Waylith '" + key + "' owned by: '" + owner + "'");
            }
        } else {
            debug("No 'waypoints' section found in waypoints.yml! File path: " + file.getAbsolutePath());
        }
    }

    public boolean waylithExists(String name) {
        return spawnLocations.containsKey(name.toLowerCase());
    }

    public boolean isWaylithBlock(Location blockLoc) {
        if (blockLoc == null || blockLoc.getWorld() == null) return false;

        String blockType = plugin.getConfig().getString("items.waylith-block.type", "Minecraft").trim();

        for (Location loc : blockLocations.values()) {
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(blockLoc.getWorld())) {

                int registeredY = loc.getBlockY();
                int clickedY = blockLoc.getBlockY();

                if (blockType.equalsIgnoreCase("Minecraft")) {
                    if (clickedY == registeredY && blockLoc.getBlockX() == loc.getBlockX() && blockLoc.getBlockZ() == loc.getBlockZ()) {
                        return true;
                    }
                } else {
                    // Supports custom furniture up to 5x5 horizontally (offset <= 2) and 6 blocks tall
                    if (Math.abs(blockLoc.getBlockX() - loc.getBlockX()) <= 2 &&
                            Math.abs(blockLoc.getBlockZ() - loc.getBlockZ()) <= 2 &&
                            clickedY >= registeredY && clickedY <= registeredY + 6) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String getWaylithKeyAt(Location location) {
        if (location == null || location.getWorld() == null) return null;
        String blockType = plugin.getConfig().getString("items.waylith-block.type", "Minecraft").trim();
        for (Map.Entry<String, Location> entry : blockLocations.entrySet()) {
            Location registered = entry.getValue();
            if (registered == null || registered.getWorld() == null || !registered.getWorld().equals(location.getWorld())) continue;

            int yDifference = location.getBlockY() - registered.getBlockY();
            int xDifference = Math.abs(location.getBlockX() - registered.getBlockX());
            int zDifference = Math.abs(location.getBlockZ() - registered.getBlockZ());
            boolean matches = blockType.equalsIgnoreCase("Minecraft")
                    ? yDifference == 0 && xDifference == 0 && zDifference == 0
                    : xDifference <= 2 && zDifference <= 2 && yDifference >= 0 && yDifference <= 6;
            if (matches) return entry.getKey();
        }
        return null;
    }

    public String generateUniqueDefaultName(String playerName) {
        String base = playerName + "'s Waylith";
        if (!waylithExists(base)) return base;
        int i = 2;
        while (waylithExists(playerName + "'s Waylith " + i)) {
            i++;
        }
        return playerName + "'s Waylith " + i;
    }

    public void saveWaylith(String name, Location blockLoc, Location spawnLoc, String ownerName) {
        String key = name.toLowerCase();
        spawnLocations.put(key, spawnLoc);
        blockLocations.put(key, blockLoc);
        owners.put(key, ownerName);
        creators.put(key, ownerName);

        config.set("waypoints." + key + ".display_name", name);
        config.set("waypoints." + key + ".block", blockLoc);
        config.set("waypoints." + key + ".spawn", spawnLoc);
        config.set("waypoints." + key + ".owner", ownerName);
        config.set("waypoints." + key + ".creator", ownerName);
        config.set("waypoints." + key + ".visibility", WaylithVisibility.PRIVATE.name());
        saveFile();
    }

    public void renameWaylith(String oldName, String newName) {
        String oldKey = oldName.toLowerCase();
        String newKey = newName.toLowerCase();
        WaylithVisibility visibility = getVisibility(oldName);

        if (!spawnLocations.containsKey(oldKey)) return;
        if (spawnLocations.containsKey(newKey) && !oldKey.equals(newKey)) return;

        Location spawn = spawnLocations.remove(oldKey);
        Location block = blockLocations.remove(oldKey);
        String owner = owners.remove(oldKey);
        String creator = creators.remove(oldKey);

        spawnLocations.put(newKey, spawn);
        blockLocations.put(newKey, block);
        if (owner != null) owners.put(newKey, owner);
        if (creator != null) creators.put(newKey, creator);

        config.set("waypoints." + oldKey, null);
        config.set("waypoints." + newKey + ".display_name", newName);
        config.set("waypoints." + newKey + ".block", block);
        config.set("waypoints." + newKey + ".spawn", spawn);
        config.set("waypoints." + newKey + ".owner", owner);
        config.set("waypoints." + newKey + ".creator", creator);
        config.set("waypoints." + newKey + ".visibility", visibility.name());

        saveFile();
    }

    public void removeWaylithAt(Location blockLoc) {
        String foundName = null;
        String blockType = plugin.getConfig().getString("items.waylith-block.type", "Minecraft").trim();

        for (Map.Entry<String, Location> entry : blockLocations.entrySet()) {
            Location loc = entry.getValue();
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(blockLoc.getWorld())) {

                int registeredY = loc.getBlockY();
                int brokenY = blockLoc.getBlockY();

                if (blockType.equalsIgnoreCase("Minecraft")) {
                    if (brokenY == registeredY && blockLoc.getBlockX() == loc.getBlockX() && blockLoc.getBlockZ() == loc.getBlockZ()) {
                        foundName = entry.getKey();
                        break;
                    }
                } else {
                    if (Math.abs(blockLoc.getBlockX() - loc.getBlockX()) <= 2 &&
                            Math.abs(blockLoc.getBlockZ() - loc.getBlockZ()) <= 2 &&
                            brokenY >= registeredY && brokenY <= registeredY + 6) {
                        foundName = entry.getKey();
                        break;
                    }
                }
            }
        }

        if (foundName != null) {
            spawnLocations.remove(foundName);
            blockLocations.remove(foundName);
            owners.remove(foundName);
            creators.remove(foundName);
            config.set("waypoints." + foundName, null);
            saveFile();
        }
    }

    private void saveFile() {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save waypoints.yml!", e);
        }
    }

    public Map<String, Location> getWayliths() {
        return spawnLocations;
    }

    public Map<String, Location> getBlockLocations() {
        return blockLocations;
    }

    public Map<String, String> getWaylithDisplayNames() {
        Map<String, String> displayNames = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("waypoints");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String displayName = config.getString("waypoints." + key + ".display_name", key);
                displayNames.put(key, displayName);
            }
        }
        return displayNames;
    }

    public int getPlayerWaylithCount(String playerName) {
        int count = 0;
        for (String owner : creators.values()) {
            if (owner.equalsIgnoreCase(playerName)) {
                count++;
            }
        }
        return count;
    }

    public String getOwner(String key) {
        return owners.getOrDefault(key.toLowerCase(), "");
    }

    public String getCreator(String key) {
        return creators.getOrDefault(key.toLowerCase(), "");
    }

    public WaylithVisibility getVisibility(String key) {
        return WaylithVisibility.fromConfig(config.getString("waypoints." + key.toLowerCase() + ".visibility", "PRIVATE"));
    }

    public void setVisibility(String key, WaylithVisibility visibility) {
        if (!waylithExists(key)) return;
        String normalizedKey = key.toLowerCase();
        String owner = visibility == WaylithVisibility.SERVER ? "Server" : getCreator(key);
        owners.put(normalizedKey, owner);
        config.set("waypoints." + normalizedKey + ".visibility", visibility.name());
        config.set("waypoints." + normalizedKey + ".owner", owner);
        saveFile();
    }

    public boolean canSee(String key, String playerName, boolean admin) {
        if (admin) return true;
        return switch (getVisibility(key)) {
            case PRIVATE -> getOwner(key).equalsIgnoreCase(playerName);
            case PUBLIC, SERVER -> true;
        };
    }

}