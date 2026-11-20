package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.Test;

public class PrayerTimesTest {
    private static final TimeZone MY = TimeZone.getTimeZone("Asia/Kuala_Lumpur");

    private static String format(long[] times) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm");
        f.setTimeZone(MY);
        StringBuilder s = new StringBuilder();
        for (long t : times) s.append(s.length() == 0 ? "" : " ").append(f.format(new Date(t)));
        return s.toString();
    }

    /** Nilai, Negeri Sembilan, 21 Nov 2026: JAKIM's times for zone NGS03 are 05:48 12:59 16:23 18:59 20:12. */
    @Test
    public void nilaiMatchesJakim() {
        assertEquals("05:47 12:59 16:23 18:59 20:12",
                format(PrayerTimes.forDay(2026, 11, 21, 2.8167, 101.8, null, MY)));
        assertEquals("05:48 12:59 16:23 18:59 20:12",
                format(PrayerTimes.forDay(2026, 11, 21, 2.8167, 101.8, new int[]{1, 0, 0, 0, 0}, MY)));
    }

    @Test
    public void nextPrayer() {
        long[] t = {100, 200, 300, 400, 500};
        assertEquals(0, PrayerTimes.next(t, 50));
        assertEquals(2, PrayerTimes.next(t, 200));
        assertEquals(-1, PrayerTimes.next(t, 500));
    }
}
