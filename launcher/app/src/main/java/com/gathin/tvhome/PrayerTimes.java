package com.gathin.tvhome;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The five daily prayer times, worked out on the TV from the sun's position, so nothing goes online.
 * Uses JAKIM's (Malaysia) parameters: Subuh at the sun 20 degrees below the horizon, Isyak at 18 degrees,
 * Asar when a shadow is its object's length plus the noon shadow (Shafi'i), Maghrib at sunset; then
 * ihtiyati, a safety margin of a few minutes, is added to each and the time is rounded up to the minute.
 * The sun's position is the low-precision formula of the US Naval Observatory, good to about a minute.
 */
final class PrayerTimes {

    static final String[] NAMES = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};

    static final double FAJR_ANGLE = 20.0;
    static final double ISHA_ANGLE = 18.0;
    /** The sun's centre at sunrise and sunset: its radius plus refraction. */
    private static final double SUNSET_ANGLE = 0.8333;

    private PrayerTimes() {
    }

    /**
     * The five times for the given day, as epoch milliseconds, in the order of {@link #NAMES}.
     *
     * @param year      the day, in the time zone {@code zone}
     * @param month     1 to 12
     * @param day       1 to 31
     * @param latitude  degrees, north positive
     * @param longitude degrees, east positive
     * @param ihtiyatiMinutes minutes added to every time before it is rounded up
     */
    static long[] forDay(int year, int month, int day, double latitude, double longitude,
                         int ihtiyatiMinutes, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.clear();
        c.set(year, month - 1, day, 0, 0, 0);
        long midnight = c.getTimeInMillis();
        // Julian day at 0h UT of the civil date; the hours below are UT hours from that moment.
        double jd0 = julianDay(year, month, day);
        // The local day may start before or after 0h UT of the same date; times are taken as UT hours
        // from jd0 and then turned into milliseconds, so the zone only matters for the date itself.
        double transit = transitUt(jd0, longitude);
        double fajr = transit - hourAngle(jd0 + transit / 24.0, -FAJR_ANGLE, latitude);
        double asr = transit + asrHourAngle(jd0 + transit / 24.0, latitude);
        double maghrib = transit + hourAngle(jd0 + transit / 24.0, -SUNSET_ANGLE, latitude);
        double isha = transit + hourAngle(jd0 + transit / 24.0, -ISHA_ANGLE, latitude);
        // A second pass with the sun where it is at each of those moments.
        transit = transitUt(jd0 + transit / 24.0, longitude);
        fajr = transit - hourAngle(jd0 + fajr / 24.0, -FAJR_ANGLE, latitude);
        asr = transit + asrHourAngle(jd0 + asr / 24.0, latitude);
        maghrib = transit + hourAngle(jd0 + maghrib / 24.0, -SUNSET_ANGLE, latitude);
        isha = transit + hourAngle(jd0 + isha / 24.0, -ISHA_ANGLE, latitude);

        double[] hours = {fajr, transit, asr, maghrib, isha};
        // 0h UT of this date, in epoch ms; the civil midnight in the zone is only used to pick the date.
        long utMidnight = Math.round((jd0 - 2440587.5) * 86_400_000.0);
        long[] out = new long[5];
        for (int i = 0; i < 5; i++) {
            long t = utMidnight + Math.round(hours[i] * 3_600_000.0) + ihtiyatiMinutes * 60_000L;
            out[i] = roundUpToMinute(t);
            // A zone far from Greenwich can put a time on the neighbouring UT date; keep it on this day.
            if (out[i] < midnight) out[i] += 86_400_000L;
            else if (out[i] >= midnight + 86_400_000L) out[i] -= 86_400_000L;
        }
        return out;
    }

    /** Index into {@link #NAMES} of the next prayer after {@code now}, or -1 when Isyak has passed. */
    static int next(long[] times, long now) {
        for (int i = 0; i < times.length; i++) if (times[i] > now) return i;
        return -1;
    }

    static long roundUpToMinute(long ms) {
        long r = ms % 60_000L;
        return r == 0 ? ms : ms - r + 60_000L;
    }

    // ---- the sun ----------------------------------------------------------------------------------

    static double julianDay(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        int a = year / 100;
        int b = 2 - a + a / 4;
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} at Julian day {@code jd}. */
    static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * sin(g) + 0.020 * sin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        eqt = eqt - 24.0 * Math.floor((eqt + 12.0) / 24.0);   // into -12..12
        double decl = Math.toDegrees(Math.asin(sin(e) * sin(l)));
        return new double[]{decl, eqt};
    }

    /** Solar noon, UT hours after 0h UT of the date of {@code jdNear}'s day. */
    private static double transitUt(double jdNear, double longitude) {
        double eqt = sun(jdNear)[1];
        return 12.0 - longitude / 15.0 - eqt;
    }

    /** Hours between solar noon and the sun at {@code altitude} degrees (negative: below the horizon). */
    private static double hourAngle(double jd, double altitude, double latitude) {
        double decl = sun(jd)[0];
        double cosH = (sin(altitude) - sin(decl) * sin(latitude)) / (cos(decl) * cos(latitude));
        cosH = Math.max(-1.0, Math.min(1.0, cosH));   // polar day or night: clamp instead of NaN
        return Math.toDegrees(Math.acos(cosH)) / 15.0;
    }

    /** Hours from solar noon to Asar: a shadow its object's length longer than at noon (Shafi'i). */
    private static double asrHourAngle(double jd, double latitude) {
        double decl = sun(jd)[0];
        double altitude = Math.toDegrees(Math.atan(1.0 / (1.0 + Math.tan(Math.toRadians(Math.abs(latitude - decl))))));
        return hourAngle(jd, altitude, latitude);
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double fixAngle(double a) {
        a = a - 360.0 * Math.floor(a / 360.0);
        return a < 0 ? a + 360.0 : a;
    }

    private static double fixHour(double h) {
        h = h - 24.0 * Math.floor(h / 24.0);
        return h < 0 ? h + 24.0 : h;
    }
}
