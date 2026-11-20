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

    /** Nilai, Negeri Sembilan, without ihtiyati: matches NOAA's solar calculator rounded up to the minute. */
    @Test
    public void nilaiWithoutIhtiyati() {
        assertEquals("05:38 12:59 16:22 18:58 20:11",
                format(PrayerTimes.forDay(2026, 11, 20, 2.8167, 101.8, 0, MY)));
        assertEquals("05:42 13:15 16:42 19:24 20:39",
                format(PrayerTimes.forDay(2026, 6, 21, 2.8167, 101.8, 0, MY)));
    }

    @Test
    public void ihtiyatiIsAdded() {
        assertEquals("05:40 13:01 16:24 19:00 20:13",
                format(PrayerTimes.forDay(2026, 11, 20, 2.8167, 101.8, 2, MY)));
    }

    @Test
    public void nextPrayer() {
        long[] t = {100, 200, 300, 400, 500};
        assertEquals(0, PrayerTimes.next(t, 50));
        assertEquals(2, PrayerTimes.next(t, 200));
        assertEquals(-1, PrayerTimes.next(t, 500));
    }
}
