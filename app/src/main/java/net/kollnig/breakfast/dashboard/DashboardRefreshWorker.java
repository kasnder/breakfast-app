package net.kollnig.breakfast.dashboard;

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

import java.util.ArrayList;
import java.util.List;

public class DashboardRefreshWorker extends Worker {
    public DashboardRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AppConfig config = new AppConfig(context);
        boolean refreshedAnything = false;
        List<String> refreshedModules = new ArrayList<>();

        if (config.isWeatherModuleEnabled() && !config.getCity().isEmpty()) {
            WeatherData weather = new WeatherClient().fetchWeather(config.getCity());
            if (weather != null) {
                config.setCachedWeather(weather);
                refreshedAnything = true;
                refreshedModules.add("weather");
            }
        }

        List<String> headlineFeeds = config.getEffectiveHeadlineFeedUrls();
        List<String> topStoryFeeds = config.isTopStoriesAvailable()
                ? config.getTopStoryFeedUrls()
                : new ArrayList<>();
        if (config.isNewsModuleEnabled() && (!headlineFeeds.isEmpty() || !topStoryFeeds.isEmpty())) {
            if (!headlineFeeds.isEmpty()) {
                long now = System.currentTimeMillis();
                int refreshHour = config.getMorningRefreshHour();
                int refreshMinute = config.getMorningRefreshMinute();
                List<ArticleData> headlineArticles = new RssFetcher().fetchAllFeeds(headlineFeeds);
                List<ArticleData> headlinePool = NewsCache.mergeSinceWindowStart(
                        config.getCachedHeadlinePool(),
                        headlineArticles,
                        now,
                        refreshHour,
                        refreshMinute
                );
                config.setCachedHeadlinePool(headlinePool);
                config.setCachedHeadlines(NewsCache.snapshotSinceWindowStart(
                        headlinePool,
                        now,
                        refreshHour,
                        refreshMinute
                ));
                config.setHeadlinesLastRefresh(now);
                refreshedAnything = true;
            }

            if (!topStoryFeeds.isEmpty()) {
                List<ArticleData> allArticles = new RssFetcher().fetchAllFeeds(topStoryFeeds);
                if (allArticles.isEmpty()) {
                    // Preserve existing curated cache if the selected top-story feeds are empty right now.
                    return finishWork(config, refreshedAnything, refreshedModules, context);
                }
                List<ArticleData> topArticles = allArticles;
                allArticles.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
                int maxArticles = Math.min(config.getArticleCount(), allArticles.size());
                topArticles = new ArrayList<>(allArticles.subList(0, maxArticles));

                if (config.isTopStoriesAvailable()) {
                    try {
                        if (config.isOnDeviceLlmReady()) {
                            final DashboardNotifier notifier = new DashboardNotifier(context);
                            OnDeviceLlmClient onDevice = new OnDeviceLlmClient(
                                    context,
                                    config.getOnDeviceModelPath(),
                                    config.isOnDeviceUseGpu(),
                                    config.isOnDeviceBatchingEnabled());
                            try {
                                onDevice.initialize();
                                topArticles = onDevice.rankAndSummarize(
                                        allArticles,
                                        config.getInterestProfile(),
                                        config.getArticleCount(),
                                        new OnDeviceLlmClient.ProgressListener() {
                                            @Override
                                            public void onScoringStarted(int total) {
                                                notifier.showProcessingNotification(
                                                        context.getString(
                                                                R.string.llm_processing_weighting,
                                                                0, total),
                                                        0, total);
                                            }

                                            @Override
                                            public void onArticleScored(int scored, int total) {
                                                notifier.showProcessingNotification(
                                                        context.getString(
                                                                R.string.llm_processing_weighting,
                                                                scored, total),
                                                        scored, total);
                                            }

                                            @Override
                                            public void onSummarizingStarted(int total) {
                                                notifier.showProcessingNotification(
                                                        context.getString(
                                                                R.string.llm_processing_summarising,
                                                                0, total),
                                                        0, total);
                                            }

                                            @Override
                                            public void onArticleSummarized(int summarized, int total) {
                                                notifier.showProcessingNotification(
                                                        context.getString(
                                                                R.string.llm_processing_summarising,
                                                                summarized, total),
                                                        summarized, total);
                                            }
                                        });
                            } finally {
                                notifier.cancelProcessingNotification();
                                onDevice.close();
                            }
                        } else {
                            LlmClient llm = new LlmClient(
                                    config.getLlmBaseUrl(),
                                    config.getLlmApiKey(),
                                    config.getLlmModel());
                            topArticles = llm.rankAndSummarize(
                                    allArticles,
                                    config.getInterestProfile(),
                                    config.getArticleCount());
                        }
                    } catch (Exception ignored) {
                        // Keep fallback top articles from the feed sort if AI is unavailable.
                    }
                }

                config.setCachedArticles(topArticles);
                config.setArticlesFetchDate();
                refreshedAnything = true;
                refreshedModules.add("news");
            }
        }

        return finishWork(config, refreshedAnything, refreshedModules, context);
    }

    private Result finishWork(AppConfig config, boolean refreshedAnything, List<String> refreshedModules, Context context) {
        if (!refreshedAnything) {
            return Result.success();
        }

        config.setDashboardLastRefresh(System.currentTimeMillis());
        if (config.isMorningNotificationEnabled()) {
            new DashboardNotifier(context).showReadyNotification(buildSummary(config, refreshedModules));
        }
        return Result.success();
    }

    private String buildSummary(AppConfig config, List<String> refreshedModules) {
        List<String> parts = new ArrayList<>();
        if (refreshedModules.contains("weather") && config.getCachedWeather() != null) {
            WeatherData weather = config.getCachedWeather();
            parts.add(String.format("%s %.0f°", weather.cityName, weather.temperature));
        }
        if (refreshedModules.contains("news")) {
            int count = config.getCachedArticles().size();
            if (count > 0) {
                parts.add(count == 1 ? "1 story" : count + " stories");
            }
        }
        if (parts.isEmpty()) {
            return "Your morning dashboard has been refreshed.";
        }
        return String.join(" • ", parts);
    }
}
