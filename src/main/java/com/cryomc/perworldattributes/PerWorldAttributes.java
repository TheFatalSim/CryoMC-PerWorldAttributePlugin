package com.cryomc.perworldattributes;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class PerWorldAttributes extends JavaPlugin implements Listener {
    private static final String DEFAULT_MODIFIERS_MARKER = "__PWA_DEFAULT__";
    private static final String EMPTY_MODIFIERS_MARKER = "__PWA_EMPTY__";

    private final Map<UUID, Map<Attribute, Double>> baselineValues = new HashMap<>();
    private NamespacedKey modifierBackupKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        modifierBackupKey = new NamespacedKey(this, "original_attribute_modifiers");
        if (getCommand("pwa") != null) {
            getCommand("pwa").setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("perworldattributes.reload")) {
                    sender.sendMessage("§cYou don't have permission to do that.");
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    reloadConfig();
                    for (Player player : getServer().getOnlinePlayers()) {
                        applyWorldSettings(player);
                    }
                    sender.sendMessage("§aPerWorldAttributes configuration reloaded.");
                    return true;
                }
                sender.sendMessage("§eUsage: /pwa reload");
                return true;
            });
            getCommand("pwa").setTabCompleter((sender, command, alias, args) -> {
                if (!sender.hasPermission("perworldattributes.reload")) return java.util.Collections.emptyList();
                if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    return List.of("reload");
                }
                return java.util.Collections.emptyList();
            });
        }
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : getServer().getOnlinePlayers()) {
            applyWorldSettings(player);
        }
        getLogger().info("PerWorldAttributes enabled.");
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            processPlayerInventory(player, java.util.Collections.emptySet());
            Map<Attribute, Double> baseline = baselineValues.get(player.getUniqueId());
            if (baseline != null) {
                for (Map.Entry<Attribute, Double> entry : baseline.entrySet()) {
                    AttributeInstance instance = player.getAttribute(entry.getKey());
                    if (instance != null) instance.setBaseValue(entry.getValue());
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyWorldSettings(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        applyWorldSettings(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        getServer().getScheduler().runTask(this, () -> {
            if (event.getPlayer().isOnline()) applyWorldSettings(event.getPlayer());
        });
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleApply(player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) scheduleApply(player);
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        scheduleApply(event.getPlayer());
    }

    @EventHandler
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        scheduleApply(event.getPlayer());
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Item dropped = event.getItemDrop();
        getServer().getScheduler().runTask(this, () -> {
            if (dropped.isValid()) {
                ItemStack stack = dropped.getItemStack();
                processStack(stack, disabledModifierAttributes(player));
                dropped.setItemStack(stack);
            }
            if (player.isOnline()) applyWorldSettings(player);
        });
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player player)) return;
        Item dropped = event.getItem();
        getServer().getScheduler().runTask(this, () -> {
            if (dropped.isValid()) {
                ItemStack stack = dropped.getItemStack();
                processStack(stack, disabledModifierAttributes(player));
                dropped.setItemStack(stack);
            }
            if (player.isOnline()) applyWorldSettings(player);
        });
    }

    private void scheduleApply(Player player) {
        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) applyWorldSettings(player);
        });
    }

    private void applyWorldSettings(Player player) {
        captureBaselines(player);

        // Reset all attributes managed by this plugin first, so a value from the previous
        // world cannot leak into a world where that attribute is not configured.
        Map<Attribute, Double> baseline = baselineValues.get(player.getUniqueId());
        if (baseline != null) {
            for (Map.Entry<Attribute, Double> entry : baseline.entrySet()) {
                AttributeInstance instance = player.getAttribute(entry.getKey());
                if (instance != null) instance.setBaseValue(entry.getValue());
            }
        }

        String path = "worlds." + player.getWorld().getName();
        ConfigurationSection attributes = getConfig().getConfigurationSection(path + ".attributes");
        if (attributes != null) {
            for (String key : attributes.getKeys(false)) {
                Attribute attribute = resolveAttribute(key);
                if (attribute == null) {
                    getLogger().warning("Unknown attribute '" + key + "' in " + path);
                    continue;
                }
                double value = getConfig().getDouble(path + ".attributes." + key);
                AttributeInstance instance = player.getAttribute(attribute);
                if (instance != null && Double.isFinite(value) && value >= 0.0) {
                    instance.setBaseValue(value);
                } else {
                    getLogger().warning("Invalid value for " + path + ".attributes." + key);
                }
            }
        }

        processPlayerInventory(player, disabledModifierAttributes(player));
    }

    private void captureBaselines(Player player) {
        Map<Attribute, Double> baseline = baselineValues.computeIfAbsent(
                player.getUniqueId(), ignored -> new HashMap<>());
        for (Attribute attribute : configuredAttributes()) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) baseline.putIfAbsent(attribute, instance.getBaseValue());
        }
    }

    private Set<Attribute> configuredAttributes() {
        Set<Attribute> result = new java.util.LinkedHashSet<>();
        ConfigurationSection worlds = getConfig().getConfigurationSection("worlds");
        if (worlds == null) return result;
        for (String world : worlds.getKeys(false)) {
            ConfigurationSection section = getConfig().getConfigurationSection("worlds." + world + ".attributes");
            if (section == null) continue;
            for (String name : section.getKeys(false)) {
                Attribute attribute = resolveAttribute(name);
                if (attribute != null) result.add(attribute);
            }
        }
        return result;
    }

    private Set<Attribute> disabledModifierAttributes(Player player) {
        List<String> configured = getConfig().getStringList(
                "worlds." + player.getWorld().getName() + ".disabled-modifier-attributes");
        Set<Attribute> result = new java.util.LinkedHashSet<>();
        for (String name : configured) {
            Attribute attribute = resolveAttribute(name);
            if (attribute == null) {
                getLogger().warning("Unknown disabled modifier attribute '" + name + "' for world "
                        + player.getWorld().getName());
            } else {
                result.add(attribute);
            }
        }
        return result;
    }

    private void processPlayerInventory(Player player, Set<Attribute> disabledAttributes) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (processStack(stack, disabledAttributes)) inventory.setItem(slot, stack);
        }

        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir() && processStack(cursor, disabledAttributes)) {
            player.setItemOnCursor(cursor);
        }
    }

    /**
     * Suppresses explicit and vanilla default item attributes while preserving the original
     * modifier component on the item itself. The backup is stored in PDC so it survives a
     * restart, item drops, trades, and moving between worlds.
     */
    private boolean processStack(ItemStack stack, Set<Attribute> disabledAttributes) {
        if (stack == null || stack.getType().isAir()) return false;
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        boolean hasBackup = pdc.has(modifierBackupKey, PersistentDataType.STRING);

        // Rebuild the original modifier set first when this item was already processed in
        // another world, so changing worlds or reloading the config is reversible.
        String backup = hasBackup ? pdc.get(modifierBackupKey, PersistentDataType.STRING) : null;
        Multimap<Attribute, AttributeModifier> original;
        boolean originalWasDefault = false;
        if (hasBackup) {
            if (DEFAULT_MODIFIERS_MARKER.equals(backup)) {
                originalWasDefault = true;
                original = stack.getType().getDefaultAttributeModifiers();
            } else if (EMPTY_MODIFIERS_MARKER.equals(backup)) {
                original = ImmutableMultimap.of();
            } else {
                try {
                    original = deserializeModifiers(backup);
                } catch (Exception exception) {
                    getLogger().log(Level.WARNING, "Could not read saved attribute modifiers on "
                            + stack.getType() + "; leaving the item unchanged.", exception);
                    return false;
                }
            }
        } else {
            Multimap<Attribute, AttributeModifier> explicit = meta.getAttributeModifiers();
            if (explicit != null) {
                original = explicit;
            } else {
                originalWasDefault = true;
                original = stack.getType().getDefaultAttributeModifiers();
            }
        }

        if (disabledAttributes.isEmpty()) {
            if (!hasBackup) return false;
            restoreOriginalModifiers(meta, backup);
            meta.getPersistentDataContainer().remove(modifierBackupKey);
            stack.setItemMeta(meta);
            return true;
        }

        ImmutableMultimap.Builder<Attribute, AttributeModifier> filteredBuilder = ImmutableMultimap.builder();
        boolean removedAny = false;
        for (Map.Entry<Attribute, AttributeModifier> entry : original.entries()) {
            if (disabledAttributes.contains(entry.getKey())) {
                removedAny = true;
            } else {
                filteredBuilder.put(entry.getKey(), entry.getValue());
            }
        }
        if (!removedAny) {
            if (!hasBackup) return false;
            // The previous world's disabled attributes no longer apply to this item.
            restoreOriginalModifiers(meta, backup);
            meta.getPersistentDataContainer().remove(modifierBackupKey);
            stack.setItemMeta(meta);
            return true;
        }

        if (!hasBackup) {
            String saved = originalWasDefault ? DEFAULT_MODIFIERS_MARKER
                    : (original.isEmpty() ? EMPTY_MODIFIERS_MARKER : serializeModifiers(original));
            pdc.set(modifierBackupKey, PersistentDataType.STRING, saved);
        }
        meta.setAttributeModifiers(filteredBuilder.build());
        stack.setItemMeta(meta);
        return true;
    }

    private void restoreOriginalModifiers(ItemMeta meta, String backup) {
        try {
            if (DEFAULT_MODIFIERS_MARKER.equals(backup)) {
                meta.setAttributeModifiers(null);
            } else if (EMPTY_MODIFIERS_MARKER.equals(backup)) {
                meta.setAttributeModifiers(ImmutableMultimap.of());
            } else {
                meta.setAttributeModifiers(deserializeModifiers(backup));
            }
        } catch (InvalidConfigurationException exception) {
            throw new IllegalStateException("Invalid saved item attribute modifier data", exception);
        }
    }

    private String serializeModifiers(Multimap<Attribute, AttributeModifier> modifiers) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<Attribute, AttributeModifier> entry : modifiers.entries()) {
            AttributeModifier modifier = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attribute", entry.getKey().getKey().toString());
            row.put("key", modifier.getKey().toString());
            row.put("amount", modifier.getAmount());
            row.put("operation", modifier.getOperation().name());
            row.put("slot", modifier.getSlotGroup().toString());
            entries.add(row);
        }
        yaml.set("modifiers", entries);
        return yaml.saveToString();
    }

    private Multimap<Attribute, AttributeModifier> deserializeModifiers(String serialized)
            throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(serialized);
        ImmutableMultimap.Builder<Attribute, AttributeModifier> builder = ImmutableMultimap.builder();
        for (Map<?, ?> row : yaml.getMapList("modifiers")) {
            NamespacedKey attributeKey = NamespacedKey.fromString(String.valueOf(row.get("attribute")));
            NamespacedKey modifierKey = NamespacedKey.fromString(String.valueOf(row.get("key")));
            if (attributeKey == null || modifierKey == null) continue;
            Attribute attribute = Registry.ATTRIBUTE.get(attributeKey);
            if (attribute == null) continue;
            double amount = Double.parseDouble(String.valueOf(row.get("amount")));
            AttributeModifier.Operation operation = AttributeModifier.Operation.valueOf(
                    String.valueOf(row.get("operation")));
            String slotName = String.valueOf(row.get("slot"));
            EquipmentSlotGroup slotGroup = EquipmentSlotGroup.getByName(slotName);
            if (slotGroup == null) slotGroup = EquipmentSlotGroup.ANY;
            builder.put(attribute, new AttributeModifier(modifierKey, amount, operation, slotGroup));
        }
        return builder.build();
    }

    private Attribute resolveAttribute(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (normalized.startsWith("generic_")) normalized = normalized.substring("generic_".length());
        if (normalized.startsWith("player_")) normalized = normalized.substring("player_".length());
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        return key == null ? null : Registry.ATTRIBUTE.get(key);
    }
}
