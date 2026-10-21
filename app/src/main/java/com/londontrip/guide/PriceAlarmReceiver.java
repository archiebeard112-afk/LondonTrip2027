package com.londontrip.guide;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Runs the early-morning price check, and sets the next one up again after the phone restarts. */
public class PriceAlarmReceiver extends BroadcastReceiver {
    static final String ACTION_CHECK = "com.londontrip.guide.CHECK_PRICE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            PriceCheck.schedule(context);
            return;
        }
        if (!ACTION_CHECK.equals(action)) {
            return;
        }
        PendingResult done = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                PriceCheck.runMorningCheck(app);
            } finally {
                done.finish();
            }
        }).start();
    }
}
