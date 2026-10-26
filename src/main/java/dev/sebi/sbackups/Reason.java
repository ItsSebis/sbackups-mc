package dev.sebi.sbackups;

/** Why a backup was taken. The pool decides which retention limit applies. */
public enum Reason {
    PERIODIC(Pool.ROUTINE), JOIN(Pool.ROUTINE), QUIT(Pool.ROUTINE), WORLD_CHANGE(Pool.ROUTINE),
    DEATH(Pool.DEATH),
    MANUAL(Pool.MANUAL), PRE_RESTORE(Pool.MANUAL);

    public enum Pool { ROUTINE, DEATH, MANUAL }

    private final Pool pool;

    Reason(Pool pool) {
        this.pool = pool;
    }

    public Pool pool() {
        return pool;
    }

    /** Routine backups are skipped when nothing changed; the others are always kept. */
    public boolean dedupe() {
        return pool == Pool.ROUTINE;
    }

    /** Lower-case, dash-separated name used in file names and the API. */
    public String id() {
        return name().toLowerCase().replace('_', '-');
    }

    public static Reason fromId(String id) {
        for (Reason r : values()) if (r.id().equals(id)) return r;
        throw new IllegalArgumentException("Unknown reason: " + id);
    }
}
