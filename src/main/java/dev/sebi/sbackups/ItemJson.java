package dev.sebi.sbackups;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;

/** Turns items into plain JSON for the web interface. Main thread only. */
final class ItemJson {
    private static final java.util.Set<String> HANDLED = java.util.Set.of("lore", "custom_name", "item_name",
            "enchantments", "stored_enchantments", "damage", "max_damage", "max_stack_size", "potion_contents",
            "attribute_modifiers", "rarity", "repair_cost", "tooltip_display", "item_model", "enchantable");

    private ItemJson() {}

    static Map<String, Object> sections(Snapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", items(Snapshot.unpack(s.main(), Snapshot.MAIN_SIZE)));
        out.put("armor", items(Snapshot.unpack(s.armor(), Snapshot.ARMOR_SIZE))); // boots, leggings, chestplate, helmet
        out.put("offhand", items(Snapshot.unpack(s.offhand(), 1)));
        out.put("ender", items(Snapshot.unpack(s.enderChest(), Snapshot.ENDER_SIZE)));
        out.put("xpLevel", s.xpLevel());
        out.put("xpProgress", s.xpProgress());
        return out;
    }

    private static List<Map<String, Object>> items(ItemStack[] stacks) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ItemStack i : stacks) out.add(i == null ? null : item(i));
        return out;
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(GlobalTranslator.render(c, Locale.ENGLISH));
    }

    static Map<String, Object> item(ItemStack i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", i.getType().getKey().getKey());
        m.put("amount", i.getAmount());
        m.put("name", plain(i.effectiveName()));
        List<Component> lore = i.lore();
        if (lore != null && !lore.isEmpty()) m.put("lore", lore.stream().map(ItemJson::plain).toList());
        if (!i.getEnchantments().isEmpty()) {
            Map<String, Integer> ench = new LinkedHashMap<>();
            i.getEnchantments().forEach((e, lvl) -> ench.put(e.getKey().getKey(), lvl));
            m.put("enchants", ench);
        }
        if (i.hasItemMeta()) {
            var meta = i.getItemMeta();
            if (meta instanceof Damageable d && d.getDamage() > 0) {
                m.put("damage", d.getDamage());
                m.put("maxDamage", i.getType().getMaxDurability());
            }
            if (meta instanceof EnchantmentStorageMeta book && book.hasStoredEnchants()) {
                Map<String, Integer> stored = new LinkedHashMap<>();
                book.getStoredEnchants().forEach((e, lvl) -> stored.put(e.getKey().getKey(), lvl));
                m.put("storedEnchants", stored);
            }
            if (meta instanceof PotionMeta pot && pot.hasBasePotionType()) {
                m.put("potion", pot.getBasePotionType().getKey().getKey());
            }
        }
        // Everything else on the item (custom data, attributes, container contents, ...) is kept by the
        // serialization; list the component names so the admin can see that there is more data. Only components
        // set on this stack count: the item type's built-in defaults (attack_animation, break_sound, ...) are noise.
        List<String> extra = i.getDataTypes().stream().filter(i::isDataOverridden).map(t -> t.getKey().getKey())
                .filter(k -> !HANDLED.contains(k)).sorted().toList();
        if (!extra.isEmpty()) m.put("components", extra);
        return m;
    }
}
