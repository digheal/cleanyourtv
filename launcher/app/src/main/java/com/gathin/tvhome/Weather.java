package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional weather card under the clock: now, today's high and low, and the next three days.
 *
 * Off unless a theme sets weather_latitude and weather_longitude. The forecast comes from Open-Meteo (no key, no
 * account), which receives only those coordinates, rounded to two decimals. It is fetched only while Home is
 * resumed, at most every 30 minutes, and the last answer is kept so Home opens with it instead of an empty card.
 */
final class Weather {
    static final int CLEAR = 0, PARTLY = 1, CLOUDY = 2, FOG = 3, DRIZZLE = 4, RAIN = 5, SNOW = 6, STORM = 7;

    private static final String TAG = "TvHome.Weather";
    private static final long REFRESH_MS = 30 * 60_000L;
    /** After a failed fetch (Wi-Fi still coming up, say), try again sooner than a full refresh. */
    private static final long RETRY_MS = 5 * 60_000L;
    /** An answer older than this is not shown: yesterday's "now" would be wrong. */
    private static final long STALE_MS = 6 * 60 * 60_000L;
    private static final int DAYS = 3;
    private static final int MAX_BYTES = 64 * 1024;

    private final View card;
    private final ImageView icon;
    private final TextView temp;
    private final TextView summary;
    private final TextView detail;
    private final View divider;
    private final LinearLayout days;
    private final String url;
    private final String place;
    private final SharedPreferences cache;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private boolean active;
    private boolean closed;
    private boolean loading;
    private long lastAttempt;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshIfDue();
            main.postDelayed(this, RETRY_MS);
        }
    };

    Weather(Context ctx, View root, Config cfg) {
        card = root.findViewById(R.id.weatherCard);
        icon = root.findViewById(R.id.weatherIcon);
        temp = root.findViewById(R.id.weatherTemp);
        summary = root.findViewById(R.id.weatherSummary);
        detail = root.findViewById(R.id.weatherDetail);
        divider = root.findViewById(R.id.weatherDivider);
        days = root.findViewById(R.id.weatherDays);
        density = ctx.getResources().getDisplayMetrics().density;
        cache = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
        boolean on = !Double.isNaN(cfg.weatherLatitude) && !Double.isNaN(cfg.weatherLongitude);
        url = on ? url(cfg.weatherLatitude, cfg.weatherLongitude, cfg.weatherFahrenheit) : null;
        place = cfg.weatherPlace;
        if (url != null) showCached();
    }

    void start() {
        if (url == null || closed) return;
        active = true;
        main.removeCallbacks(tick);
        tick.run();
    }

    void stop() {
        active = false;
        main.removeCallbacks(tick);
    }

    void close() {
        stop();
        closed = true;
        net.shutdownNow();
    }

    /** Weather code (WMO, as Open-Meteo reports it) to one of the card's glyph kinds. */
    static int kind(int code) {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return DRIZZLE;
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95 && code <= 99) return STORM;
        return CLOUDY;
    }

    /** The request. Coordinates are rounded to about a kilometre: the forecast does not need more. */
    static String url(double latitude, double longitude, boolean fahrenheit) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                + "&current=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=%d%s", latitude, longitude, DAYS + 1,
                fahrenheit ? "&temperature_unit=fahrenheit" : "");
    }

    private void refreshIfDue() {
        if (!active || loading) return;
        long now = System.currentTimeMillis();
        long fetched = url.equals(cache.getString("url", "")) ? cache.getLong("at", 0) : 0;
        if (now - fetched < REFRESH_MS || now - lastAttempt < RETRY_MS) return;
        lastAttempt = now;
        loading = true;
        net.execute(() -> {
            String body = null;
            try {
                body = get(url);
                parse(new JSONObject(body));   // only a usable answer replaces the cached one
            } catch (IOException | JSONException | RuntimeException e) {
                Log.w(TAG, "forecast unavailable: " + e);
                body = null;
            }
            String result = body;
            main.post(() -> {
                loading = false;
                if (closed || result == null) return;
                cache.edit().putString("url", url).putString("json", result)
                        .putLong("at", System.currentTimeMillis()).apply();
                showCached();
            });
        });
    }

    private void showCached() {
        String json = cache.getString("json", null);
        long at = cache.getLong("at", 0);
        if (json == null || !url.equals(cache.getString("url", ""))
                || System.currentTimeMillis() - at > STALE_MS) {
            card.setVisibility(View.GONE);
            return;
        }
        try {
            show(parse(new JSONObject(json)));
        } catch (JSONException | RuntimeException e) {
            card.setVisibility(View.GONE);
        }
    }

    private void show(Forecast f) {
        Context ctx = card.getContext();
        icon.setImageResource(glyph(f.kind, f.day));
        temp.setText(ctx.getString(R.string.weather_temp, f.temperature));
        String condition = ctx.getString(label(f.kind));
        summary.setText(place.isEmpty() ? condition : ctx.getString(R.string.weather_today, place, condition));
        detail.setText(ctx.getString(R.string.weather_high_low, f.high[0], f.low[0]));
        days.removeAllViews();
        int shown = Math.min(DAYS, f.dates.length - 1);
        for (int i = 1; i <= shown; i++) days.addView(dayColumn(ctx, f, i));
        divider.setVisibility(shown > 0 ? View.VISIBLE : View.GONE);
        card.setVisibility(View.VISIBLE);
    }

    private View dayColumn(Context ctx, Forecast f, int i) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (12 * density);
        col.setPadding(pad, 0, pad, 0);

        TextView name = text(ctx, R.font.outfit_medium, 11, 0xA6FFFFFF);
        name.setLetterSpacing(0.14f);
        String day = LocalDate.parse(f.dates[i]).getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault());
        name.setText(day.toUpperCase(Locale.getDefault()));
        col.addView(name, wrap());

        ImageView glyph = new ImageView(ctx);
        glyph.setImageResource(glyph(f.dayKinds[i], true));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = (int) (24 * density);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.topMargin = lp.bottomMargin = (int) (3 * density);
        col.addView(glyph, lp);

        TextView range = text(ctx, R.font.outfit_regular, 13, 0xE6FFFFFF);
        range.setText(ctx.getString(R.string.weather_temp, f.high[i]) + "  "
                + ctx.getString(R.string.weather_temp, f.low[i]));
        col.addView(range, wrap());
        return col;
    }

    /** Children size to their text: a vertical LinearLayout would otherwise stretch them to its first measure. */
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static TextView text(Context ctx, int font, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setTypeface(ctx.getResources().getFont(font));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setMaxLines(1);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static int glyph(int kind, boolean day) {
        switch (kind) {
            case CLEAR: return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
            case PARTLY: return day ? R.drawable.ic_wx_sun_cloud : R.drawable.ic_wx_moon_cloud;
            case FOG: return R.drawable.ic_wx_fog;
            case DRIZZLE:
            case RAIN: return R.drawable.ic_wx_rain;
            case SNOW: return R.drawable.ic_wx_snow;
            case STORM: return R.drawable.ic_wx_storm;
            default: return R.drawable.ic_wx_cloud;
        }
    }

    private static int label(int kind) {
        switch (kind) {
            case CLEAR: return R.string.weather_clear;
            case PARTLY: return R.string.weather_partly;
            case FOG: return R.string.weather_fog;
            case DRIZZLE: return R.string.weather_drizzle;
            case RAIN: return R.string.weather_rain;
            case SNOW: return R.string.weather_snow;
            case STORM: return R.string.weather_storm;
            default: return R.string.weather_cloudy;
        }
    }

    /** What the card shows, read from one Open-Meteo answer. Index 0 of the daily arrays is today. */
    static final class Forecast {
        int temperature;
        int kind;
        boolean day;
        String[] dates;
        int[] dayKinds;
        int[] high;
        int[] low;
    }

    static Forecast parse(JSONObject o) throws JSONException {
        Forecast f = new Forecast();
        JSONObject now = o.getJSONObject("current");
        f.temperature = (int) Math.round(now.getDouble("temperature_2m"));
        f.kind = kind(now.getInt("weather_code"));
        f.day = now.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        JSONArray time = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        int n = Math.min(Math.min(time.length(), codes.length()), Math.min(max.length(), min.length()));
        if (n == 0) throw new JSONException("no daily forecast");
        f.dates = new String[n];
        f.dayKinds = new int[n];
        f.high = new int[n];
        f.low = new int[n];
        for (int i = 0; i < n; i++) {
            f.dates[i] = time.getString(i);
            f.dayKinds[i] = kind(codes.getInt(i));
            f.high[i] = (int) Math.round(max.getDouble(i));
            f.low[i] = (int) Math.round(min.getDouble(i));
        }
        return f;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(10_000);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                for (int r; (r = in.read(buf)) > 0; ) {
                    out.write(buf, 0, r);
                    if (out.size() > MAX_BYTES) throw new IOException("answer too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
