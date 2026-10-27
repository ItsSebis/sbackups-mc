package dev.sebi.sbackups;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml, so a reload swaps all values at once. */
public record Settings(int periodicMinutes, boolean onJoin, boolean onQuit, boolean onDeath, boolean onWorldChange,
                       int maxRoutine, int routineMaxAgeDays, int maxDeath, int maxManual, long maxTotalBytes,
                       Sections restoreDefaults,
                       boolean webEnabled, String webBind, int webPort, String webToken,
                       boolean worldEnabled, String worldBackupCommand, String worldRestoreCommand, String worldRestoreDir) {

    static Settings from(FileConfiguration c) {
        return new Settings(
                c.getInt("triggers.periodic-minutes", 15),
                c.getBoolean("triggers.join", true),
                c.getBoolean("triggers.quit", true),
                c.getBoolean("triggers.death", true),
                c.getBoolean("triggers.world-change", true),
                c.getInt("retention.max-routine", 40),
                c.getInt("retention.routine-max-age-days", 14),
                c.getInt("retention.max-death", 20),
                c.getInt("retention.max-manual", 20),
                c.getLong("retention.max-total-size-mb", 2048) * 1024 * 1024,
                new Sections(
                        c.getBoolean("restore.include-main", true),
                        c.getBoolean("restore.include-armor", true),
                        c.getBoolean("restore.include-offhand", true),
                        c.getBoolean("restore.include-ender-chest", false),
                        c.getBoolean("restore.include-xp", false)),
                c.getBoolean("web.enabled", true),
                c.getString("web.bind-address", "127.0.0.1"),
                c.getInt("web.port", 8124),
                c.getString("web.token", ""),
                c.getBoolean("world.enabled", false),
                c.getString("world.backup-command", "/usr/local/bin/sbackups-world-backup"),
                c.getString("world.restore-command", "/usr/local/bin/sbackups-world-restore"),
                c.getString("world.restore-dir", "/srv/restored"));
    }

    int limit(Reason.Pool pool) {
        return switch (pool) {
            case ROUTINE -> maxRoutine;
            case DEATH -> maxDeath;
            case MANUAL -> maxManual;
        };
    }
}
