package com.gathin.tvhome;

/**
 * Wallpaper blur for glass themes (config.json wallpaper_blur). Done once per picture, not per frame: the
 * wallpaper is shrunk, box-blurred three times over (close to a Gaussian), and the ImageView's bilinear scaling
 * back to full screen smooths it the rest of the way. No RenderEffect, so it costs the same on every TV.
 *
 * Pure maths on packed ARGB pixels, so it can be tested on the JVM.
 */
final class Blur {

    private Blur() {
    }

    /** How many times smaller the picture is made for a blur amount of 0 to 100; 1 means no blur. */
    static int shrink(int amount) {
        if (amount <= 0) return 1;
        return 4 + Math.min(100, amount) / 5;   // 4x at the lightest, 24x at 100
    }

    /** Box radius used on the shrunk picture. */
    static int radius(int amount) {
        return amount <= 0 ? 0 : 1 + Math.min(100, amount) / 50;
    }

    /** Blurs the pixels in place: three horizontal and vertical box passes of the given radius. */
    static void box(int[] px, int w, int h, int r) {
        if (r <= 0 || w <= 0 || h <= 0) return;
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            line(px, tmp, w, h, r, true);
            line(tmp, px, w, h, r, false);
        }
    }

    /** One box pass along rows (horizontal) or columns, edges clamped, from src into dst. */
    private static void line(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w;
        int len = horizontal ? w : h;
        int step = horizontal ? 1 : w;
        int window = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = horizontal ? l * w : l;
            int a = 0, red = 0, g = 0, b = 0;
            for (int k = -r; k <= r; k++) {
                int c = src[base + clamp(k, len) * step];
                a += c >>> 24;
                red += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = (a / window) << 24 | (red / window) << 16 | (g / window) << 8 | (b / window);
                int out = src[base + clamp(i - r, len) * step];
                int in = src[base + clamp(i + r + 1, len) * step];
                a += (in >>> 24) - (out >>> 24);
                red += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                g += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                b += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }
}
