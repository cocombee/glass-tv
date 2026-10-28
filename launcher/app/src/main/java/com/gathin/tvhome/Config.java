package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.gathin.tvhome/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the dock and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read once in {@link MainActivity#onCreate}; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";
    /** Folder of pictures the launcher rotates through, in file-name order (tools/theme.sh pushes a theme's wallpapers/). */
    static final String WALLPAPERS_DIR = "wallpapers";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final String liveTvPackage;
    final String liveTvAction;
    final String sourcePanelService;
    final String sourcePanelAction;
    final String inputAction;
    final int columns;
    final int rowsBelowDock;
    final boolean shamsiDate;
    /** The Hijri (Umm al-Qura) date in Arabic above the Gregorian one, which is then in Arabic too (hijri_date). */
    final boolean hijriDate;
    /** The clock and dates at the top right, the buttons at the top left (clock_right). */
    final boolean clockRight;
    /** The clock and dates in Arabic-Indic digits, ٠١٢٣٤٥٦٧٨٩ (arabic_digits). */
    final boolean arabicDigits;
    /** The place's name, country and description above the dock (place_text); false shows the picture alone. */
    final boolean placeText;
    /** The next prayer and the time left until it, under the dates and on the screensaver (prayer_times). */
    final boolean prayerTimes;
    /** Where prayer times are worked out for: prayer_latitude, prayer_longitude, prayer_timezone; Riyadh by default. */
    final double prayerLatitude, prayerLongitude;
    final String prayerTimezone;
    /** ESPN team id whose next match is shown (football_team, e.g. "359" for Arsenal); empty for none. */
    final String footballTeam;
    /** The team's name as shown (football_team_name, e.g. "أرسنال"); empty uses the feed's. */
    final String footballTeamName;
    final boolean moduleCards;
    /** Calmer motion for weak GPUs (config.xml, reduced_motion): stiff spring, short fades, no replay. */
    final boolean reducedMotion;
    final String vpnApp;
    final boolean showHomeSettingsShortcut;
    /** The Wi-Fi / Ethernet pill in the top bar (config.xml, show_network_pill). */
    final boolean showNetworkPill;
    /** The Live TV row in the Inputs menu (config.xml, show_live_tv). */
    final boolean showLiveTv;
    /** Background colour behind everything, #RRGGBB; black unless a theme says otherwise. */
    final int backgroundColor;
    /** Full-screen picture behind the launcher, or null for the plain colour. */
    final File wallpaper;
    /** Pictures to rotate through, empty for none; when present they replace {@link #wallpaper}. */
    final List<File> wallpapers;
    /** Time between wallpaper changes (wallpaper_seconds, else wallpaper_minutes); 0 changes only when Home starts. */
    final long wallpaperIntervalMs;
    /** Pictures of places to visit, renewed weekly (see PlaceWallpapers); "places": false keeps them off. */
    final boolean places;
    /** Days between clearing the TV's app caches (see CacheCleaner); 0 never does. */
    final int cacheCleanDays;
    /** Whether the overlay file was present and parsed. */
    final boolean overlayApplied;

    private Config(List<String> dockApps, List<String> hiddenApps, String liveTvPackage, String liveTvAction,
                   String sourcePanelService, String sourcePanelAction, String inputAction,
                   int columns, int rowsBelowDock,
                   boolean shamsiDate, boolean hijriDate, boolean clockRight, boolean arabicDigits,
                   boolean placeText, boolean prayerTimes, double prayerLatitude, double prayerLongitude,
                   String prayerTimezone, String footballTeam, String footballTeamName,
                   boolean moduleCards, boolean reducedMotion, String vpnApp,
                   boolean showHomeSettingsShortcut, boolean showNetworkPill, boolean showLiveTv,
                   int backgroundColor,
                   File wallpaper, List<File> wallpapers, long wallpaperIntervalMs,
                   boolean places, int cacheCleanDays, boolean overlayApplied) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.liveTvPackage = liveTvPackage;
        this.liveTvAction = liveTvAction;
        this.sourcePanelService = sourcePanelService;
        this.sourcePanelAction = sourcePanelAction;
        this.inputAction = inputAction;
        this.columns = columns;
        this.rowsBelowDock = rowsBelowDock;
        this.shamsiDate = shamsiDate;
        this.hijriDate = hijriDate;
        this.clockRight = clockRight;
        this.arabicDigits = arabicDigits;
        this.placeText = placeText;
        this.prayerTimes = prayerTimes;
        this.prayerLatitude = prayerLatitude;
        this.prayerLongitude = prayerLongitude;
        this.prayerTimezone = prayerTimezone;
        this.footballTeam = footballTeam;
        this.footballTeamName = footballTeamName;
        this.moduleCards = moduleCards;
        this.reducedMotion = reducedMotion;
        this.vpnApp = vpnApp;
        this.showHomeSettingsShortcut = showHomeSettingsShortcut;
        this.showNetworkPill = showNetworkPill;
        this.showLiveTv = showLiveTv;
        this.backgroundColor = backgroundColor;
        this.wallpaper = wallpaper;
        this.wallpapers = Collections.unmodifiableList(wallpapers);
        this.wallpaperIntervalMs = wallpaperIntervalMs;
        this.places = places;
        this.cacheCleanDays = cacheCleanDays;
        this.overlayApplied = overlayApplied;
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        String liveTv = res.getString(R.string.live_tv_package).trim();
        String liveTvAction = res.getString(R.string.live_tv_action).trim();
        String panelService = res.getString(R.string.source_panel_service).trim();
        String panelAction = res.getString(R.string.source_panel_action).trim();
        String inputAction = res.getString(R.string.input_action).trim();
        int columns = res.getInteger(R.integer.columns);
        int rowsBelow = res.getInteger(R.integer.rows_below_dock);
        boolean shamsi = res.getBoolean(R.bool.shamsi_date);
        boolean hijri = res.getBoolean(R.bool.hijri_date);
        boolean clockRight = res.getBoolean(R.bool.clock_right);
        boolean arabicDigits = res.getBoolean(R.bool.arabic_digits);
        boolean placeText = res.getBoolean(R.bool.place_text);
        boolean prayerTimes = res.getBoolean(R.bool.prayer_times);
        double prayerLat = 24.7136, prayerLon = 46.6753;   // Riyadh
        String prayerZone = "Asia/Riyadh";
        String footballTeam = res.getString(R.string.football_team).trim();
        String footballTeamName = res.getString(R.string.football_team_name).trim();
        boolean moduleCards = res.getBoolean(R.bool.module_cards);
        boolean reducedMotion = res.getBoolean(R.bool.reduced_motion);
        String vpn = res.getString(R.string.vpn_app).trim();
        boolean homeSettings = res.getBoolean(R.bool.show_home_settings_shortcut);
        boolean networkPill = res.getBoolean(R.bool.show_network_pill);
        boolean liveTvRow = res.getBoolean(R.bool.show_live_tv);
        int background = 0xFF000000;
        boolean applied = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            applied = true;
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            liveTv = string(json, "live_tv_package", liveTv);
            liveTvAction = string(json, "live_tv_action", liveTvAction);
            panelService = string(json, "source_panel_service", panelService);
            panelAction = string(json, "source_panel_action", panelAction);
            inputAction = string(json, "input_action", inputAction);
            columns = integer(json, "columns", columns, 1, 8);
            rowsBelow = integer(json, "rows_below_dock", rowsBelow, 0, 3);
            shamsi = bool(json, "shamsi_date", shamsi);
            hijri = bool(json, "hijri_date", hijri);
            clockRight = bool(json, "clock_right", clockRight);
            arabicDigits = bool(json, "arabic_digits", arabicDigits);
            placeText = bool(json, "place_text", placeText);
            prayerTimes = bool(json, "prayer_times", prayerTimes);
            prayerLat = number(json, "prayer_latitude", prayerLat, -66, 66);
            prayerLon = number(json, "prayer_longitude", prayerLon, -180, 180);
            prayerZone = string(json, "prayer_timezone", prayerZone);
            // The id may be written as a number or as a string.
            Object team = json.opt("football_team");
            if (team instanceof Number) footballTeam = String.valueOf(((Number) team).longValue());
            else footballTeam = string(json, "football_team", footballTeam);
            footballTeamName = string(json, "football_team_name", footballTeamName);
            moduleCards = bool(json, "module_cards", moduleCards);
            reducedMotion = bool(json, "reduced_motion", reducedMotion);
            vpn = string(json, "vpn_app", vpn);
            homeSettings = bool(json, "show_home_settings_shortcut", homeSettings);
            networkPill = bool(json, "show_network_pill", networkPill);
            liveTvRow = bool(json, "show_live_tv", liveTvRow);
            background = color(json, "background_color", background);
        }
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        // A theme can also switch the wallpaper off while the file is still there.
        boolean wallpaperOn = !(json != null && json.has("wallpaper") && !bool(json, "wallpaper", true));
        if (!wallpaperOn) wallpaper = null;
        List<File> wallpapers = new ArrayList<>();
        File[] pics = dir != null && wallpaperOn ? new File(dir, WALLPAPERS_DIR).listFiles() : null;
        if (pics != null) {
            for (File f : pics) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && f.length() > 0 && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))) wallpapers.add(f);
            }
            Collections.sort(wallpapers);
        }
        int wallpaperMinutes = json != null ? integer(json, "wallpaper_minutes", 30, 0, 1440) : 30;
        int wallpaperSeconds = json != null ? integer(json, "wallpaper_seconds", 0, 5, 86400) : 0;
        long wallpaperIntervalMs = wallpaperSeconds > 0 ? wallpaperSeconds * 1000L : wallpaperMinutes * 60_000L;
        // On unless a theme says otherwise: a fresh install shows places with nothing to set up.
        boolean places = wallpaperOn && (json == null || bool(json, "places", true));
        int cacheCleanDays = json != null ? integer(json, "cache_clean_days", CacheCleaner.DEFAULT_DAYS, 0, 90)
                : CacheCleaner.DEFAULT_DAYS;

        return new Config(dock, hidden, liveTv, liveTvAction, panelService, panelAction, inputAction,
                Math.max(1, columns), Math.max(0, rowsBelow), shamsi, hijri, clockRight, arabicDigits,
                placeText, prayerTimes, prayerLat, prayerLon, prayerZone, footballTeam, footballTeamName, moduleCards, reducedMotion,
                vpn, homeSettings, networkPill, liveTvRow, background, wallpaper, wallpapers, wallpaperIntervalMs,
                places, cacheCleanDays, applied);
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    private static double number(JSONObject o, String key, double fallback, double min, double max) {
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        double d = ((Number) v).doubleValue();
        return d < min || d > max ? fallback : d;
    }

    private static int color(JSONObject o, String key, int fallback) {
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            int c = Color.parseColor(((String) v).trim());
            return c | 0xFF000000;   // always opaque: the window behind is black anyway
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
