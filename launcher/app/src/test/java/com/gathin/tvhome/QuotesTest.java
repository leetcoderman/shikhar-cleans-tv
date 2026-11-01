package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Plain JVM tests for the time-of-day quotes: the parts of the day and which quote each hour shows. */
public class QuotesTest {

    @Test
    public void partsOfTheDay() {
        assertEquals(Quotes.NIGHT, Quotes.partOfDay(0));
        assertEquals(Quotes.NIGHT, Quotes.partOfDay(4));
        assertEquals(Quotes.MORNING, Quotes.partOfDay(5));
        assertEquals(Quotes.MORNING, Quotes.partOfDay(11));
        assertEquals(Quotes.AFTERNOON, Quotes.partOfDay(12));
        assertEquals(Quotes.AFTERNOON, Quotes.partOfDay(16));
        assertEquals(Quotes.EVENING, Quotes.partOfDay(17));
        assertEquals(Quotes.EVENING, Quotes.partOfDay(20));
        assertEquals(Quotes.NIGHT, Quotes.partOfDay(21));
        assertEquals(Quotes.NIGHT, Quotes.partOfDay(23));
    }

    @Test
    public void aNewQuoteEveryHourAndNotTheSameOneAcrossMidnight() {
        long day = 20_750L;
        for (int hour = 0; hour < 23; hour++) {
            assertNotEquals(Quotes.index(7, day, hour), Quotes.index(7, day, hour + 1));
        }
        assertNotEquals(Quotes.index(7, day, 23), Quotes.index(7, day + 1, 0));
        assertNotEquals(Quotes.index(8, day, 23), Quotes.index(8, day + 1, 0));
    }

    @Test
    public void eachDayStartsOneFurtherOn() {
        long day = 20_750L;
        assertEquals((Quotes.index(8, day, 5) + 1) % 8, Quotes.index(8, day + 1, 5));
    }

    @Test
    public void indexStaysInTheList() {
        for (long day = -3; day < 400; day += 7) {
            for (int hour = 0; hour < 24; hour++) {
                int i = Quotes.index(5, day, hour);
                assertEquals(true, i >= 0 && i < 5);
            }
        }
        assertEquals(-1, Quotes.index(0, 20_750L, 9));
    }

    @Test
    public void entriesSplitIntoTextAndAuthor() {
        assertArrayEquals(new String[]{"Simplify, simplify.", "Thoreau"}, Quotes.split("Simplify, simplify.|Thoreau"));
        assertArrayEquals(new String[]{"Rest is not idleness.", ""}, Quotes.split(" Rest is not idleness. "));
        assertArrayEquals(new String[]{"", ""}, Quotes.split(null));
    }
}
