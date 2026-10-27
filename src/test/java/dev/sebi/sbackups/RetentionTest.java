package dev.sebi.sbackups;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionTest {
    private static final long DAY = 86_400_000L;
    private static final long NOW = 100 * DAY;

    private static Settings settings(int routine, int ageDays, int death, int manual) {
        Sections s = new Sections(true, true, true, false, false);
        return new Settings(15, true, true, true, true, routine, ageDays, death, manual, 0, s, false, "", 0, "", false, "", "", "");
    }

    private static Meta meta(long time, Reason r) {
        return new Meta(time + "-" + r.id(), time, r.id(), "world", 0, 0, 0, "h" + time, 1, 0, 0, 1);
    }

    @Test
    void routineBackupsDoNotEvictDeathBackups() {
        List<Meta> newestFirst = new ArrayList<>();
        newestFirst.add(meta(NOW - 1, Reason.PERIODIC));
        newestFirst.add(meta(NOW - 2, Reason.PERIODIC));
        newestFirst.add(meta(NOW - 3, Reason.DEATH));
        newestFirst.add(meta(NOW - 4, Reason.PERIODIC));
        List<Meta> gone = Retention.expired(newestFirst, settings(2, 0, 5, 5), NOW);
        assertEquals(1, gone.size());
        assertEquals(NOW - 4, gone.get(0).time());
    }

    @Test
    void routineOlderThanMaxAgeExpiresButDeathDoesNot() {
        List<Meta> newestFirst = List.of(meta(NOW - 1, Reason.JOIN), meta(NOW - 20 * DAY, Reason.QUIT),
                meta(NOW - 30 * DAY, Reason.DEATH));
        List<Meta> gone = Retention.expired(newestFirst, settings(40, 14, 20, 20), NOW);
        assertEquals(1, gone.size());
        assertTrue(gone.get(0).id().endsWith("quit"));
    }

    @Test
    void manualAndPreRestoreShareOnePool() {
        List<Meta> newestFirst = List.of(meta(NOW - 1, Reason.MANUAL), meta(NOW - 2, Reason.PRE_RESTORE),
                meta(NOW - 3, Reason.MANUAL));
        assertEquals(1, Retention.expired(newestFirst, settings(40, 0, 20, 2), NOW).size());
    }
}
