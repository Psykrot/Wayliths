package com.kotacraft.wayliths;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Wayliths extends JavaPlugin {

    private WaylithManager waylithManager;
    private ScrollManager scrollManager;
    private WaylithListener waylithListener;
    private CraftingManager craftingManager;
    private File langFile;
    private FileConfiguration langConfig;
    private BukkitTask particleTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        updateConfigVersion();
        saveDefaultLang();

        this.waylithManager = new WaylithManager(this);
        this.scrollManager = new ScrollManager(this);
        TpaManager tpaManager = new TpaManager(this);
        getServer().getPluginManager().registerEvents(tpaManager, this);

        // Initialize and register custom crafting recipes
        this.craftingManager = new CraftingManager(this);
        getServer().getPluginManager().registerEvents(craftingManager, this);
        Bukkit.getScheduler().runTaskLater(this, () -> this.craftingManager.registerRecipes(), 1L);

        // Register Command & Tab Completer
        WaylithCommand commandExecutor = new WaylithCommand(this);
        Objects.requireNonNull(getCommand("wayliths")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("wayliths")).setTabCompleter(commandExecutor);

        // Register Event Listener
        this.waylithListener = new WaylithListener(this);
        getServer().getPluginManager().registerEvents(waylithListener, this);
        if (isNexoEnabled()) {
            getServer().getPluginManager().registerEvents(new NexoWaylithListener(this), this);
        }

        startParticleTask();

        getLogger().info("Wayliths plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (particleTask != null) {
            particleTask.cancel();
        }
        // Unregister recipes cleanly on disable so they don't linger in memory if reloaded
        if (craftingManager != null) {
            craftingManager.unregisterRecipes();
        }
        getLogger().info("Wayliths plugin disabled.");
    }

    private void updateConfigVersion() {
        YamlConfiguration defaults = loadBundledYaml("config.yml");
        int latestVersion = defaults.getInt("config-version", 1);
        int currentVersion = getConfig().getInt("config-version", 1);
        if (currentVersion >= latestVersion) {
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration existing = loadYaml(configFile);
        mergeExistingValues(defaults, existing);
        defaults.set("config-version", latestVersion);
        try {
            defaults.save(configFile);
        } catch (IOException e) {
            throw new IllegalStateException("Could not update config.yml", e);
        }
        reloadConfig();
        getLogger().info("Configuration updated to version " + latestVersion + ".");
    }

    public WaylithManager getWaylithManager() {
        return waylithManager;
    }

    public ScrollManager getScrollManager() {
        return scrollManager;
    }

    public WaylithListener getWaylithListener() {
        return waylithListener;
    }

    public CraftingManager getCraftingManager() {
        return craftingManager;
    }

    public boolean isNexoEnabled() {
        return getServer().getPluginManager().isPluginEnabled("Nexo");
    }

    public boolean isItemEnabled(String item) {
        return getConfig().getBoolean("items." + item + ".enabled", true);
    }

    public void applyItemGlowing(ItemMeta meta, String item) {
        meta.setEnchantmentGlintOverride(getConfig().getBoolean(
                "items." + item + ".glowing", !item.equals("waylith-block")));
    }

    private void saveDefaultLang() {
        langFile = new File(getDataFolder(), "lang.yml");
        reloadLang();
    }

    public void reloadLang() {
        if (langFile == null) {
            langFile = new File(getDataFolder(), "lang.yml");
        }
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
            langConfig = loadBundledYaml("lang.yml");
            return;
        }

        YamlConfiguration merged = loadBundledYaml("lang.yml");
        mergeExistingValues(merged, loadYaml(langFile));
        langConfig = merged;
    }

    private YamlConfiguration loadBundledYaml(String resourceName) {
        InputStream stream = getResource(resourceName);
        if (stream == null) {
            throw new IllegalStateException("Missing bundled resource: " + resourceName);
        }
        try (InputStream input = stream) {
            return loadYaml(input);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read bundled resource: " + resourceName, e);
        }
    }

    private YamlConfiguration loadYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try {
            yaml.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Could not read YAML file: " + file.getName(), e);
        }
        return yaml;
    }

    private YamlConfiguration loadYaml(InputStream input) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            try {
                yaml.load(reader);
            } catch (org.bukkit.configuration.InvalidConfigurationException e) {
                throw new IllegalStateException("Invalid bundled YAML resource", e);
            }
        }
        return yaml;
    }

    private void mergeExistingValues(ConfigurationSection target, ConfigurationSection existing) {
        for (String key : existing.getKeys(false)) {
            ConfigurationSection existingSection = existing.getConfigurationSection(key);
            if (existingSection != null) {
                ConfigurationSection targetSection = target.getConfigurationSection(key);
                if (targetSection == null) {
                    target.set(key, null);
                    targetSection = target.createSection(key);
                }
                mergeExistingValues(targetSection, existingSection);
            } else {
                target.set(key, existing.get(key));
            }

            List<String> comments = existing.getComments(key);
            if (!comments.isEmpty()) {
                target.setComments(key, comments);
            }
            List<String> inlineComments = existing.getInlineComments(key);
            if (!inlineComments.isEmpty()) {
                target.setInlineComments(key, inlineComments);
            }
        }
    }

    public Component getLangMessage(String path, String placeholder, String value) {
        Map<String, String> placeholders = placeholder != null && value != null
                ? Map.of(placeholder, value)
                : Map.of();
        return getLangMessage(path, placeholders, true);
    }

    public Component getLangMessage(String path, Map<String, String> placeholders) {
        return getLangMessage(path, placeholders, true);
    }

    public Component getLangMessageWithoutPrefix(String path, String placeholder, String value) {
        Map<String, String> placeholders = placeholder != null && value != null
                ? Map.of(placeholder, value)
                : Map.of();
        return getLangMessageWithoutPrefix(path, placeholders);
    }

    public Component getLangMessageWithoutPrefix(String path, Map<String, String> placeholders) {
        return getLangMessage(path, placeholders, false);
    }

    private Component getLangMessage(String path, Map<String, String> placeholders, boolean includePrefix) {
        String rawPrefix = langConfig.getString("prefix", "");
        String msg = langConfig.getString(path, "<red>Missing language key: " + path);

        if (msg.contains("<prefix>")) {
            msg = msg.replace("<prefix>", includePrefix ? rawPrefix : "");
        }

        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            msg = msg.replace(placeholder.getKey(), placeholder.getValue());
        }

        return MiniMessage.miniMessage().deserialize(msg)
                .decoration(TextDecoration.ITALIC, false);
    }

    public List<Component> getLangLore(String path) {
        return getLangLore(path, Map.of());
    }

    public List<Component> getLangLore(String path, Map<String, String> placeholders) {
        List<String> rawLore = langConfig.getStringList(path);
        List<Component> components = new ArrayList<>();

        for (String line : rawLore) {
            String formattedLine = line;
            for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
                formattedLine = formattedLine.replace(placeholder.getKey(), placeholder.getValue());
            }
            components.add(MiniMessage.miniMessage().deserialize(formattedLine)
                    .decoration(TextDecoration.ITALIC, false));
        }

        return components;
    }

    public void startParticleTask() {
        if (particleTask != null) {
            particleTask.cancel();
        }

        if (!getConfig().getBoolean("particles.enabled", true)) return;

        int count = getConfig().getInt("particles.count", 5);
        String particleTypeName = getConfig().getString("particles.type", "PORTAL");
        Particle particleType;

        try {
            particleType = Particle.valueOf(particleTypeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Invalid particle type in config: " + particleTypeName + ". Defaulting to PORTAL.");
            particleType = Particle.PORTAL;
        }

        final Particle finalParticle = particleType;

        particleTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.Location loc : waylithManager.getBlockLocations().values()) {
                if (loc != null && loc.getWorld() != null) {
                    loc.getWorld().spawnParticle(finalParticle, loc.clone().add(0.5, 1.2, 0.5), count, 0.2, 0.2, 0.2, 0.02);
                }
            }
        }, 0L, 20L);
    }
}