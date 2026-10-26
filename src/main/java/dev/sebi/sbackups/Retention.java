package dev.sebi.sbackups;

import java.util.ArrayList;
import java.util.List;

/** Decides which backups of one player fall out of the retention limits. Pure, so it is unit tested. */
final class Retention {
    private Retention() {}

    /** @param newestFirst all backups of the player, newest first */
    static List<Meta> expired(List<Meta> newestFirst, Settings s, long now) {
        List<Meta> out = new ArrayList<>();
        int[] kept = new int[Reason.Pool.values().length];
        long maxAge = s.routineMaxAgeDays() > 0 ? s.routineMaxAgeDays() * 86_400_000L : Long.MAX_VALUE;
        for (Meta m : newestFirst) {
            Reason.Pool pool = m.reasonEnum().pool();
            boolean tooOld = pool == Reason.Pool.ROUTINE && now - m.time() > maxAge;
            if (tooOld || kept[pool.ordinal()] >= Math.max(1, s.limit(pool))) out.add(m);
            else kept[pool.ordinal()]++;
        }
        return out;
    }
}
