package com.kotacraft.wayliths;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.BooleanSupplier;

public class WaylithListener implements Listener {

    private final Wayliths plugin;
    private final WaylithManager manager;
    private final NamespacedKey waylithItemKey;

    private final Map<UUID, String> activeRenames = new HashMap<>();
    private final Set<UUID> pendingWaylithCreations = new HashSet<>();
    private final Map<UUID, String> playerSortModes = new HashMap<>();
    private final Map<UUID, String> playerFilters = new HashMap<>();
    private final Map<UUID, Integer> playerPages = new HashMap<>();
    private final Map<UUID, String> editTargets = new HashMap<>();
    private final Map<UUID, Map<UUID, String>> waylithDialogTargets = new HashMap<>();
    private final Set<UUID> usedWaylithScroll = new HashSet<>();
    private final Map<UUID, BukkitTask> pendingTeleports = new HashMap<>();
    private final Map<UUID, Location> pendingTeleportOrigins = new HashMap<>();
    private final Map<UUID, Boolean> pendingTeleportCancelOnMove = new HashMap<>();
    private final Map<UUID, Long> teleportCooldowns = new HashMap<>();
    private static final double TELEPORT_MOVE_TOLERANCE_SQUARED = 0.01;
    private static final Key RENAME_DIALOG_SUBMIT = Key.key("wayliths", "rename_submit");
    private static final Key RENAME_DIALOG_CANCEL = Key.key("wayliths", "rename_cancel");
    private static final String RENAME_DIALOG_INPUT = "name";
    private static final int WAYLITH_PAGE_SIZE = 12;
    private boolean warnedInvalidCountdownDisplay;
    private boolean warnedInvalidCooldownDisplay;
    private boolean warnedInvalidTeleportSound;
    private boolean warnedInvalidTeleportParticle;
    private boolean warnedInvalidBeaconBeamParticle;
    private final Set<String> warnedInvalidWaylithSounds = new HashSet<>();

    public WaylithListener(Wayliths plugin) {
        this.plugin = plugin;
        this.manager = plugin.getWaylithManager();
        this.waylithItemKey = new NamespacedKey(plugin, "is_waylith_block");
    }

    private void debug(String message) {
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    private boolean isWaylithItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        Byte tagValue = item.getItemMeta().getPersistentDataContainer().get(waylithItemKey, PersistentDataType.BYTE);
        return tagValue != null && tagValue == 1;
    }

