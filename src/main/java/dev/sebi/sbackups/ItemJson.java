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

/** Turns items into plain JSON for the web interface. Main thread only. */
final class ItemJson {
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
        if (i.hasItemMeta() && i.getItemMeta() instanceof Damageable d && d.getDamage() > 0) {
            m.put("damage", d.getDamage());
            m.put("maxDamage", i.getType().getMaxDurability());
        }
        return m;
    }
}
