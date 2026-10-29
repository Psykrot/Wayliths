package com.kotacraft.wayliths;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WaylithCommand implements CommandExecutor, TabCompleter {

    private final Wayliths plugin;

    public WaylithCommand(Wayliths plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (args.length > 0) {
            String sub = args[0].toLowerCase();

            if (sub.equals("reload")) {
                if (!sender.hasPermission("wayliths.admin")) {
                    sender.sendMessage(plugin.getLangMessage("no-permission", null, null));
                    return true;
                }

                plugin.reloadConfig();
                plugin.reloadLang();
                plugin.getWaylithManager().loadWaypoints();
                plugin.startParticleTask();

                // Reload and re-register custom crafting recipes from crafting.yml
                if (plugin.getCraftingManager() != null) {
                    plugin.getCraftingManager().loadCraftingConfig();
                    plugin.getCraftingManager().registerRecipes();
                }

                sender.sendMessage(plugin.getLangMessage("reload-success", null, null));
                return true;
            }

            if (sub.equals("give")) {
                if (!sender.hasPermission("wayliths.admin")) {
                    sender.sendMessage(plugin.getLangMessage("no-permission", null, null));
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /wayliths give <bed_scroll|waylith_scroll|tpa_scroll|waylith_block> [player]", NamedTextColor.YELLOW));
                    return true;
                }

                String itemType = args[1].toLowerCase();
                String itemConfigKey = switch (itemType) {
                    case "bed_scroll" -> "bed-scroll";
                    case "waylith_scroll" -> "waylith-scroll";
                    case "tpa_scroll" -> "tpa-scroll";
                    case "waylith_block" -> "waylith-block";
                    default -> null;
                };
                if (itemConfigKey != null && !plugin.isItemEnabled(itemConfigKey)) {
                    sender.sendMessage(plugin.getLangMessage("item-disabled", null, null));
                    return true;
                }

                ItemStack item;
                String itemName;

                switch (itemType) {
                    case "bed_scroll":
                        item = plugin.getScrollManager().createBedScroll();
                        itemName = "Bed Scroll";
                        break;
                    case "waylith_scroll":
                        item = plugin.getScrollManager().createWaylithScroll();
                        itemName = "Waylith Scroll";
                        break;
                    case "tpa_scroll":
                        item = plugin.getScrollManager().createTpaScroll();
                        itemName = "TPA Scroll";
                        break;
                    case "waylith_block":
                        item = plugin.getWaylithManager().createWaylithBlockItem();
                        itemName = "Waylith Block";
                        break;
                    default:
                        sender.sendMessage(Component.text("Unknown item type. Use: bed_scroll, waylith_scroll, tpa_scroll, or waylith_block", NamedTextColor.RED));
                        return true;
                }

                Player target;
                if (args.length > 2) {
                    target = Bukkit.getPlayer(args[2]);
                    if (target == null) {
                        sender.sendMessage(Component.text("Player not found or offline.", NamedTextColor.RED));
                        return true;
                    }
                } else if (sender instanceof Player player) {
                    target = player;
                } else {
                    sender.sendMessage(Component.text("Console must specify a player target!", NamedTextColor.RED));
                    return true;
                }

                target.getInventory().addItem(item);
                target.sendMessage(plugin.getLangMessage("give-received", Map.of(
                        "{item}", itemName,
                        "{amount}", String.valueOf(item.getAmount()),
                        "{player}", sender.getName())));
                if (!(sender instanceof Player senderPlayer) || !senderPlayer.equals(target)) {
                    sender.sendMessage(plugin.getLangMessage("give-sent", Map.of(
                            "{item}", itemName,
                            "{amount}", String.valueOf(item.getAmount()),
                            "{player}", target.getName())));
                }
                return true;
            }
        }

        sender.sendMessage(plugin.getLangMessage("prefix", null, null).append(Component.text("Usage: /wayliths [reload|give <item> [player]]", NamedTextColor.YELLOW)));
        return true;
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("wayliths.admin")) {
            return completions;
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            List<String> options = List.of("reload", "give");
            for (String option : options) {
                if (option.startsWith(input)) {
                    completions.add(option);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String input = args[1].toLowerCase();
            List<String> options = List.of("bed_scroll", "waylith_scroll", "tpa_scroll", "waylith_block");
            for (String option : options) {
                if (option.startsWith(input)) {
                    completions.add(option);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String input = args[2].toLowerCase();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(input)) {
                    completions.add(player.getName());
                }
            }
        }
        return completions;
    }
}