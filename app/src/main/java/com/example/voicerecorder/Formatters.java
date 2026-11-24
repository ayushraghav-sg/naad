package com.example.voicerecorder;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java formatting, file-name cleaning, and date helpers (unit-testable without Android). */
public final class Formatters {
    private static final Pattern DEFAULT_NAME_NEW =
            Pattern.compile("^(.*)_\\d{2}-\\d{2}-\\d{4}_\\d{2}-\\d{2}-\\d{2}(AM|PM|am|pm)$");
    private static final Pattern DEFAULT_NAME_LEGACY =
            Pattern.compile("^(REC|Dhwani)_\\d{8}_\\d{6}$");
    private static final Pattern INVALID_FILENAME_CHARS =
            Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]");

    private Formatters() { }

    public static String cleanName(String s) {
        if (s == null) return "";
        String cleaned = INVALID_FILENAME_CHARS.matcher(s.trim()).replaceAll("_");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned.trim();
    }

    public static String stripExt(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    /** Default names like "Recording_23-11-2026_08-51-21AM.mp3" show as "Recording". */
    public static String displayName(String fileName) {
        String n = stripExt(fileName);
        Matcher m = DEFAULT_NAME_NEW.matcher(n);
        if (m.matches() && m.group(1) != null && !m.group(1).isEmpty()) return m.group(1);
        m = DEFAULT_NAME_LEGACY.matcher(n);
        if (m.matches()) return m.group(1);
        return n;
    }

    public static String fmtDuration(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    public static String fmtSize(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024L * 1024L) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    /** "9:22 am" today, "Yesterday", "23 Nov", or "23 Nov 25". Handles Dec 31 -> Jan 1 properly. */
    public static String shortDate(long timestampMs) {
        return shortDate(timestampMs, System.currentTimeMillis());
    }

    static String shortDate(long timestampMs, long nowMs) {
        Calendar todayStart = Calendar.getInstance();
        todayStart.setTimeInMillis(nowMs);
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        todayStart.set(Calendar.MILLISECOND, 0);

        Calendar tomorrowStart = (Calendar) todayStart.clone();
        tomorrowStart.add(Calendar.DAY_OF_YEAR, 1);

        Calendar yesterdayStart = (Calendar) todayStart.clone();
        yesterdayStart.add(Calendar.DAY_OF_YEAR, -1);

        if (timestampMs >= todayStart.getTimeInMillis() && timestampMs < tomorrowStart.getTimeInMillis()) {
            return new SimpleDateFormat("h:mm a", Locale.ENGLISH)
                    .format(new Date(timestampMs)).toLowerCase(Locale.ENGLISH);
        }
        if (timestampMs >= yesterdayStart.getTimeInMillis() && timestampMs < todayStart.getTimeInMillis()) {
            return "Yesterday";
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timestampMs);
        boolean sameYear = todayStart.get(Calendar.YEAR) == c.get(Calendar.YEAR);
        return new SimpleDateFormat(sameYear ? "d MMM" : "d MMM yy", Locale.ENGLISH)
                .format(new Date(timestampMs));
    }

    public static String fullDate(long timestampMs) {
        return new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH).format(new Date(timestampMs));
    }

    public static String timestampStamp(long timestampMs) {
        return new SimpleDateFormat("dd-MM-yyyy_hh-mm-ssa", Locale.ENGLISH).format(new Date(timestampMs));
    }
}
