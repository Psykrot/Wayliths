package com.kotacraft.wayliths;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.connection.PlayerGameConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TpaManager implements Listener {
    private static final int PLAYER_PAGE_SIZE = 12;
    private static final List<String> SORT_MODES = List.of("alphabetical", "same_world", "nearby");

    private final Wayliths plugin;
    private final Map<UUID, TpaRequest> requestsByTarget = new HashMap<>();
    private final Map<UUID, TpaRequest> requestsByRequester = new HashMap<>();
    private final Map<UUID, String> playerSortModes = new HashMap<>();

    public TpaManager(Wayliths plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!plugin.getScrollManager().isTpaScroll(item)) return;

        event.setCancelled(true);
        if (!plugin.isItemEnabled("tpa-scroll")) {
            event.getPlayer().sendMessage(plugin.getLangMessage("item-disabled", null, null));
            return;
        }
        openPlayerDialog(event.getPlayer(), 0,
                playerSortModes.computeIfAbsent(event.getPlayer().getUniqueId(), ignored -> "alphabetical"));
    }

    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent event) {
        Key action = event.getIdentifier();
        if (!action.namespace().equals("wayliths") || !action.value().startsWith("tpa/")) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;

        Player viewer = connection.getPlayer();
        if (!plugin.isItemEnabled("tpa-scroll")) {
            viewer.sendMessage(plugin.getLangMessage("item-disabled", null, null));
            return;
        }

        String[] parts = action.value().split("/");
        if (parts.length < 2) return;
        switch (parts[1]) {
            case "player" -> {
                if (parts.length != 5) return;
                UUID targetId = parseUuid(parts[2]);
                String sortMode = parts[3];
                int page = parsePage(parts[4]);
                if (targetId == null || !SORT_MODES.contains(sortMode) || page < 0) return;
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || target.equals(viewer)) {
                    viewer.sendMessage(plugin.getLangMessage("tpa-player-offline", null, null));
                    openPlayerDialog(viewer, page, sortMode);
                    return;
                }
                if (!sendTpaRequest(viewer, target)) {
                    openPlayerDialog(viewer, page, sortMode);
                }
            }
            case "page" -> {
                if (parts.length != 4 || !SORT_MODES.contains(parts[2])) return;
                int page = parsePage(parts[3]);
                if (page >= 0) openPlayerDialog(viewer, page, parts[2]);
            }
            case "sort" -> {
                if (parts.length != 4 || !SORT_MODES.contains(parts[2])) return;
                if (parsePage(parts[3]) >= 0) openPlayerDialog(viewer, 0, parts[2]);
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        playerSortModes.remove(playerId);
        TpaRequest outgoing = requestsByRequester.get(playerId);
        if (outgoing != null) removeRequest(outgoing);

        TpaRequest incoming = requestsByTarget.get(playerId);
        if (incoming != null) {
            Player requester = Bukkit.getPlayer(incoming.requesterId);
            removeRequest(incoming);
            if (requester != null) {
                requester.sendMessage(plugin.getLangMessage("tpa-target-offline", null, null));
            }
        }
    }

    private void handleRequestClick(Player target, TpaRequest request, boolean approve) {
        if (requestsByTarget.get(target.getUniqueId()) != request) {
            target.sendMessage(plugin.getLangMessage("tpa-request-expired", null, null));
            return;
        }

        Player requester = Bukkit.getPlayer(request.requesterId);
        if (requester == null) {
            removeRequest(request);
            target.sendMessage(plugin.getLangMessage("tpa-requester-offline", null, null));
            return;
        }

        if (approve) {
            WaylithListener teleports = plugin.getWaylithListener();
            if (teleports.isTeleportUnavailable(requester)) {
                target.sendMessage(plugin.getLangMessage("tpa-teleport-unavailable", "{player}", requester.getName()));
                return;
            }
            removeRequest(request);
            requester.sendMessage(plugin.getLangMessage("tpa-request-approved", "{player}", target.getName()));
            teleports.scheduleTeleport(requester, target.getLocation().clone(),
                    plugin.getLangMessage("tpa-teleport-completed", "{player}", target.getName()),
                    () -> requester.isOnline() && target.isOnline(),
                    () -> consumeTpaScroll(requester),
                    "tpa-scroll",
                    plugin.getConfig().getBoolean("tpa.cancel-on-move", false));
            target.sendMessage(plugin.getLangMessage("tpa-approved", "{player}", requester.getName()));
        } else {
            removeRequest(request);
            requester.sendMessage(plugin.getLangMessage("tpa-request-denied", "{player}", target.getName()));
            target.sendMessage(plugin.getLangMessage("tpa-denied", "{player}", requester.getName()));
        }
    }

    private void openPlayerDialog(Player viewer, int page, String sortMode) {
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.remove(viewer);
        if (!SORT_MODES.contains(sortMode)) sortMode = "alphabetical";
        playerSortModes.put(viewer.getUniqueId(), sortMode);
        sortPlayers(players, viewer, sortMode);

        int totalPages = Math.max(1, (players.size() + PLAYER_PAGE_SIZE - 1) / PLAYER_PAGE_SIZE);
        int currentPage = Math.clamp(page, 0, totalPages - 1);
        int firstIndex = currentPage * PLAYER_PAGE_SIZE;
        int lastIndex = Math.min(firstIndex + PLAYER_PAGE_SIZE, players.size());
        List<ActionButton> actions = new ArrayList<>();
        List<DialogBody> bodies = new ArrayList<>();
        if (players.isEmpty()) {
            bodies.add(DialogBody.plainMessage(
                    plugin.getLangMessageWithoutPrefix("tpa-dialog-empty", null, null)));
        } else {
            bodies.add(DialogBody.plainMessage(
                    plugin.getLangMessageWithoutPrefix("tpa-dialog-page", "{page}",
                            (currentPage + 1) + " / " + totalPages)));
        }
        String nextSortMode = SORT_MODES.get((SORT_MODES.indexOf(sortMode) + 1) % SORT_MODES.size());
        String sortLabelKey = switch (sortMode) {
            case "same_world" -> "tpa-dialog-sort-same-world";
            case "nearby" -> "tpa-dialog-sort-nearby";
            default -> "tpa-dialog-sort-alphabetical";
        };
        String sortLabel = MiniMessage.miniMessage().serialize(
                plugin.getLangMessageWithoutPrefix(sortLabelKey, null, null));

        ActionButton sortButton = dialogButton(
                plugin.getLangMessageWithoutPrefix("tpa-dialog-sort-button", "{sort}", sortLabel),
                plugin.getLangMessageWithoutPrefix("tpa-dialog-sort-tooltip", null, null),
                tpaAction("sort/" + nextSortMode + "/0"),
                300);
        actions.add(sortButton);

        for (int index = firstIndex; index < lastIndex; index++) {
            Player target = players.get(index);
            Component tooltip = plugin.getLangMessageWithoutPrefix(
                            "tpa-dialog-player-tooltip", null, null)
                    .append(target.displayName()
                            .colorIfAbsent(NamedTextColor.WHITE)
                            .decorate(TextDecoration.BOLD));
            actions.add(dialogButton(
                    target.displayName().colorIfAbsent(NamedTextColor.WHITE),
                    tooltip,
                    tpaAction("player/" + target.getUniqueId() + "/" + sortMode + "/" + currentPage),
                    300));
        }

        if (currentPage > 0) {
            actions.add(dialogButton(
                    plugin.getLangMessageWithoutPrefix("gui-previous-page", null, null),
                    null,
                    tpaAction("page/" + sortMode + "/" + (currentPage - 1)),
                    300));
        }
        if (currentPage + 1 < totalPages) {
            actions.add(dialogButton(
                    plugin.getLangMessageWithoutPrefix("gui-next-page", null, null),
                    null,
                    tpaAction("page/" + sortMode + "/" + (currentPage + 1)),
                    300));
        }
        ActionButton closeButton = dialogButton(
                plugin.getLangMessageWithoutPrefix("tpa-dialog-close", null, null),
                tpaAction("close"));

        DialogBase base = DialogBase.builder(plugin.getLangMessageWithoutPrefix(
                        "tpa-dialog-title", null, null))
                .canCloseWithEscape(true)
                .afterAction(DialogAfterAction.CLOSE)
                .body(bodies)
                .build();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.multiAction(actions, closeButton, 1)));
        viewer.showDialog(dialog);
    }

    private ActionButton dialogButton(Component label, Key action) {
        return dialogButton(label, null, action, 150);
    }

    private ActionButton dialogButton(Component label, Component tooltip, Key action, int width) {
        return ActionButton.builder(label.decoration(TextDecoration.ITALIC, false))
                .tooltip(tooltip == null ? null : tooltip.decoration(TextDecoration.ITALIC, false))
                .width(width)
                .action(action == null ? null : DialogAction.customClick(action, null))
                .build();
    }

    private Key tpaAction(String action) {
        //noinspection PatternValidation
        return Key.key("wayliths", "tpa/" + action);
    }

    private int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return page >= 0 ? page : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean sendTpaRequest(Player player, Player target) {
        UUID targetId = target.getUniqueId();
        if (target.equals(player)) return false;
        if (requestsByRequester.containsKey(player.getUniqueId())) {
            player.sendMessage(plugin.getLangMessage("tpa-already-pending", null, null));
            return false;
        }
        if (requestsByTarget.containsKey(targetId)) {
            player.sendMessage(plugin.getLangMessage("tpa-target-busy", "{player}", target.getName()));
            return false;
        }
        if (plugin.getWaylithListener().isTeleportUnavailable(player)) return false;
        if (!hasTpaScroll(player)) {
            player.sendMessage(plugin.getLangMessage("tpa-scroll-required", null, null));
            return false;
        }

        TpaRequest request = new TpaRequest(player.getUniqueId(), targetId);
        requestsByTarget.put(targetId, request);
        requestsByRequester.put(player.getUniqueId(), request);
        player.sendMessage(plugin.getLangMessage("tpa-request-sent", "{player}", target.getName()));
        sendRequestMessage(target, player, request);

        long timeoutTicks = Math.max(1L, plugin.getConfig().getLong("tpa.request-timeout-seconds", 30L)) * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> expireRequest(request), timeoutTicks);
        return true;
    }

    private void sortPlayers(List<Player> players, Player viewer, String sortMode) {
        if (sortMode.equals("alphabetical")) {
            players.sort((first, second) -> first.getName().compareToIgnoreCase(second.getName()));
        } else if (sortMode.equals("same_world")) {
            players.sort((first, second) -> {
                boolean firstSameWorld = first.getWorld().equals(viewer.getWorld());
                boolean secondSameWorld = second.getWorld().equals(viewer.getWorld());
                if (firstSameWorld != secondSameWorld) return firstSameWorld ? -1 : 1;
                return first.getName().compareToIgnoreCase(second.getName());
            });
        } else {
            Location viewerLocation = viewer.getLocation();
            players.sort((first, second) -> {
                boolean firstSameWorld = first.getWorld().equals(viewerLocation.getWorld());
                boolean secondSameWorld = second.getWorld().equals(viewerLocation.getWorld());
                if (firstSameWorld != secondSameWorld) return firstSameWorld ? -1 : 1;
                if (firstSameWorld) {
                    int distance = Double.compare(
                            first.getLocation().distanceSquared(viewerLocation),
                            second.getLocation().distanceSquared(viewerLocation));
                    if (distance != 0) return distance;
                }
                return first.getName().compareToIgnoreCase(second.getName());
            });
        }
    }

    private void sendRequestMessage(Player target, Player requester, TpaRequest request) {
        Component message = plugin.getLangMessage("tpa-request-received", "{player}", requester.getName())
                .append(Component.newline())
                .append(plugin.getLangMessageWithoutPrefix("tpa-request-approve-button", null, null)
                        .clickEvent(ClickEvent.callback(audience -> {
                            if (audience instanceof Player clicker) {
                                handleRequestClick(clicker, request, true);
                            }
                        })))
                .append(Component.newline())
                .append(plugin.getLangMessageWithoutPrefix("tpa-request-deny-button", null, null)
                        .clickEvent(ClickEvent.callback(audience -> {
                            if (audience instanceof Player clicker) {
                                handleRequestClick(clicker, request, false);
                            }
                        })));
        target.sendMessage(message);
    }

    private boolean hasTpaScroll(Player player) {
        if (!plugin.isItemEnabled("tpa-scroll")) return false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (plugin.getScrollManager().isTpaScroll(item)) return true;
        }
        return plugin.getScrollManager().isTpaScroll(player.getInventory().getItemInOffHand());
    }

    private void consumeTpaScroll(Player player) {
        if (!plugin.isItemEnabled("tpa-scroll")) return;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && plugin.getScrollManager().isTpaScroll(item)) {
                item.setAmount(item.getAmount() - 1);
                return;
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (plugin.getScrollManager().isTpaScroll(offHand)) {
            offHand.setAmount(offHand.getAmount() - 1);
        }
    }

    private void expireRequest(TpaRequest request) {
        if (requestsByTarget.get(request.targetId) != request) return;
        Player requester = Bukkit.getPlayer(request.requesterId);
        Player target = Bukkit.getPlayer(request.targetId);
        removeRequest(request);
        if (requester != null) requester.sendMessage(plugin.getLangMessage("tpa-request-expired", null, null));
        if (target != null) target.sendMessage(plugin.getLangMessage("tpa-request-expired", null, null));
    }

    private void removeRequest(TpaRequest request) {
        requestsByTarget.remove(request.targetId, request);
        requestsByRequester.remove(request.requesterId, request);
    }

    private record TpaRequest(UUID requesterId, UUID targetId) {
    }

}
