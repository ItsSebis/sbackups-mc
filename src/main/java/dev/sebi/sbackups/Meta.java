package dev.sebi.sbackups;

/** Header of a backup file: everything needed to list it without loading the items. */
public record Meta(String id, long time, String reason, String world, double x, double y, double z,
                   String hash, int itemCount, int xpLevel, float xpProgress, long size) {

    public Reason reasonEnum() {
        return Reason.fromId(reason);
    }

    Meta withSize(long newSize) {
        return new Meta(id, time, reason, world, x, y, z, hash, itemCount, xpLevel, xpProgress, newSize);
    }
}
