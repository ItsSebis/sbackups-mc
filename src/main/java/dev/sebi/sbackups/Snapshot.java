package dev.sebi.sbackups;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The backed-up state of a player. Armor and offhand are kept as separate sections, never merged into main.
 * Items are held as Paper's version-aware serialized bytes; {@link #capture} must run on the main thread.
 */
public record Snapshot(byte[] main, byte[] armor, byte[] offhand, byte[] enderChest, int xpLevel, float xpProgress,
                       int itemCount) {

    public static final int MAIN_SIZE = 36, ARMOR_SIZE = 4, ENDER_SIZE = 27;

    public static Snapshot capture(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] main = inv.getStorageContents();
        ItemStack[] armor = inv.getArmorContents();
        ItemStack[] off = {inv.getItemInOffHand()};
        ItemStack[] ender = p.getEnderChest().getContents();
        int count = count(main) + count(armor) + count(off) + count(ender);
        return new Snapshot(pack(main), pack(armor), pack(off), pack(ender), p.getLevel(), p.getExp(), count);
    }

    private static int count(ItemStack[] items) {
        int n = 0;
        for (ItemStack i : items) if (i != null && !i.getType().isAir()) n++;
        return n;
    }

    private static byte[] pack(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? new ItemStack(Material.AIR) : items[i];
        }
        return ItemStack.serializeItemsAsBytes(copy);
    }

    /** Items of a section with empty slots as null. Main thread only. */
    public static ItemStack[] unpack(byte[] data, int size) {
        ItemStack[] raw = ItemStack.deserializeItemsFromBytes(data);
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size && i < raw.length; i++) {
            out[i] = raw[i] == null || raw[i].getType().isAir() ? null : raw[i];
        }
        return out;
    }

    public String hash() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] part : new byte[][]{main, armor, offhand, enderChest}) {
                md.update((byte) (part.length >> 24));
                md.update((byte) (part.length >> 16));
                md.update((byte) (part.length >> 8));
                md.update((byte) part.length);
                md.update(part);
            }
            md.update((byte) xpLevel);
            md.update((byte) (xpLevel >> 8));
            return HexFormat.of().formatHex(md.digest(), 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void write(DataOutputStream out) throws IOException {
        for (byte[] part : new byte[][]{main, armor, offhand, enderChest}) {
            out.writeInt(part.length);
            out.write(part);
        }
    }

    static Snapshot read(DataInputStream in, Meta meta) throws IOException {
        byte[][] parts = new byte[4][];
        for (int i = 0; i < 4; i++) {
            int len = in.readInt();
            if (len < 0 || len > 64 * 1024 * 1024) throw new IOException("Corrupt backup section length " + len);
            parts[i] = in.readNBytes(len);
            if (parts[i].length != len) throw new IOException("Truncated backup");
        }
        return new Snapshot(parts[0], parts[1], parts[2], parts[3], meta.xpLevel(), meta.xpProgress(), meta.itemCount());
    }

}
