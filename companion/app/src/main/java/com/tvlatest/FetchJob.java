package com.tvlatest;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Runs the fetch once a day whenever the TV has a network, and once right away when the data is stale. */
public class FetchJob extends JobService {
    private static final int DAILY = 1, NOW = 2;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    /** Schedules the daily job if it is missing, and an immediate one if the last fetch is over a day old. */
    static void ensure(Context c) {
        try {
            schedule(c);
        } catch (RuntimeException e) {
            // Never let scheduling break the provider the launcher reads, or this screen.
            android.util.Log.w("TvLatest", "schedule failed", e);
        }
    }

    private static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName me = new ComponentName(c, FetchJob.class);
        if (js.getPendingJob(DAILY) == null) {
            js.schedule(new JobInfo.Builder(DAILY, me)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(DAY_MS, 2L * 60 * 60 * 1000)
                    .setPersisted(true)
                    .build());
        }
        if (System.currentTimeMillis() - Store.updated(c) > DAY_MS && js.getPendingJob(NOW) == null) {
            now(c);
        }
    }

    /** A fetch as soon as there is a network, e.g. after the key or regions changed. */
    static void now(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (Store.key(c).isEmpty()) return;
        js.schedule(new JobInfo.Builder(NOW, new ComponentName(c, FetchJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    private static final java.util.concurrent.atomic.AtomicBoolean refreshing =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Refresh pressed: fetch right away on a thread of its own; the provider says when the data is new. */
    static boolean refreshNow(Context c) {
        if (Store.key(c).isEmpty() || !refreshing.compareAndSet(false, true)) return false;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Fetcher.run(app);
            } finally {
                refreshing.set(false);
                // Also when nothing came back, so a spinner stops.
                app.getContentResolver().notifyChange(LatestProvider.TOP, null);
            }
        }, "tmdb-refresh").start();
        return true;
    }

    static boolean refreshing() {
        return refreshing.get();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean ok = Fetcher.run(getApplicationContext());
            if (ok) getContentResolver().notifyChange(LatestProvider.TOP, null);
            jobFinished(params, !ok && params.getJobId() == NOW && !Store.key(this).isEmpty());
        }, "tmdb-fetch").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
