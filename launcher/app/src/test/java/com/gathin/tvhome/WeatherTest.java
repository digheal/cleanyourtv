package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the weather card's code mapping and request. */
public class WeatherTest {

    @Test
    public void mapsWmoCodesToGlyphKinds() {
        assertEquals(Weather.CLEAR, Weather.kind(0));
        assertEquals(Weather.PARTLY, Weather.kind(2));
        assertEquals(Weather.CLOUDY, Weather.kind(3));
        assertEquals(Weather.FOG, Weather.kind(48));
        assertEquals(Weather.DRIZZLE, Weather.kind(53));
        assertEquals(Weather.RAIN, Weather.kind(63));
        assertEquals(Weather.RAIN, Weather.kind(81));   // showers
        assertEquals(Weather.SNOW, Weather.kind(75));
        assertEquals(Weather.SNOW, Weather.kind(86));   // snow showers
        assertEquals(Weather.STORM, Weather.kind(95));
        assertEquals(Weather.CLOUDY, Weather.kind(42)); // unknown codes fall back to a cloud
    }

    @Test
    public void requestRoundsCoordinatesAndAsksForTheUnits() {
        String f = Weather.url(37.81234, -122.24567, true);
        assertTrue(f.startsWith("https://api.open-meteo.com/v1/forecast?"));
        assertTrue(f.contains("latitude=37.81&longitude=-122.25"));
        assertTrue(f.contains("temperature_unit=fahrenheit"));
        assertTrue(f.contains("forecast_days=4"));
        assertFalse(Weather.url(51.5, -0.12, false).contains("temperature_unit"));
    }
}
