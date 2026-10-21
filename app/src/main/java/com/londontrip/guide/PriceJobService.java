package com.londontrip.guide;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Background job that checks for a new flight price and notifies the user. */
public class PriceJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            String json = PriceCheck.fetch(this);
            PriceCheck.notifyIfNew(this, json);
            jobFinished(params, json == null);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
