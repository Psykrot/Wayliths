package com.kotacraft.wayliths;

import com.nexomc.nexo.api.events.custom_block.NexoBlockBreakEvent;
import com.nexomc.nexo.api.events.furniture.NexoFurnitureBreakEvent;
import com.nexomc.nexo.utils.drops.Drop;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class NexoWaylithListener implements Listener {

    private final Wayliths plugin;
    private final WaylithManager manager;

    public NexoWaylithListener(Wayliths plugin) {
        this.plugin = plugin;
        this.manager = plugin.getWaylithManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNexoFurnitureBreak(NexoFurnitureBreakEvent event) {
        var location = event.getBaseEntity().getLocation();
        if (!manager.isWaylithBlock(location)) return;

        event.setDrop(Drop.emptyDrop());
        plugin.getWaylithListener().handleWaylithBreak(location, event.getPlayer(), false, true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNexoBlockBreak(NexoBlockBreakEvent event) {
        var location = event.getBlock().getLocation();
        if (!manager.isWaylithBlock(location)) return;

        event.setDrop(Drop.emptyDrop());
        plugin.getWaylithListener().handleWaylithBreak(location, event.getPlayer(), false, true);
    }
}
