package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the wallpaper blur: amounts, flat areas and edges. */
public class BlurTest {

    @Test
    public void zeroAmountMeansNoBlur() {
        assertEquals(1, Blur.shrink(0));
        assertEquals(0, Blur.radius(0));
        assertTrue(Blur.shrink(100) > Blur.shrink(20));
    }

    @Test
    public void flatColourStaysTheSame() {
        int[] px = new int[12 * 8];
        java.util.Arrays.fill(px, 0xFF336699);
        Blur.box(px, 12, 8, 2);
        for (int c : px) assertEquals(0xFF336699, c);
    }

    @Test
    public void hardEdgeBecomesAGradient() {
        int w = 20, h = 4;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[y * w + x] = x < w / 2 ? 0xFF000000 : 0xFFFFFFFF;
        Blur.box(px, w, h, 2);
        int left = px[0] & 0xFF, mid = px[w / 2] & 0xFF, right = px[w - 1] & 0xFF;
        assertEquals(0, left);
        assertEquals(255, right);
        assertTrue(mid > 0 && mid < 255);
        assertEquals(0xFF, px[w / 2] >>> 24);   // alpha untouched on an opaque picture
    }
}
