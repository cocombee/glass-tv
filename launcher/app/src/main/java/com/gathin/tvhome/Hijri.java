package com.gathin.tvhome;

import android.icu.text.DateFormat;
import android.icu.util.Calendar;
import android.icu.util.ULocale;
import android.text.method.TransformationMethod;
import android.graphics.Rect;
import android.view.View;

/**
 * Hijri (Umm al-Qura, as in Saudi Arabia) and Gregorian dates in Arabic, through Android's own ICU
 * (API 24+), and the Arabic-Indic digits (٠١٢٣٤٥٦٧٨٩) the clock can be shown in.
 */
final class Hijri {

    private Hijri() {
    }

    /** e.g. "٧ جمادى الأولى ١٤٤٨ هـ", or with Western digits "7 جمادى الأولى 1448 هـ". */
    static String today(boolean arabicDigits) {
        ULocale loc = new ULocale("ar@calendar=islamic-umalqura;numbers=" + (arabicDigits ? "arab" : "latn"));
        Calendar cal = Calendar.getInstance(loc);
        return DateFormat.getDateInstance(cal, DateFormat.LONG, loc).format(cal);
    }

    /** e.g. "الأربعاء ٢٨ أكتوبر ٢٠٢٦" */
    static String gregorian(boolean arabicDigits) {
        ULocale loc = new ULocale("ar@calendar=gregorian;numbers=" + (arabicDigits ? "arab" : "latn"));
        Calendar cal = Calendar.getInstance(loc);
        return DateFormat.getInstanceForSkeleton(cal, "EEEEdMMMMy", loc).format(cal);
    }

    /** Whether today is in Ramadan by the Umm al-Qura calendar (Isha is then 120 minutes after Maghrib). */
    static boolean ramadan() {
        android.icu.util.IslamicCalendar cal = new android.icu.util.IslamicCalendar();
        cal.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        return cal.get(Calendar.MONTH) == android.icu.util.IslamicCalendar.RAMADAN;
    }

    /** Western digits to Arabic-Indic, and AM/PM to ص/م. */
    static String arabicDigits(CharSequence s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? (char) ('٠' + (c - '0')) : c);
        }
        String out = b.toString();
        return out.replaceAll("(?i)\\bAM\\b", "ص").replaceAll("(?i)\\bPM\\b", "م");
    }

    /** Shows a TextClock's (or any TextView's) text in Arabic-Indic digits without changing its format. */
    static final TransformationMethod DIGITS = new TransformationMethod() {
        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            return source == null ? null : arabicDigits(source);
        }

        @Override
        public void onFocusChanged(View view, CharSequence sourceText, boolean focused, int direction, Rect previouslyFocusedRect) {
        }
    };
}
