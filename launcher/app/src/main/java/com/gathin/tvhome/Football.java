package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A team's next match, from ESPN's public schedule feed (no key), every competition together. Fetched
 * by Home at most every 6 hours (sooner once the cached match is over, every 30 minutes after a
 * failure) and kept in preferences, so the screensaver reads it without going online.
 *
 * <p>Off unless a theme names a team ({@code football_team}, ESPN's team id: Arsenal is 359).
 */
final class Football {
    private static final String TAG = "TvHome.Football";
    private static final String FEED = "https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/%s/schedule?fixture=true";
    /** ESPN's logo, scaled down on their side to 96 px (about 5 KB). */
    private static final String LOGO = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/soccer/500/%s.png&h=96&w=96";
    private static final long REFRESH_MS = 6 * 3_600_000L;
    private static final long RETRY_MS = 30 * 60_000L;
    /** A match counts as over this long after kick-off, and the next one is looked up. */
    private static final long MATCH_MS = 150 * 60_000L;

    /** The cached next match, or null. */
    static final class Match {
        final long kickoff;
        final String home, away, homeId, awayId, competition;
        final boolean teamAtHome, live;

        Match(long kickoff, String home, String away, String homeId, String awayId, String competition,
              boolean teamAtHome, boolean live) {
            this.kickoff = kickoff;
            this.home = home;
            this.away = away;
            this.homeId = homeId;
            this.awayId = awayId;
            this.competition = competition;
            this.teamAtHome = teamAtHome;
            this.live = live;
        }

        String opponent() {
            return teamAtHome ? away : home;
        }

        String teamId() {
            return teamAtHome ? homeId : awayId;
        }

        String opponentId() {
            return teamAtHome ? awayId : homeId;
        }
    }

    private Football() {
    }

    static Match cached(Context ctx, String team) {
        SharedPreferences p = prefs(ctx);
        if (!team.equals(p.getString("team", "")) || !p.contains("kickoff")) return null;
        long kickoff = p.getLong("kickoff", 0);
        if (System.currentTimeMillis() > kickoff + MATCH_MS) return null;
        return new Match(kickoff, p.getString("home", ""), p.getString("away", ""),
                p.getString("homeId", ""), p.getString("awayId", ""), p.getString("competition", ""), p.getBoolean("teamAtHome", true),
                System.currentTimeMillis() >= kickoff);
    }

    static boolean due(Context ctx, String team) {
        if (team.isEmpty()) return false;
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        // A cache from before team ids and logos were kept is replaced at once.
        if (p.contains("kickoff") && !p.contains("homeId")) return true;
        if (now - p.getLong("attempt", 0) < RETRY_MS) return false;
        if (!team.equals(p.getString("team", ""))) return true;
        if (now - p.getLong("fetched", 0) > REFRESH_MS) return true;
        return p.contains("kickoff") && now > p.getLong("kickoff", 0) + MATCH_MS;
    }

    /** Fetches and caches the next match; off the main thread. Returns whether the cache changed. */
    static boolean refresh(Context ctx, String team) {
        SharedPreferences p = prefs(ctx);
        long now = System.currentTimeMillis();
        p.edit().putLong("attempt", now).apply();
        try {
            JSONObject feed = new JSONObject(get(String.format(Locale.ROOT, FEED, team)));
            JSONArray events = feed.optJSONArray("events");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONObject best = null;
            long bestAt = Long.MAX_VALUE;
            for (int i = 0; events != null && i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                JSONObject comp = e.getJSONArray("competitions").getJSONObject(0);
                String state = comp.optJSONObject("status") != null
                        ? comp.getJSONObject("status").getJSONObject("type").optString("state") : "";
                if (!"pre".equals(state) && !"in".equals(state)) continue;
                long at = iso.parse(e.getString("date")).getTime();
                if (at + MATCH_MS < now || at >= bestAt) continue;
                best = e;
                bestAt = at;
            }
            SharedPreferences.Editor ed = p.edit().putString("team", team).putLong("fetched", now);
            if (best == null) {
                ed.remove("kickoff").apply();
                return true;
            }
            JSONObject comp = best.getJSONArray("competitions").getJSONObject(0);
            JSONArray sides = comp.getJSONArray("competitors");
            String home = "", away = "", homeId = "", awayId = "";
            boolean teamAtHome = true;
            for (int i = 0; i < sides.length(); i++) {
                JSONObject s = sides.getJSONObject(i);
                String name = s.getJSONObject("team").optString("shortDisplayName", s.getJSONObject("team").optString("displayName"));
                String id = s.getJSONObject("team").optString("id");
                boolean isHome = "home".equals(s.optString("homeAway"));
                if (isHome) {
                    home = name;
                    homeId = id;
                } else {
                    away = name;
                    awayId = id;
                }
                if (team.equals(id)) teamAtHome = isHome;
            }
            JSONObject league = best.optJSONObject("league");
            String competition = league != null ? league.optString("shortName", league.optString("name")) : "";
            for (String id : new String[]{homeId, awayId}) fetchLogo(ctx, id);
            ed.putLong("kickoff", bestAt).putString("home", home).putString("away", away)
                    .putString("homeId", homeId).putString("awayId", awayId)
                    .putString("competition", competition).putBoolean("teamAtHome", teamAtHome).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "next match: " + e);
            return false;
        }
    }

    /** Where a team's logo is kept; it may not exist yet. */
    static File logo(Context ctx, String id) {
        return new File(new File(ctx.getFilesDir(), "logos"), id + ".png");
    }

    /** Downloads a team's logo once; a failure only leaves the logo out. */
    private static void fetchLogo(Context ctx, String id) {
        if (id.isEmpty()) return;
        File f = logo(ctx, id);
        if (f.isFile() && f.length() > 0) return;
        try {
            byte[] png = getBytes(String.format(Locale.ROOT, LOGO, id), 256 * 1024);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(png);
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            Log.w(TAG, "logo " + id + ": " + e);
        }
    }

    private static String get(String url) throws Exception {
        return new String(getBytes(url, 2 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json, image/png");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    if (out.size() > maxBytes) throw new IllegalStateException("response too large");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("football", Context.MODE_PRIVATE);
    }
}
