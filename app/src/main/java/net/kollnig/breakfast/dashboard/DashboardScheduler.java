package net.kollnig.breakfast.dashboard;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public final class DashboardScheduler {
    private static final String MORNING_WORK_NAME = "breakfast_morning_refresh";
    private static final String HEADLINE_WORK_NAME = "breakfast_headline_refresh";

    private DashboardScheduler() {}

    public static void scheduleMorningRefresh(Context context) {
        AppConfig config = new AppConfig(context);
        long initialDelay = computeInitialDelayMs(config.getMorningRefreshHour(), config.getMorningRefreshMinute());
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DashboardRefreshWorker.class,
                24,
                TimeUnit.HOURS
        ).setInitialDelay(initialDelay, TimeUnit.MILLISECONDS).build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                MORNING_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );

        if (config.isNewsRefreshOnOpenEnabled()) {
            WorkManager.getInstance(context).cancelUniqueWork(HEADLINE_WORK_NAME);
        } else {
            scheduleHeadlineRefresh(context);
        }
    }

    private static void scheduleHeadlineRefresh(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                HeadlineRefreshWorker.class,
                1,
                TimeUnit.HOURS
        ).build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HEADLINE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    private static long computeInitialDelayMs(int hourOfDay, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.HOUR_OF_DAY, hourOfDay);
        nextRun.set(Calendar.MINUTE, minute);
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);
        if (!nextRun.after(now)) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }
        return nextRun.getTimeInMillis() - now.getTimeInMillis();
    }
}
