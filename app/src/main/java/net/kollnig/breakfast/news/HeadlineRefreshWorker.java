package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

public class HeadlineRefreshWorker extends Worker {
    public HeadlineRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AppConfig config = new AppConfig(context);
        List<String> headlineFeeds = config.getEffectiveHeadlineFeedUrls();
        if (!config.isNewsModuleEnabled() || headlineFeeds.isEmpty()) {
            return Result.success();
        }

        long now = System.currentTimeMillis();
        if (config.isNewsRefreshOnOpenEnabled()) {
            return Result.success();
        }
        int refreshHour = config.getMorningRefreshHour();
        int refreshMinute = config.getMorningRefreshMinute();
        List<ArticleData> fetched = new RssFetcher().fetchAllFeeds(headlineFeeds);
        List<ArticleData> merged = NewsCache.mergeRollingWindow(
                config.getCachedHeadlinePool(),
                fetched,
                now
        );
        config.setCachedHeadlinePool(merged);
        long currentDeliveryWindowStart = NewsCache.computeWindowStart(now, refreshHour, refreshMinute);
        if (config.getHeadlinesLastRefresh() < currentDeliveryWindowStart) {
            config.setCachedHeadlines(NewsCache.snapshotForDeliveryWindow(
                    merged,
                    now,
                    refreshHour,
                    refreshMinute
            ));
            config.setHeadlinesLastRefresh(currentDeliveryWindowStart);
        }
        return Result.success();
    }
}
