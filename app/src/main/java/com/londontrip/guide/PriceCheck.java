package com.londontrip.guide;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Daily flight prices. A scheduled Claude task writes the cheapest DEN-LHR fare to prices.json on the
 * repo's "prices" branch once a day. This class downloads that file, keeps a copy for the page, and
 * posts a notification when a new day's price appears.
 */
final class PriceCheck {
    static final String PRICES_URL =
            "https://raw.githubusercontent.com/archiebeard112-afk/LondonTrip2027/prices/prices.json";

    private static final int JOB_ID = 2027;
    private static final String CHANNEL_ID = "flight_prices";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS = "prices";
    private static final String KEY_JSON = "json";
    private static final String KEY_NOTIFIED = "notifiedDate";

    private PriceCheck() {
    }

    /** Schedules the background check every few hours. Only a new day's price triggers a notification. */
    static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, PriceJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    /** The last prices.json the app downloaded, or null. */
    static String cached(Context context) {
        return prefs(context).getString(KEY_JSON, null);
    }

    /** Downloads prices.json and caches it. Returns the JSON text, or null if it couldn't be fetched. */
    static String fetch(Context context) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(PRICES_URL).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "LondonTrip-Android");
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String json = readAll(conn.getInputStream());
            new JSONObject(json); // only keep valid JSON
            prefs(context).edit().putString(KEY_JSON, json).apply();
            return json;
        } catch (Exception e) {
            return null;
        }
    }

    /** Marks the latest price as seen, so opening the app counts as being told about it. */
    static void markSeen(Context context, String json) {
        JSONObject latest = latest(json);
        if (latest != null) {
            prefs(context).edit().putString(KEY_NOTIFIED, latest.optString("date")).apply();
        }
    }

    /** Posts a notification if the downloaded file has a price for a day we haven't told the user about. */
    static void notifyIfNew(Context context, String json) {
        JSONObject latest = latest(json);
        if (latest == null) {
            return;
        }
        String date = latest.optString("date");
        SharedPreferences p = prefs(context);
        if (date.isEmpty() || date.equals(p.getString(KEY_NOTIFIED, null))) {
            return;
        }

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !nm.areNotificationsEnabled()) {
            return;
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Flight prices",
                NotificationManager.IMPORTANCE_DEFAULT));

        Intent open = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_plane)
                .setContentTitle("Flights to London: " + money(latest.optInt("price")) + " for 2")
                .setContentText(detail(json, latest))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, n);
        p.edit().putString(KEY_NOTIFIED, date).apply();
    }

    private static String detail(String json, JSONObject latest) {
        StringBuilder s = new StringBuilder();
        JSONObject prev = previous(json);
        if (prev != null) {
            int diff = latest.optInt("price") - prev.optInt("price");
            if (diff < 0) {
                s.append("Down ").append(money(-diff)).append(" since last check");
            } else if (diff > 0) {
                s.append("Up ").append(money(diff)).append(" since last check");
            } else {
                s.append("Same as last check");
            }
        } else {
            s.append("DEN to LHR round trip, Feb 19 to 28");
        }
        String airline = latest.optString("airline");
        if (!airline.isEmpty()) {
            s.append(" · ").append(airline);
        }
        return s.toString();
    }

    private static JSONObject latest(String json) {
        return entry(json, 1);
    }

    private static JSONObject previous(String json) {
        return entry(json, 2);
    }

    /** The nth entry from the end of the history list (1 is the newest). */
    private static JSONObject entry(String json, int fromEnd) {
        if (json == null) {
            return null;
        }
        try {
            JSONArray h = new JSONObject(json).optJSONArray("history");
            if (h == null || h.length() < fromEnd) {
                return null;
            }
            JSONObject e = h.getJSONObject(h.length() - fromEnd);
            return e.optInt("price") > 0 ? e : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String money(int dollars) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        f.setMaximumFractionDigits(0);
        return f.format(dollars);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
