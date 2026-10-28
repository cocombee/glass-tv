package com.gathin.tvhome;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Prayer times by the Umm al-Qura method (Saudi Arabia): Fajr at the sun 18.5° below the horizon,
 * Asr at a shadow as long as the object (Shafi'i), Isha 90 minutes after Maghrib, 120 in Ramadan.
 * Worked out on the TV from the sun's position, the praytimes.org formulas; nothing goes online.
 * Checked against Aladhan's Umm al-Qura times for Riyadh to within a minute.
 */
final class Prayer {

    static final String[] NAMES_AR = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    /** The next prayer from now: its index in {@link #NAMES_AR} and its time in epoch milliseconds. */
    static final class Next {
        final int index;
        final long at;

        Next(int index, long at) {
            this.index = index;
            this.at = at;
        }
    }

    private Prayer() {
    }

    static Next next(long now, double lat, double lon, TimeZone zone, boolean ramadan) {
        Calendar day = Calendar.getInstance(zone);
        day.setTimeInMillis(now);
        for (int add = 0; add < 2; add++) {
            long[] t = day(day, lat, lon, zone, ramadan && add == 0);
            for (int i = 0; i < t.length; i++) {
                if (t[i] > now) return new Next(i, t[i]);
            }
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
        return null;
    }

    /** Fajr, Dhuhr, Asr, Maghrib and Isha on the day {@code date} falls on, in epoch milliseconds. */
    static long[] day(Calendar date, double lat, double lon, TimeZone zone, boolean ramadan) {
        int y = date.get(Calendar.YEAR), m = date.get(Calendar.MONTH) + 1, d = date.get(Calendar.DAY_OF_MONTH);
        Calendar midnight = Calendar.getInstance(zone);
        midnight.clear();
        midnight.set(y, m - 1, d, 0, 0, 0);
        double tz = zone.getOffset(midnight.getTimeInMillis() + 12 * 3_600_000L) / 3_600_000.0;
        double[] h = hours(y, m, d, lat, lon, tz, ramadan);
        long[] out = new long[h.length];
        for (int i = 0; i < h.length; i++) {
            out[i] = midnight.getTimeInMillis() + Math.round(h[i] * 60) * 60_000L;
        }
        return out;
    }

    /** The five times as hours after local midnight. */
    static double[] hours(int y, int m, int d, double lat, double lon, double tz, boolean ramadan) {
        double jd = julian(y, m, d) - lon / (15 * 24);
        double adj = tz - lon / 15;
        double fajr = angleTime(jd, 18.5, 5 / 24.0, true, lat) + adj;
        double dhuhr = midDay(jd, 12 / 24.0) + adj;
        double asr = asr(jd, 13 / 24.0, lat) + adj;
        double maghrib = angleTime(jd, 0.833, 18 / 24.0, false, lat) + adj;
        double isha = maghrib + (ramadan ? 120 : 90) / 60.0;
        return new double[]{fajr, dhuhr, asr, maghrib, isha};
    }

    private static double julian(int y, int m, int d) {
        if (m <= 2) {
            y -= 1;
            m += 12;
        }
        int a = y / 100, b = 2 - a + a / 4;
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + d + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} */
    private static double[] sun(double jd) {
        double days = jd - 2451545.0;
        double g = fix(357.529 + 0.98560028 * days, 360);
        double q = fix(280.459 + 0.98564736 * days, 360);
        double l = fix(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 360);
        double e = 23.439 - 0.00000036 * days;
        double ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15;
        double eqt = q / 15 - fix(ra, 24);
        double decl = Math.toDegrees(Math.asin(sin(e) * sin(l)));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        return fix(12 - sun(jd + t)[1], 24);
    }

    /** When the sun is {@code angle} degrees below the horizon, before noon ({@code ccw}) or after. */
    private static double angleTime(double jd, double angle, double t, boolean ccw, double lat) {
        double decl = sun(jd + t)[0];
        double noon = midDay(jd, t);
        double span = Math.toDegrees(Math.acos((-sin(angle) - sin(decl) * sin(lat)) / (cos(decl) * cos(lat)))) / 15;
        return ccw ? noon - span : noon + span;
    }

    private static double asr(double jd, double t, double lat) {
        double decl = sun(jd + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
        return angleTime(jd, angle, t, false, lat);
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double fix(double a, double b) {
        a = a - b * Math.floor(a / b);
        return a < 0 ? a + b : a;
    }
}