    private int getPlayerWaylithLimit(Player player) {
        if (player.hasPermission("wayliths.limit.*") || player.hasPermission("wayliths.admin")) {
            return Integer.MAX_VALUE;
        }

        int highestLimit = plugin.getConfig().getInt("default-waylith-limit", 1);

        for (PermissionAttachmentInfo permInfo : player.getEffectivePermissions()) {
            String perm = permInfo.getPermission().toLowerCase();
            if (perm.startsWith("wayliths.limit.")) {
                try {
                    int limit = Integer.parseInt(perm.substring("wayliths.limit.".length()));
                    if (limit > highestLimit) {
                        highestLimit = limit;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return highestLimit;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isWaylithItem(event.getItemInHand())) {
            Player player = event.getPlayer();

            if (!plugin.isItemEnabled("waylith-block")) {
                player.sendMessage(plugin.getLangMessage("item-disabled", null, null));
                event.setCancelled(true);
                return;
            }

            int maxAllowed = getPlayerWaylithLimit(player);
            if (maxAllowed <= 0) {
                player.sendMessage(plugin.getLangMessage("waylith-placement-disabled", null, null));
                event.setCancelled(true);
                return;
            }

            int ownedCount = manager.getPlayerWaylithCount(player.getName());

            if (ownedCount >= maxAllowed) {
                player.sendMessage(plugin.getLangMessage("waylith-limit-reached", null, null));
                event.setCancelled(true);
                return;
            }

            Location blockLoc = event.getBlockPlaced().getLocation();

            Location spawnLoc = player.getLocation().clone();
            spawnLoc.setPitch(0.0f);

            String defaultName = manager.generateUniqueDefaultName(player.getName());
            manager.saveWaylith(defaultName, blockLoc, spawnLoc, player.getName());
            debug("Saved Waylith '" + defaultName + "' at block: " + blockLoc);
            playWaylithPlacementEffects(blockLoc);

            activeRenames.put(player.getUniqueId(), defaultName);
            pendingWaylithCreations.add(player.getUniqueId());
            openWaylithNameDialog(player, defaultName);
        }
    }

    @EventHandler
    public void onWaylithDialogClick(PlayerCustomClickEvent event) {
        Key action = event.getIdentifier();
        if (!action.namespace().equals("wayliths")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;
        Player player = connection.getPlayer();
        String[] parts = action.value().split("/");
        if (parts.length < 2) return;

        if (parts[0].equals("network")) {
            handleNetworkDialogClick(player, parts);
        } else if (parts[0].equals("edit")) {
            handleEditDialogClick(player, parts);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        // Check for scroll usage
        if (item != null) {
            ScrollManager scrollManager = plugin.getScrollManager();
            if (scrollManager.isBedScroll(item) || scrollManager.isWaylithScroll(item) || scrollManager.isTpaScroll(item)) {
                if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    event.setCancelled(true);

                    if (scrollManager.isBedScroll(item)) {
                        if (!plugin.isItemEnabled("bed-scroll")) {
                            player.sendMessage(plugin.getLangMessage("item-disabled", null, null));
                            return;
                        }

                        Location respawnLoc = player.getRespawnLocation();
                        if (respawnLoc == null) {
                            player.sendMessage(plugin.getLangMessage("bed-scroll-missing", null, null));
                            return;
                        }

                        if (isTeleportUnavailable(player)) return;
                        if (!hasScroll(player, true)) return;
                        scheduleTeleport(player, respawnLoc,
                                plugin.getLangMessage("bed-scroll-teleport", null, null),
                                () -> hasScroll(player, true),
                                () -> consumeScroll(player, true),
                                "bed-scroll");
                    } else if (scrollManager.isWaylithScroll(item)) {
                        if (!plugin.isItemEnabled("waylith-scroll")) {
                            player.sendMessage(plugin.getLangMessage("item-disabled", null, null));
                            return;
                        }

                        usedWaylithScroll.add(player.getUniqueId());
                        editTargets.remove(player.getUniqueId());
                        playerPages.put(player.getUniqueId(), 0);
                        openWaylithGUI(player);
                    }
                }
                return;
            }
        }

        // Standard Waylith Block interaction
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        if (manager.isWaylithBlock(block.getLocation())) {
            event.setCancelled(true);
            String key = manager.getWaylithKeyAt(block.getLocation());
            if (key != null) editTargets.put(player.getUniqueId(), key);
            usedWaylithScroll.remove(player.getUniqueId());
            playerPages.put(player.getUniqueId(), 0);
            openWaylithGUI(player);
        }
    }

    public boolean isTeleportUnavailable(Player player) {
        UUID playerId = player.getUniqueId();
        if (pendingTeleports.containsKey(playerId)) {
            player.sendMessage(plugin.getLangMessage("teleport-already-counting", null, null));
            return true;
        }

        long remainingMillis = teleportCooldowns.getOrDefault(playerId, 0L) - System.currentTimeMillis();
        if (remainingMillis > 0) {
            long remainingSeconds = (remainingMillis + 999) / 1000;
            sendTeleportCooldown(player, remainingSeconds);
            return true;
        }
        teleportCooldowns.remove(playerId);
        return false;
    }

    private void sendTeleportCooldown(Player player, long seconds) {
        String display = plugin.getConfig().getString("teleport.cooldown-display", "actionbar").trim();
        Component message = display.equalsIgnoreCase("actionbar")
                ? plugin.getLangMessageWithoutPrefix("teleport-cooldown", "{seconds}", Long.toString(seconds))
                : plugin.getLangMessage("teleport-cooldown", "{seconds}", Long.toString(seconds));
        if (display.equalsIgnoreCase("actionbar")) {
            player.sendActionBar(message);
        } else {
            if (!display.equalsIgnoreCase("chat") && !warnedInvalidCooldownDisplay) {
                plugin.getLogger().warning("Invalid teleport.cooldown-display value '" + display + "'. Using chat; expected 'chat' or 'actionbar'.");
                warnedInvalidCooldownDisplay = true;
            }
            player.sendMessage(message);
        }
    }

    public void scheduleTeleport(Player player, Location destination, Component successMessage,
                                 BooleanSupplier canComplete, Runnable consumeScroll, String itemKey) {
        scheduleTeleport(player, destination, successMessage, canComplete, consumeScroll, itemKey,
                plugin.getConfig().getBoolean("teleport.cancel-on-move", true));
    }

    public void scheduleTeleport(Player player, Location destination, Component successMessage,
                                 BooleanSupplier canComplete, Runnable consumeScroll, String itemKey,
                                 boolean cancelOnMove) {
        UUID playerId = player.getUniqueId();
        String itemPath = "teleport.items." + itemKey;
        int countdownSeconds = Math.max(0, plugin.getConfig().getInt(itemPath + ".countdown-seconds", 3));
        int cooldownSeconds = Math.max(0, plugin.getConfig().getInt("teleport.cooldown-seconds", 10));

        if (countdownSeconds == 0) {
            completeTeleport(player, destination, successMessage, canComplete, consumeScroll, cooldownSeconds);
            return;
        }

        int[] remainingSeconds = {countdownSeconds};
        BukkitTask[] taskHolder = new BukkitTask[1];
        sendTeleportCountdown(player, countdownSeconds);
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || destination.getWorld() == null) {
                pendingTeleports.remove(playerId);
                pendingTeleportOrigins.remove(playerId);
                pendingTeleportCancelOnMove.remove(playerId);
                taskHolder[0].cancel();
                if (player.isOnline()) {
                    player.sendActionBar(Component.empty());
                    player.sendMessage(plugin.getLangMessage("teleport-failed", null, null));
                }
                return;
            }

            remainingSeconds[0]--;
            if (remainingSeconds[0] == 0) {
                pendingTeleports.remove(playerId);
                pendingTeleportOrigins.remove(playerId);
                pendingTeleportCancelOnMove.remove(playerId);
                taskHolder[0].cancel();
                completeTeleport(player, destination, successMessage, canComplete, consumeScroll, cooldownSeconds);
            } else {
                sendTeleportCountdown(player, remainingSeconds[0]);
            }
        }, 20L, 20L);
        pendingTeleports.put(playerId, taskHolder[0]);
        pendingTeleportOrigins.put(playerId, player.getLocation().clone());
        pendingTeleportCancelOnMove.put(playerId, cancelOnMove);
    }

    private void sendTeleportCountdown(Player player, int seconds) {
        String display = plugin.getConfig().getString("teleport.countdown-display", "actionbar").trim();
        Component message = display.equalsIgnoreCase("actionbar")
                ? plugin.getLangMessageWithoutPrefix("teleport-countdown", "{seconds}", Integer.toString(seconds))
                : plugin.getLangMessage("teleport-countdown", "{seconds}", Integer.toString(seconds));
        if (display.equalsIgnoreCase("actionbar")) {
            player.sendActionBar(message);
        } else {
            if (!display.equalsIgnoreCase("chat") && !warnedInvalidCountdownDisplay) {
                plugin.getLogger().warning("Invalid teleport.countdown-display value '" + display + "'. Using chat; expected 'chat' or 'actionbar'.");
                warnedInvalidCountdownDisplay = true;
            }
            player.sendMessage(message);
        }
        if (plugin.getConfig().getBoolean("teleport.countdown-sound-enabled", true)) {
            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        }
    }

    private void completeTeleport(Player player, Location destination, Component successMessage,
                                  BooleanSupplier canComplete, Runnable consumeScroll, int cooldownSeconds) {
        if (!player.isOnline()) return;
        if (destination.getWorld() == null) {
            player.sendActionBar(Component.empty());
            player.sendMessage(plugin.getLangMessage("teleport-failed", null, null));
            return;
        }
        if (!canComplete.getAsBoolean()) {
            player.sendActionBar(Component.empty());
            player.sendMessage(plugin.getLangMessage("teleport-failed", null, null));
            return;
        }
        Location startLoc = player.getLocation().clone();
        if (!player.teleport(destination)) {
            player.sendActionBar(Component.empty());
            player.sendMessage(plugin.getLangMessage("teleport-failed", null, null));
            return;
        }

        consumeScroll.run();
        player.sendActionBar(Component.empty());
        if (cooldownSeconds > 0) {
            teleportCooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldownSeconds * 1000L);
        }
        org.bukkit.Sound teleportSound = getTeleportSound();
        if (startLoc.getWorld() != null) {
            startLoc.getWorld().playSound(startLoc, teleportSound, 1.0f, 1.0f);
            spawnTeleportParticles(startLoc);
        }
        Location destinationLoc = player.getLocation().clone();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (destinationLoc.getWorld() != null) {
                destinationLoc.getWorld().playSound(destinationLoc, teleportSound, 1.0f, 1.0f);
                spawnTeleportParticles(destinationLoc);
            }
        }, 1L);
        player.sendMessage(successMessage);
    }

    private void spawnTeleportParticles(Location location) {
        if (!plugin.getConfig().getBoolean("teleport.particles.enabled", true) || location.getWorld() == null) return;
        org.bukkit.Particle particle = getTeleportParticle();
        int count = Math.max(0, plugin.getConfig().getInt("teleport.particles.count", 30));
        location.getWorld().spawnParticle(particle, location.clone().add(0, 1, 0), count, 0.5, 0.5, 0.5, 0.1);
    }

    private void playWaylithPlacementEffects(Location blockLocation) {
        Location center = blockLocation.clone().add(0.5, 0.5, 0.5);
        if (plugin.getConfig().getBoolean("waylith-effects.beacon-beam-enabled", true)) {
            spawnBeaconBeam(center);
        }
        if (plugin.getConfig().getBoolean("waylith-effects.placement-sound-enabled", true)) {
            center.getWorld().playSound(center,
                    getConfiguredSound("waylith-effects.placement-sound", "block.beacon.activate",
                            org.bukkit.Sound.BLOCK_BEACON_ACTIVATE),
                    1.0f, 1.0f);
        }
    }

    private org.bukkit.Sound getConfiguredSound(String configPath, String defaultSoundName,
                                                org.bukkit.Sound fallbackSound) {
        String soundName = plugin.getConfig().getString(configPath, defaultSoundName).trim();
        NamespacedKey soundKey = NamespacedKey.fromString(soundName);
        org.bukkit.Sound sound = soundKey == null ? null : Registry.SOUND_EVENT.get(soundKey);
        if (sound != null) {
            return sound;
        }
        if (warnedInvalidWaylithSounds.add(configPath)) {
            plugin.getLogger().warning("Invalid " + configPath + " value '" + soundName
                    + "'. Using " + defaultSoundName + ".");
        }
        return fallbackSound;
    }

    private void spawnBeaconBeam(Location center) {
        if (center.getWorld() == null) return;

        int startY = center.getBlockY() + 1;
        int endY = Math.min(startY + 24, center.getWorld().getMaxHeight() - 1);
        if (startY > endY) return;

        String particleType = plugin.getConfig()
                .getString("waylith-effects.beacon-beam-particle", "END_ROD").trim();
        boolean purpleDust = particleType.equalsIgnoreCase("PURPLE_DUST");
        if (!purpleDust && !particleType.equalsIgnoreCase("END_ROD")) {
            if (!warnedInvalidBeaconBeamParticle) {
                plugin.getLogger().warning("Invalid waylith-effects.beacon-beam-particle value '"
                        + particleType + "'. Using END_ROD; expected END_ROD or PURPLE_DUST.");
                warnedInvalidBeaconBeamParticle = true;
            }
        }
        org.bukkit.Particle.DustOptions beamDust = purpleDust
                ? new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(128, 0, 255), 1.5f)
                : null;
        int[] remainingRuns = {10};
        BukkitTask[] taskHolder = new BukkitTask[1];
        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int y = startY; y <= endY; y++) {
                Location particleLocation = new Location(
                        center.getWorld(), center.getBlockX() + 0.5, y, center.getBlockZ() + 0.5);
                if (purpleDust) {
                    center.getWorld().spawnParticle(org.bukkit.Particle.DUST, particleLocation,
                            8, 0.12, 0, 0.12, beamDust);
                } else {
                    center.getWorld().spawnParticle(org.bukkit.Particle.END_ROD,
                            particleLocation, 1, 0, 0, 0, 0);
                }
            }
            remainingRuns[0]--;
            if (remainingRuns[0] == 0) {
                taskHolder[0].cancel();
            }
        }, 1L, 2L);
    }

    private org.bukkit.Particle getTeleportParticle() {
        String particleName = plugin.getConfig().getString("teleport.particles.type", "PORTAL").trim();
        try {
            return org.bukkit.Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (!warnedInvalidTeleportParticle) {
                plugin.getLogger().warning("Invalid teleport.particles.type value '" + particleName
                        + "'. Using PORTAL.");
                warnedInvalidTeleportParticle = true;
            }
            return org.bukkit.Particle.PORTAL;
        }
    }

    private org.bukkit.Sound getTeleportSound() {
        String soundName = plugin.getConfig().getString("teleport.sound", "entity.enderman.teleport").trim();
        NamespacedKey soundKey = NamespacedKey.fromString(soundName);
        org.bukkit.Sound sound = soundKey == null ? null : Registry.SOUND_EVENT.get(soundKey);
        if (sound != null) {
            return sound;
        }
        if (!warnedInvalidTeleportSound) {
            plugin.getLogger().warning("Invalid teleport.sound value '" + soundName
                    + "'. Using entity.enderman.teleport.");
            warnedInvalidTeleportSound = true;
        }
        return org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT;
    }

    private ItemStack findScroll(Player player, boolean bedScroll) {
        if (!plugin.isItemEnabled(bedScroll ? "bed-scroll" : "waylith-scroll")) return null;
        ScrollManager scrollManager = plugin.getScrollManager();
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && (bedScroll ? scrollManager.isBedScroll(item) : scrollManager.isWaylithScroll(item))) {
                return item;
            }
        }
        ItemStack offHandItem = player.getInventory().getItemInOffHand();
        if (bedScroll ? scrollManager.isBedScroll(offHandItem) : scrollManager.isWaylithScroll(offHandItem)) {
            return offHandItem;
        }
        return null;
    }

    private boolean hasScroll(Player player, boolean bedScroll) {
        return findScroll(player, bedScroll) != null;
    }

    private void consumeScroll(Player player, boolean bedScroll) {
        ItemStack scroll = findScroll(player, bedScroll);
        if (scroll != null) {
            scroll.setAmount(scroll.getAmount() - 1);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (!pendingTeleportCancelOnMove.getOrDefault(playerId, false)) return;
        Location to = event.getTo();
        BukkitTask pendingTask = pendingTeleports.get(playerId);
        if (pendingTask != null) {
            Location origin = pendingTeleportOrigins.get(playerId);
            if (origin != null && Objects.equals(origin.getWorld(), to.getWorld())
                    && origin.distanceSquared(to) <= TELEPORT_MOVE_TOLERANCE_SQUARED) {
                return;
            }

            pendingTeleports.remove(playerId);
            pendingTeleportOrigins.remove(playerId);
            pendingTeleportCancelOnMove.remove(playerId);
            pendingTask.cancel();
            event.getPlayer().sendActionBar(Component.empty());
            event.getPlayer().sendMessage(plugin.getLangMessage("teleport-cancelled", null, null));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask pendingTask = pendingTeleports.remove(playerId);
        pendingTeleportOrigins.remove(playerId);
        pendingTeleportCancelOnMove.remove(playerId);
        if (pendingTask != null) pendingTask.cancel();
        usedWaylithScroll.remove(playerId);
        activeRenames.remove(playerId);
        pendingWaylithCreations.remove(playerId);
        waylithDialogTargets.remove(playerId);
        editTargets.remove(playerId);
        playerSortModes.remove(playerId);
        playerFilters.remove(playerId);
        playerPages.remove(playerId);
    }

    private void openWaylithGUI(Player player) {
        Map<String, String> waylithsMap = manager.getWaylithDisplayNames();
        List<Map.Entry<String, String>> waylithEntries = new ArrayList<>();
        String filterValue = playerFilters.getOrDefault(player.getUniqueId(), "all");
        String filter = filterValue.equals("personal") ? "personal" : "all";
        boolean admin = player.hasPermission("wayliths.admin");
        waylithsMap.forEach((key, name) -> {
            WaylithVisibility visibility = manager.getVisibility(key);
            if (filter.equals("personal")) {
                if (visibility != WaylithVisibility.PRIVATE || !manager.getOwner(key).equalsIgnoreCase(player.getName()))
                    return;
                waylithEntries.add(Map.entry(key, name));
            } else if (manager.canSee(key, player.getName(), admin)) {
                waylithEntries.add(Map.entry(key, name));
            }
        });

        String sortMode = playerSortModes.getOrDefault(player.getUniqueId(), "a_z");
        Comparator<Map.Entry<String, String>> nameComparator = Map.Entry.comparingByValue(String.CASE_INSENSITIVE_ORDER);
        if (sortMode.equals("z_a")) nameComparator = nameComparator.reversed();
        waylithEntries.sort(nameComparator);

        int page = playerPages.computeIfAbsent(player.getUniqueId(), _ -> 0);
        int totalPages = Math.max(1, (waylithEntries.size() + WAYLITH_PAGE_SIZE - 1) / WAYLITH_PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        playerPages.put(player.getUniqueId(), page);

        int startIndex = page * WAYLITH_PAGE_SIZE;
        int endIndex = Math.min(startIndex + WAYLITH_PAGE_SIZE, waylithEntries.size());
        UUID playerId = player.getUniqueId();
        Map<UUID, String> targets = new HashMap<>();
        waylithDialogTargets.put(playerId, targets);
        List<ActionButton> actions = new ArrayList<>();
        List<DialogBody> bodies = new ArrayList<>();
        if (waylithEntries.isEmpty()) {
            bodies.add(DialogBody.plainMessage(
                    plugin.getLangMessageWithoutPrefix("waylith-dialog-empty", null, null)));
        } else {
            bodies.add(DialogBody.plainMessage(plugin.getLangMessageWithoutPrefix(
                    "waylith-dialog-page", "{page}", (page + 1) + " / " + totalPages)));
        }

        String nextFilter = filter.equals("all") ? "personal" : "all";
        String readableFilter = MiniMessage.miniMessage().serialize(plugin.getLangMessageWithoutPrefix(
                filter.equals("all") ? "waylith-gui-filter-all" : "waylith-gui-filter-personal", null, null));
        String nextSort = sortMode.equals("z_a") ? "a_z" : "z_a";
        String sortLabelKey = sortMode.equals("z_a")
                ? "waylith-gui-sort-name-descending"
                : "waylith-gui-sort-name-ascending";
        String readableSort = MiniMessage.miniMessage().serialize(
                plugin.getLangMessageWithoutPrefix(sortLabelKey, null, null));
        actions.add(waylithDialogButton(
                plugin.getLangMessageWithoutPrefix("waylith-gui-sort-button", "{sort}", readableSort),
                joinTooltipLines(plugin.getLangLore("waylith-gui-sort-lore")),
                "network/sort/" + nextSort));

        actions.add(waylithDialogButton(
                plugin.getLangMessageWithoutPrefix("waylith-gui-filter-button", "{filter}", readableFilter),
                joinTooltipLines(plugin.getLangLore("waylith-gui-filter-lore")),
                "network/filter/" + nextFilter));

        String editTarget = editTargets.get(playerId);
        boolean canEditTarget = editTarget != null
                && manager.waylithExists(editTarget)
                && canEditWaylith(player, editTarget);
        if (canEditTarget) {
            actions.add(waylithDialogButton(
                    plugin.getLangMessageWithoutPrefix("waylith-edit-button", null, null),
                    joinTooltipLines(plugin.getLangLore("waylith-edit-button-lore")),
                    "network/edit"));
        }

        for (int i = startIndex; i < endIndex; i++) {
            Map.Entry<String, String> entry = waylithEntries.get(i);
            String displayName = entry.getValue();
            UUID targetToken = UUID.randomUUID();
            targets.put(targetToken, entry.getKey());
            Component tooltip = joinTooltipLines(plugin.getLangLore("waylith-gui-entry-lore", Map.of(
                    "{visibility}", getVisibilityLabel(manager.getVisibility(entry.getKey())),
                    "{owner}", manager.getOwner(entry.getKey()))));
            actions.add(waylithDialogButton(
                    plugin.getLangMessageWithoutPrefix("waylith-gui-entry-name", "{name}", displayName),
                    tooltip,
                    "network/waypoint/" + targetToken));
        }

        if (page > 0) {
            actions.add(waylithDialogButton(
                    plugin.getLangMessageWithoutPrefix("gui-previous-page", null, null),
                    null,
                    "network/page/" + (page - 1)));
        }
        if (page < totalPages - 1) {
            actions.add(waylithDialogButton(
                    plugin.getLangMessageWithoutPrefix("gui-next-page", null, null),
                    null,
                    "network/page/" + (page + 1)));
        }

        DialogBase base = DialogBase.builder(plugin.getLangMessageWithoutPrefix(
                        "gui-title", null, null))
                .canCloseWithEscape(true)
                .afterAction(DialogAfterAction.CLOSE)
                .body(bodies)
                .build();
        ActionButton closeButton = waylithDialogButton(
                plugin.getLangMessageWithoutPrefix("gui-close", null, null),
                null,
                "network/close");
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.multiAction(actions, closeButton,
                        canEditTarget ? 3 : 2))));
    }

    private boolean canEditWaylith(Player player, String key) {
        return player.hasPermission("wayliths.admin") || manager.getCreator(key).equalsIgnoreCase(player.getName());
    }

    private void cycleWaylithVisibility(Player player, String key) {
        WaylithVisibility current = manager.getVisibility(key);
        boolean admin = player.hasPermission("wayliths.admin");
        boolean canSetServerVisibility = admin || player.hasPermission("wayliths.visibility.server");
        WaylithVisibility next = switch (current) {
            case PRIVATE -> WaylithVisibility.PUBLIC;
            case PUBLIC -> canSetServerVisibility ? WaylithVisibility.SERVER : WaylithVisibility.PRIVATE;
            case SERVER -> WaylithVisibility.PRIVATE;
        };
        manager.setVisibility(key, next);
        player.sendMessage(plugin.getLangMessage("waylith-visibility-updated", "{visibility}", getVisibilityLabel(next)));
        playerFilters.putIfAbsent(player.getUniqueId(), "all");
        playerPages.put(player.getUniqueId(), 0);
    }

    private String getVisibilityLabel(WaylithVisibility visibility) {
        String key = visibility == WaylithVisibility.PRIVATE
                ? "waylith-visibility-private"
                : "waylith-visibility-public";
        return MiniMessage.miniMessage().serialize(plugin.getLangMessageWithoutPrefix(key, null, null));
    }

    private String getVisibilityButtonLabel(WaylithVisibility visibility) {
        String key = visibility == WaylithVisibility.SERVER
                ? "waylith-visibility-server"
                : visibility == WaylithVisibility.PRIVATE
                ? "waylith-visibility-private"
                : "waylith-visibility-public";
        return MiniMessage.miniMessage().serialize(plugin.getLangMessageWithoutPrefix(key, null, null));
    }

    private void openWaylithEditGUI(Player player, String key) {
        if (!manager.waylithExists(key) || !canEditWaylith(player, key)) return;
        WaylithVisibility visibility = manager.getVisibility(key);
        String visibilityLoreVariant = switch (visibility) {
            case PRIVATE -> "private";
            case PUBLIC -> "public";
            case SERVER -> "server";
        };
        String name = manager.getWaylithDisplayNames().get(key);
        List<DialogBody> bodies = List.of(DialogBody.plainMessage(
                plugin.getLangMessageWithoutPrefix("waylith-dialog-edit-description", "{name}", name)));
        List<ActionButton> actions = List.of(
                waylithDialogButton(
                        plugin.getLangMessageWithoutPrefix(
                                "waylith-edit-type", "{visibility}", getVisibilityButtonLabel(visibility)),
                        joinTooltipLines(plugin.getLangLore(
                                "waylith-edit-type-lore." + visibilityLoreVariant)),
                        "edit/visibility"),
                waylithDialogButton(
                        plugin.getLangMessageWithoutPrefix("waylith-edit-rename", "{name}", name),
                        joinTooltipLines(plugin.getLangLore(
                                "waylith-edit-rename-lore", Map.of("{name}", name))),
                        "edit/rename"));
        DialogBase base = DialogBase.builder(plugin.getLangMessageWithoutPrefix(
                        "waylith-edit-gui-title", null, null))
                .canCloseWithEscape(true)
                .afterAction(DialogAfterAction.CLOSE)
                .body(bodies)
                .build();
        ActionButton backButton = waylithDialogButton(
                plugin.getLangMessageWithoutPrefix("gui-back", null, null),
                null,
                "edit/back");
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.multiAction(actions, backButton, 2))));
    }

    private void handleNetworkDialogClick(Player player, String[] parts) {
        UUID playerId = player.getUniqueId();
        switch (parts[1]) {
            case "waypoint" -> {
                if (parts.length != 3) return;
                UUID token;
                try {
                    token = UUID.fromString(parts[2]);
                } catch (IllegalArgumentException exception) {
                    return;
                }
                Map<UUID, String> targets = waylithDialogTargets.get(playerId);
                String key = targets == null ? null : targets.remove(token);
                if (key != null) travelToWaylith(player, key);
            }
            case "page" -> {
                if (parts.length != 3) return;
                int page = parseDialogPage(parts[2]);
                if (page < 0) return;
                playerPages.put(playerId, page);
                reopenWaylithNetwork(player);
            }
            case "sort" -> {
                if (parts.length != 3 || !List.of("a_z", "z_a", "owner_first").contains(parts[2])) return;
                playerSortModes.put(playerId, parts[2]);
                playerPages.put(playerId, 0);
                reopenWaylithNetwork(player);
            }
            case "filter" -> {
                if (parts.length != 3 || !List.of("all", "personal").contains(parts[2])) return;
                playerFilters.put(playerId, parts[2]);
                playerPages.put(playerId, 0);
                reopenWaylithNetwork(player);
            }
            case "edit" -> {
                if (parts.length != 2) return;
                String key = editTargets.get(playerId);
                if (key != null && manager.waylithExists(key) && canEditWaylith(player, key)) {
                    Bukkit.getScheduler().runTask(plugin, () -> openWaylithEditGUI(player, key));
                }
            }
            case "close" -> {
                if (parts.length != 2) return;
                usedWaylithScroll.remove(playerId);
                waylithDialogTargets.remove(playerId);
            }
            default -> {
            }
        }
    }

    private void handleEditDialogClick(Player player, String[] parts) {
        String key = editTargets.get(player.getUniqueId());
        if (parts[1].equals("close")) {
            if (parts.length != 2) return;
            usedWaylithScroll.remove(player.getUniqueId());
            return;
        }
        if (parts[1].equals("back")) {
            if (parts.length != 2) return;
            Bukkit.getScheduler().runTask(plugin, () -> openWaylithGUI(player));
            return;
        }
        if (parts.length != 2) return;
        if (key == null || !manager.waylithExists(key) || !canEditWaylith(player, key)) return;
        switch (parts[1]) {
            case "visibility" -> {
                cycleWaylithVisibility(player, key);
                Bukkit.getScheduler().runTask(plugin, () -> openWaylithEditGUI(player, key));
            }
            case "rename" -> Bukkit.getScheduler().runTask(plugin, () -> beginWaylithRename(player, key));
            default -> {
            }
        }
    }

    private void travelToWaylith(Player player, String key) {
        boolean consumeScroll = usedWaylithScroll.remove(player.getUniqueId());
        boolean available = manager.waylithExists(key)
                && manager.canSee(key, player.getName(), player.hasPermission("wayliths.admin"));
        if (!available) {
            player.sendMessage(plugin.getLangMessage("waylith-unavailable", null, null));
            return;
        }
        Location targetLoc = manager.getWayliths().get(key);
        if (targetLoc == null || targetLoc.getWorld() == null) {
            player.sendMessage(plugin.getLangMessage("waylith-unavailable", null, null));
            return;
        }

        String teleportItem = consumeScroll ? "waylith-scroll" : "waylith-block";
        if (isTeleportUnavailable(player)) return;
        if (consumeScroll && !hasScroll(player, false)) {
            player.sendMessage(plugin.getLangMessage("no-permission", null, null));
            return;
        }

        String selectedName = manager.getWaylithDisplayNames().getOrDefault(key, key);
        scheduleTeleport(player, targetLoc,
                plugin.getLangMessage("waylith-teleport", "{name}", selectedName),
                () -> !consumeScroll || hasScroll(player, false),
                () -> {
                    if (consumeScroll) consumeScroll(player, false);
                },
                teleportItem);
    }

    private void reopenWaylithNetwork(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> openWaylithGUI(player));
    }

    private int parseDialogPage(String value) {
        try {
            int page = Integer.parseInt(value);
            return page >= 0 ? page : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private ActionButton waylithDialogButton(Component label, Component tooltip, String action) {
        //noinspection PatternValidation
        ActionButton.Builder button = ActionButton.builder(label
                        .colorIfAbsent(NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false))
                .action(DialogAction.customClick(Key.key("wayliths", action), null));
        if (tooltip != null) {
            button.tooltip(tooltip.decoration(TextDecoration.ITALIC, false));
        }
        return button.build();
    }

    private Component joinTooltipLines(List<Component> lines) {
        Component tooltip = Component.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) tooltip = tooltip.append(Component.newline());
            tooltip = tooltip.append(lines.get(index));
        }
        return tooltip;
    }

    private void beginWaylithRename(Player player, String key) {
        String name = manager.getWaylithDisplayNames().get(key);
        if (name == null) return;
        activeRenames.put(player.getUniqueId(), name);
        pendingWaylithCreations.remove(player.getUniqueId());
        openWaylithNameDialog(player, name);
    }

    private void openWaylithNameDialog(Player player, String initialName) {
        DialogBase base = DialogBase.builder(plugin.getLangMessageWithoutPrefix(
                        "waylith-name-dialog-title", null, null))
                .canCloseWithEscape(false)
                .afterAction(DialogAfterAction.CLOSE)
                .inputs(List.of(DialogInput.text(
                        RENAME_DIALOG_INPUT,
                        300,
                        plugin.getLangMessageWithoutPrefix("waylith-name-dialog-input", null, null),
                        true,
                        initialName,
                        50,
                        null)))
                .build();

        ActionButton saveButton = ActionButton.builder(plugin.getLangMessageWithoutPrefix(
                                "waylith-name-dialog-save", null, null)
                        .colorIfAbsent(NamedTextColor.WHITE))
                .action(DialogAction.customClick(RENAME_DIALOG_SUBMIT, null))
                .build();
        ActionButton cancelButton = ActionButton.builder(plugin.getLangMessageWithoutPrefix(
                                "waylith-name-dialog-cancel", null, null)
                        .colorIfAbsent(NamedTextColor.WHITE))
                .action(DialogAction.customClick(RENAME_DIALOG_CANCEL, null))
                .build();

        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.confirmation(saveButton, cancelButton)));
        player.showDialog(dialog);
    }

    @EventHandler
    public void onRenameDialogClick(PlayerCustomClickEvent event) {
        Key action = event.getIdentifier();
        if (!action.equals(RENAME_DIALOG_SUBMIT) && !action.equals(RENAME_DIALOG_CANCEL)) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;

        Player player = connection.getPlayer();
        UUID playerId = player.getUniqueId();
        String oldName = activeRenames.get(playerId);
        if (oldName == null) return;

        if (action.equals(RENAME_DIALOG_CANCEL)) {
            activeRenames.remove(playerId);
            if (pendingWaylithCreations.remove(playerId)) {
                player.sendMessage(plugin.getLangMessage("waylith-created", "{name}", oldName));
            }
            return;
        }

        DialogResponseView response = event.getDialogResponseView();
        String responseName = response == null ? null : response.getText(RENAME_DIALOG_INPUT);
        if (responseName == null) {
            plugin.getLogger().warning("Rename dialog submitted without a name from " + player.getName());
            player.sendMessage(plugin.getLangMessage("waylith-name-dialog-invalid", null, null));
            Bukkit.getScheduler().runTask(plugin, () -> openWaylithNameDialog(player, oldName));
            return;
        }

        String newName = responseName.trim();
        if (newName.isEmpty()) {
            player.sendMessage(plugin.getLangMessage("waylith-name-dialog-empty", null, null));
            Bukkit.getScheduler().runTask(plugin, () -> openWaylithNameDialog(player, responseName));
            return;
        }

        if (!newName.equals(oldName) && manager.waylithExists(newName)) {
            player.sendMessage(plugin.getLangMessage("waylith-exists", null, null));
            Bukkit.getScheduler().runTask(plugin, () -> openWaylithNameDialog(player, newName));
            return;
        }

        if (!newName.equals(oldName)) {
            String oldKey = oldName.toLowerCase();
            manager.renameWaylith(oldName, newName);
            if (oldKey.equals(editTargets.get(playerId))) {
                editTargets.put(playerId, newName.toLowerCase());
            }
            debug("Renamed Waylith from " + oldName + " to " + newName);
        }

        activeRenames.remove(playerId);
        boolean isCreation = pendingWaylithCreations.remove(playerId);
        String messageKey = isCreation ? "waylith-created" : "waylith-renamed";
        player.sendMessage(plugin.getLangMessage(messageKey, "{name}", newName));
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        debug("BlockBreakEvent triggered for: " + block.getType() + " at " + block.getLocation());

        if (manager.isWaylithBlock(block.getLocation())) {
            if (plugin.getConfig().getString("items.waylith-block.type", "Minecraft")
                    .equalsIgnoreCase("Nexo")) {
                return;
            }

            debug("-> Confirmed block is a Waylith structure!");
            event.setCancelled(true);
            handleWaylithBreak(block.getLocation(), event.getPlayer(), true);
        } else {
            debug("-> Block Break ignored: Not registered as a Waylith block.");
        }
    }

    void handleWaylithBreak(Location blockLocation, Player player, boolean clearBlock) {
        Location centerLoc = blockLocation.clone().add(0.5, 0.5, 0.5);
        if (plugin.getConfig().getBoolean("waylith-effects.break-sound-enabled", true)) {
            centerLoc.getWorld().playSound(centerLoc,
                    getConfiguredSound("waylith-effects.break-sound", "block.beacon.deactivate",
                            org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE),
                    1.0f, 1.0f);
        }

        if (clearBlock) {
            blockLocation.getBlock().setType(Material.AIR);
        }
        manager.removeWaylithAt(blockLocation);

        if (plugin.isItemEnabled("waylith-block")) {
            ItemStack waylithItem = manager.createWaylithBlockItem();
            blockLocation.getWorld().dropItemNaturally(centerLoc, waylithItem);
            debug("-> Dropped custom NBT Waylith item.");
        }

        Bukkit.getScheduler().runTaskLater(plugin, () ->
                centerLoc.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, centerLoc, 15, 0.3, 0.3, 0.3,
                        Bukkit.createBlockData(Material.STONE)), 1L);

        player.sendMessage(plugin.getLangMessage("waylith-removed", null, null));
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        ItemStack itemStack = itemEntity.getItemStack();
        Location dropLoc = itemEntity.getLocation();

        // Check if an item spawned right where a Waylith block was recently broken (within a tight 1.5 block radius)
        boolean nearWaylith = manager.getBlockLocations().values().stream().anyMatch(loc ->
                loc != null && loc.getWorld() != null && loc.getWorld().equals(dropLoc.getWorld()) &&
                        loc.distanceSquared(dropLoc) <= 2.25
        );

        if (nearWaylith) {
            boolean isTagged = itemStack.hasItemMeta() &&
                    itemStack.getItemMeta().getPersistentDataContainer().has(waylithItemKey, PersistentDataType.BYTE);

            // If the spawning item is NOT our custom NBT-tagged Waylith item, cancel/remove it instantly
            if (!isTagged) {
                event.setCancelled(true);
            }
        }
    }
}