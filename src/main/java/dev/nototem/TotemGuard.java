package dev.nototem;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Totems of Undying can't be dropped, picked up, carried, stored or used.
 * Everything is event driven (no repeating tasks) and every part can be switched on or off in config.yml.
 */
final class TotemGuard implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final List<String> SUBS = List.of("on", "off", "reload", "status", "purge");

    private final JavaPlugin plugin;

    TotemGuard(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    boolean isEnabled() {
        return plugin.getConfig().getBoolean("enabled", true);
    }

    /** True when the whole system is enabled and this particular option is on. */
    private boolean on(String key) {
        FileConfiguration c = plugin.getConfig();
        return c.getBoolean("enabled", true) && c.getBoolean(key, true);
    }

    private boolean pickupOrStorage() {
        FileConfiguration c = plugin.getConfig();
        return c.getBoolean("enabled", true)
                && (c.getBoolean("prevent-pickup", true) || c.getBoolean("prevent-storage", true));
    }

    // ------------------------------------------------------------------
    // Item helpers
    // ------------------------------------------------------------------

    static boolean isTotem(ItemStack stack) {
        return stack != null && stack.getType() == Material.TOTEM_OF_UNDYING;
    }

    /** Shulker boxes and bundles can hold other items. */
    private static boolean canHoldItems(ItemStack stack) {
        String n = stack.getType().name();
        return n.endsWith("SHULKER_BOX") || n.equals("BUNDLE") || n.endsWith("_BUNDLE");
    }

    /** Removes totems from inside a shulker box or bundle item. Returns how many were removed. */
    static int stripNested(ItemStack stack) {
        if (stack == null || !canHoldItems(stack)) return 0;
        ItemMeta meta = stack.getItemMeta();
        int removed = 0;
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
            removed = strip(box.getInventory());
            if (removed > 0) {
                bsm.setBlockState(box);
                stack.setItemMeta(bsm);
            }
        } else if (meta instanceof BundleMeta bundle) {
            List<ItemStack> keep = new ArrayList<>();
            for (ItemStack inside : bundle.getItems()) {
                if (isTotem(inside)) {
                    removed += inside.getAmount();
                } else {
                    keep.add(inside);
                }
            }
            if (removed > 0) {
                bundle.setItems(keep);
                stack.setItemMeta(bundle);
            }
        }
        return removed;
    }

    /** Removes every totem (also inside shulker boxes and bundles) from an inventory. */
    static int strip(Inventory inv) {
        if (inv == null) return 0;
        int removed = 0;
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null) continue;
            if (isTotem(stack)) {
                removed += stack.getAmount();
                inv.setItem(i, null);
            } else {
                int nested = stripNested(stack);
                if (nested > 0) {
                    removed += nested;
                    inv.setItem(i, stack);
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private void notifyRemoved(Player player, int amount) {
        FileConfiguration c = plugin.getConfig();
        String prefix = c.getString("messages.prefix", "&8[&cNoTotem&8] ");
        if (c.getBoolean("notify-player", true)) {
            player.sendMessage(LEGACY.deserialize(prefix
                    + c.getString("messages.totem-removed", "&cTotems of Undying are disabled on this server.")));
        }
        if (c.getBoolean("notify-admins", false)) {
            Bukkit.broadcast(LEGACY.deserialize(prefix + "&7Removed " + amount + "x totem from &e" + player.getName()),
                    "nototem.admin");
        }
    }

    // ------------------------------------------------------------------
    // Drops
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (!on("prevent-drops")) return;
        event.getDrops().removeIf(TotemGuard::isTotem);
    }

    @EventHandler
    public void onLoot(LootGenerateEvent event) {
        if (!on("prevent-drops")) return;
        event.getLoot().removeIf(TotemGuard::isTotem);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!on("prevent-drops")) return;
        if (isTotem(event.getEntity().getItemStack())) event.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Pickup
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!on("prevent-pickup")) return;

        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (isTotem(stack)) {
            event.setCancelled(true);
            item.remove();
            notifyRemoved(player, stack.getAmount());
            return;
        }
        int nested = stripNested(stack);
        if (nested > 0) {
            item.setItemStack(stack);
            notifyRemoved(player, nested);
        }
    }

    // ------------------------------------------------------------------
    // Joining, changing world, respawning
    // ------------------------------------------------------------------

    private void scan(Player player) {
        if (!on("remove-on-join")) return;
        int removed = strip(player.getInventory());
        if (on("remove-existing")) removed += strip(player.getEnderChest());
        if (removed > 0) notifyRemoved(player, removed);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scan(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        scan(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) scan(player);
        });
    }

    // ------------------------------------------------------------------
    // Inventory interaction
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!pickupOrStorage()) return;
        Player player = event.getWhoClicked() instanceof Player p ? p : null;
        int removed = 0;

        ItemStack current = event.getCurrentItem();
        if (isTotem(current)) {
            removed += current.getAmount();
            event.setCurrentItem(null);
        } else if (current != null) {
            int nested = stripNested(current);
            if (nested > 0) {
                removed += nested;
                event.setCurrentItem(current);
            }
        }

        ItemStack cursor = event.getCursor();
        if (isTotem(cursor)) {
            removed += cursor.getAmount();
            event.getWhoClicked().setItemOnCursor(null);
        } else if (cursor != null && !cursor.getType().isAir()) {
            int nested = stripNested(cursor);
            if (nested > 0) {
                removed += nested;
                event.getWhoClicked().setItemOnCursor(cursor);
            }
        }

        if (player != null) {
            PlayerInventory inv = player.getInventory();
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int button = event.getHotbarButton();
                if (button >= 0 && isTotem(inv.getItem(button))) {
                    removed += inv.getItem(button).getAmount();
                    inv.setItem(button, null);
                }
            } else if (event.getClick() == ClickType.SWAP_OFFHAND && isTotem(inv.getItemInOffHand())) {
                removed += inv.getItemInOffHand().getAmount();
                inv.setItemInOffHand(null);
            }
        }

        if (removed > 0) {
            event.setCancelled(true);
            if (player != null) notifyRemoved(player, removed);
        }
    }

    /** Creative mode grabbing a totem out of the item menu. */
    @EventHandler(ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!on("prevent-pickup")) return;
        if (isTotem(event.getCursor()) || isTotem(event.getCurrentItem())) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) notifyRemoved(player, 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!pickupOrStorage()) return;
        if (isTotem(event.getOldCursor())) {
            event.setCancelled(true);
            event.getWhoClicked().setItemOnCursor(null);
            if (event.getWhoClicked() instanceof Player player) notifyRemoved(player, 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!pickupOrStorage()) return;
        Player player = event.getPlayer();
        PlayerInventory inv = player.getInventory();
        int removed = 0;
        if (isTotem(inv.getItemInMainHand())) {
            removed += inv.getItemInMainHand().getAmount();
            inv.setItemInMainHand(null);
        }
        if (isTotem(inv.getItemInOffHand())) {
            removed += inv.getItemInOffHand().getAmount();
            inv.setItemInOffHand(null);
        }
        if (removed > 0) {
            event.setCancelled(true);
            notifyRemoved(player, removed);
        }
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /** Hoppers and droppers can't move totems (also not inside shulker boxes or bundles). */
    @EventHandler(ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent event) {
        if (!on("prevent-storage")) return;
        ItemStack stack = event.getItem();
        if (isTotem(stack)) {
            event.setCancelled(true);
        } else if (canHoldItems(stack) && stripNested(stack.clone()) > 0) {
            event.setCancelled(true);
        }
    }

    /** Totems that were stored before the plugin was installed disappear when the container is opened. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!on("remove-existing")) return;
        int removed = strip(event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            removed += strip(player.getInventory());
            if (removed > 0) notifyRemoved(player, removed);
        }
    }

    // ------------------------------------------------------------------
    // Using a totem
    // ------------------------------------------------------------------

    /** Safety net: even if someone ends up with a totem (creative, /give), it can't save them. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!on("block-use")) return;
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }

    // ------------------------------------------------------------------
    // Purge
    // ------------------------------------------------------------------

    private int purge() {
        int removed = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            removed += strip(player.getInventory());
            removed += strip(player.getEnderChest());
        }
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Container container) {
                        removed += strip(container.getInventory());
                    }
                }
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Item item) {
                        ItemStack stack = item.getItemStack();
                        if (isTotem(stack)) {
                            removed += stack.getAmount();
                            item.remove();
                        } else {
                            int nested = stripNested(stack);
                            if (nested > 0) {
                                removed += nested;
                                item.setItemStack(stack);
                            }
                        }
                    } else if (entity instanceof InventoryHolder holder && !(entity instanceof Player)) {
                        removed += strip(holder.getInventory());
                    }
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    private static String flag(boolean value) {
        return value ? "&2ON" : "&cOFF";
    }

    private void status(CommandSender sender) {
        FileConfiguration c = plugin.getConfig();
        sender.sendMessage(LEGACY.deserialize("&6NoTotem is " + flag(isEnabled())));
        String[] keys = {"prevent-drops", "remove-on-join", "prevent-pickup", "prevent-storage",
                "remove-existing", "block-use", "notify-player", "notify-admins"};
        for (String key : keys) {
            sender.sendMessage(LEGACY.deserialize("&7" + key + ": " + flag(c.getBoolean(key, true))));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nototem.admin")) {
            sender.sendMessage(LEGACY.deserialize("&cYou don't have permission."));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "on", "off" -> {
                boolean value = sub.equals("on");
                plugin.getConfig().set("enabled", value);
                plugin.saveConfig();
                sender.sendMessage(LEGACY.deserialize("&aNoTotem is now " + flag(value)));
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(LEGACY.deserialize("&aNoTotem settings reloaded."));
            }
            case "status" -> status(sender);
            case "purge" -> {
                int removed = purge();
                sender.sendMessage(LEGACY.deserialize(removed > 0
                        ? "&aRemoved &e" + removed + " &atotem(s) from online players, loaded containers and the ground."
                        : "&7No totems found."));
            }
            case "" -> {
                status(sender);
                sender.sendMessage(LEGACY.deserialize("&e/nototem on|off|reload|status|purge"));
            }
            default -> sender.sendMessage(LEGACY.deserialize("&cUsage: /nototem on|off|reload|status|purge"));
        }
        return true;
    }

    List<String> tabSubs(String typed) {
        List<String> out = new ArrayList<>();
        for (String s : SUBS) {
            if (s.startsWith(typed.toLowerCase())) out.add(s);
        }
        return out;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("nototem.admin") || args.length != 1) return List.of();
        return tabSubs(args[0]);
    }
}
