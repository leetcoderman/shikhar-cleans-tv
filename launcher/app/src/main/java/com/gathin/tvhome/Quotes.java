package com.gathin.tvhome;

/**
 * Short quotes in place of the time-of-day greeting (config.xml, time_quotes): one list each for the
 * morning, afternoon, evening and night in res/values/quotes.xml, every entry {@code text|author}.
 *
 * Pure maths like {@link Sky}, so the choice is tested on the JVM: a new quote every hour, never the same
 * one two hours running (with three or more in the list), and each day starts one further on.
 */
final class Quotes {

    static final int MORNING = 0;
    static final int AFTERNOON = 1;
    static final int EVENING = 2;
    static final int NIGHT = 3;

    private Quotes() {
    }

    /** The part of the day an hour (0 to 23) belongs to: morning from 5, afternoon from 12, evening from 17, night from 21. */
    static int partOfDay(int hour) {
        if (hour < 5) return NIGHT;
        if (hour < 12) return MORNING;
        if (hour < 17) return AFTERNOON;
        if (hour < 21) return EVENING;
        return NIGHT;
    }

    /**
     * Which of {@code size} quotes to show in {@code hour} of the day {@code epochDay}
     * ({@link java.time.LocalDate#toEpochDay()}), or -1 for an empty list.
     */
    static int index(int size, long epochDay, int hour) {
        if (size <= 0) return -1;
        // A 25-step day: one step an hour and one more at midnight, so tomorrow starts one quote further on.
        return (int) Math.floorMod(epochDay * 25 + hour, (long) size);
    }

    /** {@code "text|author"} as {text, author}; the author is "" for an entry without one. */
    static String[] split(String entry) {
        String e = entry == null ? "" : entry.trim();
        int bar = e.lastIndexOf('|');
        if (bar < 0) return new String[]{e, ""};
        return new String[]{e.substring(0, bar).trim(), e.substring(bar + 1).trim()};
    }
}
