package dev.sebi.sbackups;

/** Which parts of a backup a restore touches. */
public record Sections(boolean main, boolean armor, boolean offhand, boolean enderChest, boolean xp) {

    public boolean any() {
        return main || armor || offhand || enderChest || xp;
    }
}
