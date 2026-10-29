package com.kotacraft.wayliths;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CraftingManager implements Listener {

    private final Wayliths plugin;
    private final File file;
    private FileConfiguration config;
    private final List<NamespacedKey> registeredKeys = new ArrayList<>();
    private final Map<NamespacedKey, String> registeredRecipeNames = new HashMap<>();

    public CraftingManager(Wayliths plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "crafting.yml");
        loadCraftingConfig();
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    public void loadCraftingConfig() {
        if (!file.exists()) {
            plugin.saveResource("crafting.yml", false);
        }

        YamlConfiguration existing = new YamlConfiguration();
        existing.options().parseComments(true);
        try {
            existing.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Could not read crafting.yml", e);
        }

        YamlConfiguration defaults = loadBundledCraftingConfig();
        int latestVersion = defaults.getInt("crafting-version", 1);
        int currentVersion = existing.getInt("crafting-version", 1);
        if (currentVersion < latestVersion) {
            mergeExistingValues(defaults, existing);
            defaults.set("crafting-version", latestVersion);
            try {
                defaults.save(file);
            } catch (IOException e) {
                throw new IllegalStateException("Could not update crafting.yml", e);
            }
            config = defaults;
            plugin.getLogger().info("Crafting configuration updated to version " + latestVersion + ".");
        } else {
            config = existing;
        }
    }

    private YamlConfiguration loadBundledCraftingConfig() {
        InputStream stream = plugin.getResource("crafting.yml");
        if (stream == null) {
            throw new IllegalStateException("Missing bundled resource: crafting.yml");
        }

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.options().parseComments(true);
        try (InputStream input = stream;
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            defaults.load(reader);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Could not read bundled crafting.yml", e);
        }
        return defaults;
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

    public void unregisterRecipes() {
        for (NamespacedKey key : registeredKeys) {
            Bukkit.removeRecipe(key);
        }
        registeredKeys.clear();
        registeredRecipeNames.clear();
    }

    public void registerRecipes() {
        // Unregister existing recipes first to prevent duplicates on reload
        unregisterRecipes();

        if (!config.getBoolean("enabled", true)) {
            debug("Crafting recipes are disabled in crafting.yml.");
            return;
        }

        ConfigurationSection recipesSection = config.getConfigurationSection("recipes");
        if (recipesSection == null) return;

        for (String key : recipesSection.getKeys(false)) {
            ConfigurationSection section = recipesSection.getConfigurationSection(key);
            if (section == null) continue;

            String itemConfigKey = switch (key.toLowerCase()) {
                case "waylith_scroll" -> "waylith-scroll";
                case "bed_scroll" -> "bed-scroll";
                case "tpa_scroll" -> "tpa-scroll";
                default -> "waylith-block";
            };
            if (!plugin.isItemEnabled(itemConfigKey)) {
                debug("Skipping recipe '" + key + "' because its item is disabled.");
                continue;
            }

            String type = section.getString("type", "shaped").toLowerCase();
            int amount = section.getInt("result-amount", 1);

            ItemStack resultItem = createResultItem(key, amount);
            NamespacedKey recipeKey = new NamespacedKey(plugin, key.toLowerCase());

            if (type.equals("shaped")) {
                ShapedRecipe recipe = new ShapedRecipe(recipeKey, resultItem);
                List<String> shapeList = section.getStringList("shape");
                if (shapeList.size() == 3) {
                    recipe.shape(shapeList.get(0), shapeList.get(1), shapeList.get(2));

                    ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
                    if (ingredients != null) {
                        for (String ingredientKey : ingredients.getKeys(false)) {
                            char c = ingredientKey.charAt(0);
                            String matName = ingredients.getString(ingredientKey, "STONE").toUpperCase();

                            if (matName.equals("WOOL")) {
                                List<Material> wools = new ArrayList<>();
                                for (Material m : Material.values()) {
                                    if (m.name().endsWith("_WOOL")) wools.add(m);
                                }
                                recipe.setIngredient(c, new RecipeChoice.MaterialChoice(wools));
                            } else {
                                Material mat = Material.matchMaterial(matName);
                                if (mat != null) {
                                    recipe.setIngredient(c, mat);
                                }
                            }
                        }
                    }
                    Bukkit.addRecipe(recipe);
                    registeredKeys.add(recipeKey);
                    registeredRecipeNames.put(recipeKey, key);
                    debug("Registered custom recipe: " + key + " (Yields: " + amount + ")");
                }
            } else if (type.equals("shapeless")) {
                ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, resultItem);
                List<String> ingredientList = section.getStringList("ingredients");
                for (String matName : ingredientList) {
                    matName = matName.toUpperCase();
                    if (matName.equals("WOOL")) {
                        List<Material> wools = new ArrayList<>();
                        for (Material m : Material.values()) {
                            if (m.name().endsWith("_WOOL")) wools.add(m);
                        }
                        recipe.addIngredient(new RecipeChoice.MaterialChoice(wools));
                    } else {
                        Material mat = Material.matchMaterial(matName);
                        if (mat != null) {
                            recipe.addIngredient(mat);
                        }
                    }
                }
                Bukkit.addRecipe(recipe);
                registeredKeys.add(recipeKey);
                registeredRecipeNames.put(recipeKey, key);
                debug("Registered custom recipe: " + key + " (Yields: " + amount + ")");
            }
        }
    }

    @EventHandler
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        if (!(event.getRecipe() instanceof Keyed keyed)) {
            return;
        }

        String recipeName = registeredRecipeNames.get(keyed.getKey());
        if (recipeName == null) {
            return;
        }
        ConfigurationSection section = config.getConfigurationSection("recipes." + recipeName);
        if (section == null) {
            return;
        }

        event.getInventory().setResult(createResultItem(recipeName, section.getInt("result-amount", 1)));
    }

    private ItemStack createResultItem(String recipeName, int amount) {
        ItemStack resultItem;
        if (recipeName.equalsIgnoreCase("waylith_scroll")) {
            resultItem = plugin.getScrollManager().createWaylithScroll();
        } else if (recipeName.equalsIgnoreCase("bed_scroll")) {
            resultItem = plugin.getScrollManager().createBedScroll();
        } else if (recipeName.equalsIgnoreCase("tpa_scroll")) {
            resultItem = plugin.getScrollManager().createTpaScroll();
        } else {
            resultItem = plugin.getWaylithManager().createWaylithBlockItem();
        }
        resultItem.setAmount(Math.min(amount, resultItem.getMaxStackSize()));
        return resultItem;
    }
}