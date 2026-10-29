package com.kotacraft.wayliths;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.items.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class ScrollManager {

    private final Wayliths plugin;
    private final NamespacedKey bedScrollKey;
    private final NamespacedKey waylithScrollKey;
    private final NamespacedKey tpaScrollKey;

    public ScrollManager(Wayliths plugin) {
        this.plugin = plugin;
        this.bedScrollKey = new NamespacedKey(plugin, "is_bed_scroll");
        this.waylithScrollKey = new NamespacedKey(plugin, "is_waylith_scroll");
        this.tpaScrollKey = new NamespacedKey(plugin, "is_tpa_scroll");
    }

    public ItemStack createBedScroll() {
        String type = plugin.getConfig().getString("items.bed-scroll.type", "Minecraft").trim();
        String id = plugin.getConfig().getString("items.bed-scroll.id", "PAPER").trim();

        ItemStack item = null;
        if (type.equalsIgnoreCase("Nexo") && plugin.isNexoEnabled()) {
            ItemBuilder builder = NexoItems.itemFromId(id);
            if (builder != null) {
                item = builder.build();
            }
        }

        if (item == null) {
            Material mat = Material.matchMaterial(id);
            item = new ItemStack(mat != null ? mat : Material.PAPER);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component name = plugin.getLangMessage("items.bed_scroll.name", null, null);
            List<Component> lore = plugin.getLangLore("items.bed_scroll.lore");
            meta.displayName(name);
            meta.lore(lore);
            plugin.applyItemGlowing(meta, "bed-scroll");

            // Stamp the hidden persistent data tag onto the item
            meta.getPersistentDataContainer().set(bedScrollKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }

        return item;
    }

    public ItemStack createWaylithScroll() {
        String type = plugin.getConfig().getString("items.waylith-scroll.type", "Minecraft").trim();
        String id = plugin.getConfig().getString("items.waylith-scroll.id", "PAPER").trim();

        ItemStack item = null;
        if (type.equalsIgnoreCase("Nexo") && plugin.isNexoEnabled()) {
            ItemBuilder builder = NexoItems.itemFromId(id);
            if (builder != null) {
                item = builder.build();
            }
        }

        if (item == null) {
            Material mat = Material.matchMaterial(id);
            item = new ItemStack(mat != null ? mat : Material.PAPER);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component customName = plugin.getLangMessage("items.waylith_scroll.name", null, null);
            List<Component> loreLines = plugin.getLangLore("items.waylith_scroll.lore");
            meta.displayName(customName);
            meta.lore(loreLines);
            plugin.applyItemGlowing(meta, "waylith-scroll");

            // Stamp the hidden persistent data tag onto the item
            meta.getPersistentDataContainer().set(waylithScrollKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }

        return item;
    }

    public boolean isBedScroll(ItemStack item) {
        return hasScrollTag(item, bedScrollKey);
    }

    public boolean isWaylithScroll(ItemStack item) {
        return hasScrollTag(item, waylithScrollKey);
    }

    public ItemStack createTpaScroll() {
        String type = plugin.getConfig().getString("items.tpa-scroll.type", "Minecraft").trim();
        String id = plugin.getConfig().getString("items.tpa-scroll.id", "PAPER").trim();

        ItemStack item = null;
        if (type.equalsIgnoreCase("Nexo") && plugin.isNexoEnabled()) {
            ItemBuilder builder = NexoItems.itemFromId(id);
            if (builder != null) {
                item = builder.build();
            }
        }

        if (item == null) {
            Material mat = Material.matchMaterial(id);
            item = new ItemStack(mat != null ? mat : Material.PAPER);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.getLangMessage("items.tpa_scroll.name", null, null));
            meta.lore(plugin.getLangLore("items.tpa_scroll.lore"));
            plugin.applyItemGlowing(meta, "tpa-scroll");
            meta.getPersistentDataContainer().set(tpaScrollKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isTpaScroll(ItemStack item) {
        return hasScrollTag(item, tpaScrollKey);
    }

    private boolean hasScrollTag(ItemStack item, NamespacedKey scrollKey) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        Byte tagValue = meta.getPersistentDataContainer().get(scrollKey, PersistentDataType.BYTE);
        return tagValue != null && tagValue == 1;
    }
}