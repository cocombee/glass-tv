package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The next prayer and the team's next match, in Arabic, for Home and the screensaver alike, e.g.
 * "العصر ٢:٥١ م · بعد ٢٥ دقيقة" and [logo] "أرسنال × ليفربول" [logo] "· الأحد ١ نوفمبر ٧:٣٠ م · الدوري الإنجليزي".
 */
final class InfoLines {
    /** Right-to-left mark: the line reads right to left even where it starts with a Latin team name. */
    private static final String RLM = "‏";
    private static final String[] DAYS = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};
    private static final String[] MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو",
            "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};

    private InfoLines() {
    }

    /** Empty when prayer times are off. */
    static String prayer(Context ctx, Config cfg, long now) {
        if (!cfg.prayerTimes) return "";
        TimeZone zone = TimeZone.getTimeZone(cfg.prayerTimezone);
        Prayer.Next next = Prayer.next(now, cfg.prayerLatitude, cfg.prayerLongitude, zone, Hijri.ramadan());
        if (next == null) return "";
        long minutes = Math.max(1, (next.at - now + 59_999) / 60_000);
        String line = RLM + Prayer.NAMES_AR[next.index] + " " + time(ctx, next.at, zone) + " · بعد " + span(minutes);
        return cfg.arabicDigits ? Hijri.arabicDigits(line) : line;
    }

    /** Decoded logos, by ESPN team id: a few kilobytes each, read once rather than every minute. */
    private static final Map<String, Bitmap> LOGOS = new HashMap<>();

    /**
     * Fills a match row (matchTeamLogo, matchTeams, matchOppLogo and matchInfo, laid out right to left)
     * and shows it, or hides it when no team is set or no match is known yet.
     */
    static void bindMatch(View row, Context ctx, Config cfg, long now) {
        Football.Match m = cfg.footballTeam.isEmpty() ? null : Football.cached(ctx, cfg.footballTeam);
        if (m == null) {
            row.setVisibility(View.GONE);
            return;
        }
        String team = !cfg.footballTeamName.isEmpty() ? cfg.footballTeamName
                : TeamsAr.name(m.teamId(), m.teamAtHome ? m.home : m.away);
        String teams = RLM + team + "  ×  " + TeamsAr.name(m.opponentId(), m.opponent());
        StringBuilder info = new StringBuilder(RLM).append("· ");
        info.append(m.live ? "مباشر الآن" : day(m.kickoff, now, TimeZone.getDefault()) + " " + time(ctx, m.kickoff, TimeZone.getDefault()));
        String comp = competition(m.competition);
        if (!comp.isEmpty()) info.append(" · ").append(comp);
        if (!m.teamAtHome) info.append(" · خارج أرضه");
        String infoText = info.toString();
        if (cfg.arabicDigits) {
            teams = Hijri.arabicDigits(teams);
            infoText = Hijri.arabicDigits(infoText);
        }
        ((TextView) row.findViewById(R.id.matchTeams)).setText(teams);
        ((TextView) row.findViewById(R.id.matchInfo)).setText(infoText);
        setLogo(row.findViewById(R.id.matchTeamLogo), ctx, m.teamId());
        setLogo(row.findViewById(R.id.matchOppLogo), ctx, m.opponentId());
        row.setVisibility(View.VISIBLE);
    }

    private static void setLogo(ImageView view, Context ctx, String id) {
        Bitmap bmp = LOGOS.get(id);
        if (bmp == null && !id.isEmpty()) {
            File f = Football.logo(ctx, id);
            if (f.isFile()) bmp = BitmapFactory.decodeFile(f.getPath());
            if (bmp != null) LOGOS.put(id, bmp);
        }
        view.setImageBitmap(bmp);
        view.setVisibility(bmp != null ? View.VISIBLE : View.GONE);
    }

    /** "h:mm ص/م", or "HH:mm" where the TV is set to 24-hour time. */
    private static String time(Context ctx, long at, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(at);
        int h = c.get(Calendar.HOUR_OF_DAY), min = c.get(Calendar.MINUTE);
        if (android.text.format.DateFormat.is24HourFormat(ctx)) return String.format(Locale.ROOT, "%02d:%02d", h, min);
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d %s", h12, min, h < 12 ? "ص" : "م");
    }

    /** "اليوم", "غدًا", or the weekday with the date: "الأحد ١ نوفمبر". */
    private static String day(long at, long now, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone), today = Calendar.getInstance(zone);
        c.setTimeInMillis(at);
        today.setTimeInMillis(now);
        int days = (int) ((startOfDay(c) - startOfDay(today)) / 86_400_000L);
        if (days == 0) return "اليوم";
        if (days == 1) return "غدًا";
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 12);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    /** "٢٥ دقيقة", "ساعتين و١٠ دقائق": Arabic counts take a different word for 1, 2, 3–10 and 11+. */
    private static String span(long minutes) {
        long h = minutes / 60, m = minutes % 60;
        String mins = count(m, "دقيقة", "دقيقتين", "دقائق");
        if (h == 0) return mins;
        String hours = count(h, "ساعة", "ساعتين", "ساعات");
        return m == 0 ? hours : hours + " و" + mins;
    }

    private static String count(long n, String one, String two, String few) {
        if (n == 1) return one;
        if (n == 2) return two;
        return n + " " + (n <= 10 ? few : one);
    }

    private static String competition(String name) {
        switch (name) {
            case "Premier League":
            case "English Premier League":
                return "الدوري الإنجليزي";
            case "UEFA Champions League":
            case "Champions League":
                return "دوري أبطال أوروبا";
            case "UEFA Europa League":
                return "الدوري الأوروبي";
            case "FA Cup":
            case "English FA Cup":
                return "كأس الاتحاد";
            case "Carabao Cup":
            case "English Carabao Cup":
            case "League Cup":
            case "English League Cup":
                return "كأس الرابطة";
            case "UEFA Super Cup":
                return "السوبر الأوروبي";
            case "FA Community Shield":
            case "Community Shield":
                return "درع المجتمع";
            case "FIFA Club World Cup":
            case "Club World Cup":
                return "كأس العالم للأندية";
            case "Club Friendly":
            case "Friendly":
                return "مباراة ودية";
            default:
                // Left out rather than shown in English.
                return "";
        }
    }
}
