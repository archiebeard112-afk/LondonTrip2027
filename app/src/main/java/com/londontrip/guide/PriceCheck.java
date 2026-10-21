package com.londontrip.guide;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Daily flight prices. A scheduled Claude task writes the cheapest DEN-LHR fare to prices.json on the
 * repo's "prices" branch at 4:30 AM. This class checks that file a few times between 4:30 and 5:30 AM,
 * keeps a copy for the page, and posts a notification when the new price differs from the last one.
 */
final class PriceCheck {
    static final String PRICES_URL =
            "https://raw.githubusercontent.com/archiebeard112-afk/LondonTrip2027/prices/prices.json";

    /** Check times after the 4:30 AM task, phone local time. Later ones are retries if the price isn't in yet. */
    private static final LocalTime[] SLOTS = {
            LocalTime.of(4, 40), LocalTime.of(4, 55), LocalTime.of(5, 10), LocalTime.of(5, 25)
    };
    private static final String CHANNEL_ID = "flight_prices";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREFS = "prices";
    private static final String KEY_JSON = "json";
    private static final String KEY_NOTIFIED = "notifiedDate";
    private static final String KEY_BOOKED = "flightsBooked";

    private PriceCheck() {
    }

    /** Flights ticked off as booked in the app: price checks and notifications stop. Unticking starts them again. */
    static void setBooked(Context context, boolean booked) {
        prefs(context).edit().putBoolean(KEY_BOOKED, booked).apply();
        if (booked) {
            AlarmManager am = context.getSystemService(AlarmManager.class);
            if (am != null) {
                am.cancel(checkIntent(context));
            }
        } else {
            schedule(context);
        }
    }

    private static boolean booked(Context context) {
        return prefs(context).getBoolean(KEY_BOOKED, false);
    }

    /** Sets the next morning check. Safe to call often: it replaces any check already set. */
    static void schedule(Context context) {
        if (booked(context)) {
            return;
        }
        JSONObject latest = latest(cached(context));
        scheduleNext(context, latest != null && today().equals(latest.optString("date")));
    }

    /** One check in the morning window: fetch, notify if today's price changed, then set the next check. */
    static void runMorningCheck(Context context) {
        if (booked(context)) {
            return;
        }
        String json = fetch(context);
        JSONObject latest = latest(json);
        boolean gotToday = latest != null && today().equals(latest.optString("date"));
        if (gotToday) {
            notifyIfChanged(context, json);
        }
        scheduleNext(context, gotToday);
    }

    private static void scheduleNext(Context context, boolean doneToday) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = null;
        if (!doneToday) {
            for (LocalTime slot : SLOTS) {
                ZonedDateTime t = now.with(slot);
                if (t.isAfter(now.plusSeconds(30))) {
                    next = t;
                    break;
                }
            }
        }
        if (next == null) {
            next = now.plusDays(1).with(SLOTS[0]);
        }
        // Inexact so no special permission is needed; Android may run it a few minutes late while the phone sleeps.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), checkIntent(context));
    }

    private static PendingIntent checkIntent(Context context) {
        Intent check = new Intent(context, PriceAlarmReceiver.class).setAction(PriceAlarmReceiver.ACTION_CHECK);
        return PendingIntent.getBroadcast(context, 0, check,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String today() {
        return LocalDate.now().toString();
    }

    /** The last prices.json the app downloaded, or null. */
    static String cached(Context context) {
        return prefs(context).getString(KEY_JSON, null);
    }

    /** Downloads prices.json and caches it. Returns the JSON text, or null if it couldn't be fetched. */
    static String fetch(Context context) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(PRICES_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
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

    /** Posts a notification if the newest price differs from the one before it and hasn't been shown yet. */
    static void notifyIfChanged(Context context, String json) {
        JSONObject latest = latest(json);
        JSONObject prev = previous(json);
        if (booked(context) || latest == null || prev == null || latest.optInt("price") == prev.optInt("price")) {
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
                .setContentText(detail(latest, prev))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFICATION_ID, n);
        p.edit().putString(KEY_NOTIFIED, date).apply();
    }

    private static String detail(JSONObject latest, JSONObject prev) {
        StringBuilder s = new StringBuilder();
        int diff = latest.optInt("price") - prev.optInt("price");
        s.append(diff < 0 ? "Down " : "Up ").append(money(Math.abs(diff))).append(" since last check");
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
