package com.example.voicerecorder;

import org.junit.Test;

import java.util.Calendar;

import static org.junit.Assert.assertEquals;

public class FormattersTest {

    @Test
    public void cleanName_stripsInvalidCharsAndLeadingDots() {
        assertEquals("Meeting_notes", Formatters.cleanName("  Meeting/notes  "));
        assertEquals("a_b_c", Formatters.cleanName("a:b*c"));
        assertEquals("hidden", Formatters.cleanName("...hidden..."));
        assertEquals("", Formatters.cleanName("..."));
        assertEquals("", Formatters.cleanName(null));
    }

    @Test
    public void displayName_stripsTimestampFromDefaultNames() {
        assertEquals("Recording", Formatters.displayName("Recording_23-11-2026_08-51-21AM.mp3"));
        assertEquals("Standup", Formatters.displayName("Standup_01-01-2026_11-59-59pm.m4a"));
        assertEquals("Dhwani", Formatters.displayName("Dhwani_20260101_090000.mp3"));
        assertEquals("Custom_Note", Formatters.displayName("Custom_Note.mp3"));
    }

    @Test
    public void fmtDuration_formatsMinutesAndHours() {
        assertEquals("00:00", Formatters.fmtDuration(0));
        assertEquals("00:00", Formatters.fmtDuration(-500));
        assertEquals("01:05", Formatters.fmtDuration(65_000));
        assertEquals("1:02:03", Formatters.fmtDuration(3_723_000));
    }

    @Test
    public void fmtSize_formatsKbAndMb() {
        assertEquals("0 KB", Formatters.fmtSize(500));
        assertEquals("512 KB", Formatters.fmtSize(512 * 1024));
        assertEquals("2.5 MB", Formatters.fmtSize((long) (2.5 * 1024 * 1024)));
    }

    @Test
    public void shortDate_handlesNewYearsBoundary() {
        Calendar jan1 = Calendar.getInstance();
        jan1.set(2027, Calendar.JANUARY, 1, 10, 0, 0);
        jan1.set(Calendar.MILLISECOND, 0);

        Calendar dec31 = Calendar.getInstance();
        dec31.set(2026, Calendar.DECEMBER, 31, 22, 30, 0);
        dec31.set(Calendar.MILLISECOND, 0);

        Calendar dec20 = Calendar.getInstance();
        dec20.set(2026, Calendar.DECEMBER, 20, 12, 0, 0);
        dec20.set(Calendar.MILLISECOND, 0);

        assertEquals("Yesterday", Formatters.shortDate(dec31.getTimeInMillis(), jan1.getTimeInMillis()));
        assertEquals("20 Dec 26", Formatters.shortDate(dec20.getTimeInMillis(), jan1.getTimeInMillis()));
    }
}
