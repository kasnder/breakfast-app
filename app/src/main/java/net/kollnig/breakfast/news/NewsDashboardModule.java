package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

public class NewsDashboardModule {
    private static final String TAG = "NewsDashboardModule";

    public interface MainThreadPoster {
        void post(Runnable r);
    }

    public interface RelativeTimeFormatter {
        String format(long timestamp);
    }

    public interface InvalidateOptionsMenuCallback {
        void invalidateMenu();
    }

    public interface BriefingDataProvider {
        String getCalendarSummary();
        String getEmailSummary();
        String getSocialSummary();
        WeatherData getCachedWeather();
    }

    private final Activity activity;
    private final AppConfig config;
    private final ExecutorService executor;
    private final MainThreadPoster mainThreadPoster;
    private final RelativeTimeFormatter relativeTimeFormatter;
    private final InvalidateOptionsMenuCallback invalidateMenuCallback;
    private final BriefingDataProvider briefingDataProvider;
    private final ArticleImageLoader articleImageLoader;

    // News card views
    private final TextView newsStatus;
    private final TextView newsWarning;
    private final TextView newsResetHint;
    private final ProgressBar newsLoading;
    private final LinearLayout newsContainer;
    private final MaterialButton btnNewsShowAll;

    // Headlines card views
    private final TextView headlinesStatus;
    private final TextView headlinesHint;
    private final ProgressBar headlinesLoading;
    private final LinearLayout headlinesContainer;
    private final MaterialButton btnHeadlinesShowAll;

    // Briefing audio views
    private final TextView briefingAudioStatus;
    private final ProgressBar briefingAudioProgress;

    // Article data
    private List<ArticleData> currentBriefingArticles = new ArrayList<>();
    private List<ArticleData> currentHeadlineArticles = new ArrayList<>();
    private boolean briefingExpanded;
    private boolean headlinesExpanded;

    // Briefing state
    private boolean briefingScriptGenerationInProgress;
    private int briefingScriptGenerationRequestId;

    // Speaker instances
    private MorningBriefingSpeaker morningBriefingSpeaker;
    private OpenAiBriefingSpeaker openAiBriefingSpeaker;

    public NewsDashboardModule(Activity activity, View cardNews, View cardHeadlines,
                              AppConfig config, ExecutorService executor,
                              MainThreadPoster mainThreadPoster,
                              RelativeTimeFormatter relativeTimeFormatter,
                              InvalidateOptionsMenuCallback invalidateMenuCallback,
                              BriefingDataProvider briefingDataProvider) {
        this.activity = activity;
        this.config = config;
        this.executor = executor;
        this.mainThreadPoster = mainThreadPoster;
        this.relativeTimeFormatter = relativeTimeFormatter;
        this.invalidateMenuCallback = invalidateMenuCallback;
        this.briefingDataProvider = briefingDataProvider;
        this.articleImageLoader = ArticleImageLoader.getInstance();

        // Initialize news card views
        this.newsStatus = cardNews.findViewById(R.id.news_status);
        this.newsWarning = cardNews.findViewById(R.id.news_warning);
        this.newsResetHint = cardNews.findViewById(R.id.news_reset_hint);
        this.briefingAudioStatus = cardNews.findViewById(R.id.briefing_audio_status);
        this.briefingAudioProgress = cardNews.findViewById(R.id.briefing_audio_progress);
        this.newsLoading = cardNews.findViewById(R.id.news_loading);
        this.newsContainer = cardNews.findViewById(R.id.news_articles_container);
        this.btnNewsShowAll = cardNews.findViewById(R.id.btn_news_show_all);

        // Initialize headlines card views
        this.headlinesStatus = cardHeadlines.findViewById(R.id.headlines_status);
        this.headlinesHint = cardHeadlines.findViewById(R.id.headlines_hint);
        this.headlinesLoading = cardHeadlines.findViewById(R.id.headlines_loading);
        this.headlinesContainer = cardHeadlines.findViewById(R.id.headlines_articles_container);
        this.btnHeadlinesShowAll = cardHeadlines.findViewById(R.id.btn_headlines_show_all);

        btnNewsShowAll.setOnClickListener(v -> {
            briefingExpanded = !briefingExpanded;
            showArticles(currentBriefingArticles);
        });
        btnHeadlinesShowAll.setOnClickListener(v -> {
            headlinesExpanded = !headlinesExpanded;
            showHeadlines(currentHeadlineArticles);
        });

        // Initialize briefing speakers
        initializeBriefingSpeakers();
    }

    private void initializeBriefingSpeakers() {
        morningBriefingSpeaker = new MorningBriefingSpeaker(activity, new MorningBriefingSpeaker.Listener() {
            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                mainThreadPoster.post(() -> updateBriefingPlaybackUi(isPlaying));
            }

            @Override
            public void onUnavailable() {
                mainThreadPoster.post(() -> {
                    briefingScriptGenerationInProgress = false;
                    updateBriefingPlaybackUi(false);
                    briefingAudioProgress.setVisibility(View.GONE);
                    briefingAudioStatus.setText(R.string.briefing_audio_unavailable);
                    briefingAudioStatus.setVisibility(View.VISIBLE);
                    Toast.makeText(activity,
                            R.string.briefing_audio_unavailable,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });

        openAiBriefingSpeaker = new OpenAiBriefingSpeaker(activity, new OpenAiBriefingSpeaker.Listener() {
            @Override
            public void onPlaybackStateChanged(boolean isPlaying) {
                mainThreadPoster.post(() -> updateBriefingPlaybackUi(isPlaying));
            }

            @Override
            public void onUnavailable(String message) {
                mainThreadPoster.post(() -> {
                    briefingScriptGenerationInProgress = false;
                    updateBriefingPlaybackUi(false);
                    briefingAudioProgress.setVisibility(View.GONE);
                    briefingAudioStatus.setText(message);
                    briefingAudioStatus.setVisibility(View.VISIBLE);
                    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                    if (config.isBriefingUseOpenAiTtsEnabled()) {
                        boolean fallbackStarted = morningBriefingSpeaker.toggle(buildBriefingTranscript());
                        if (fallbackStarted) {
                            syncBriefingPlaybackStatus();
                        } else {
                            briefingAudioProgress.setVisibility(View.GONE);
                            briefingAudioStatus.setText(R.string.briefing_audio_unavailable);
                            briefingAudioStatus.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    public void loadCachedArticles() {
        if (!config.isTopStoriesAvailable()) {
            newsStatus.setText("AI Briefing needs a cloud API or on-device model. Latest From Your Feeds will still use all feed sources.");
            newsStatus.setVisibility(View.VISIBLE);
            newsWarning.setVisibility(View.GONE);
            newsResetHint.setVisibility(View.GONE);
            newsLoading.setVisibility(View.GONE);
            newsContainer.removeAllViews();
            btnNewsShowAll.setVisibility(View.GONE);
            currentBriefingArticles = new ArrayList<>();
            updateBriefingAvailability();
            return;
        }

        List<ArticleData> cached = config.getCachedArticles();
        if (!cached.isEmpty()) {
            showArticles(cached);
            newsResetHint.setVisibility(View.VISIBLE);
        } else if (config.getTopStoryFeedUrls().isEmpty()) {
            newsStatus.setText("Choose at least one feed for AI Briefing in Settings");
            newsStatus.setVisibility(View.VISIBLE);
            newsResetHint.setVisibility(View.GONE);
        } else {
            newsStatus.setText("Your next scheduled refresh will fill this card");
            newsStatus.setVisibility(View.VISIBLE);
            newsResetHint.setVisibility(View.GONE);
        }
        currentBriefingArticles = new ArrayList<>(cached);
        updateBriefingAvailability();
    }

    public void loadCachedHeadlines() {
        List<ArticleData> cached = config.getCachedHeadlines();
        headlinesHint.setText(buildHeadlineWindowHint());

        if (!cached.isEmpty()) {
            showHeadlines(cached);
            headlinesHint.setVisibility(View.VISIBLE);
        } else if (config.getEffectiveHeadlineFeedUrls().isEmpty()) {
            headlinesStatus.setText("Add RSS or Atom feeds in Settings to get started");
            headlinesStatus.setVisibility(View.VISIBLE);
            headlinesHint.setVisibility(View.GONE);
        } else {
            headlinesStatus.setText("Your next scheduled refresh will fill this card");
            headlinesStatus.setVisibility(View.VISIBLE);
            headlinesHint.setVisibility(View.GONE);
            headlinesContainer.removeAllViews();
            headlinesLoading.setVisibility(View.GONE);
            btnHeadlinesShowAll.setVisibility(View.GONE);
        }
        currentHeadlineArticles = new ArrayList<>(cached);
    }

    public void loadHeadlines() {
        List<String> feeds = config.getEffectiveHeadlineFeedUrls();
        if (feeds.isEmpty()) {
            headlinesStatus.setText("Choose at least one feed for Latest From Your Feeds in Settings");
            headlinesStatus.setVisibility(View.VISIBLE);
            headlinesHint.setVisibility(View.GONE);
            return;
        }

        headlinesStatus.setVisibility(View.GONE);
        headlinesHint.setVisibility(View.GONE);
        headlinesLoading.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                int refreshHour = config.getMorningRefreshHour();
                int refreshMinute = config.getMorningRefreshMinute();
                List<ArticleData> fetched = new RssFetcher().fetchAllFeeds(feeds);
                List<ArticleData> headlinePool = NewsCache.mergeRollingWindow(
                        config.getCachedHeadlinePool(),
                        fetched,
                        now
                );
                config.setCachedHeadlinePool(headlinePool);
                long currentDeliveryWindowStart = NewsCache.computeWindowStart(
                        now,
                        refreshHour,
                        refreshMinute
                );
                List<ArticleData> displayArticles;
                boolean newWindow = config.getHeadlinesLastRefresh() < currentDeliveryWindowStart;
                boolean cacheEmpty = config.getCachedHeadlines().isEmpty();
                if (newWindow || cacheEmpty) {
                    displayArticles = NewsCache.snapshotSinceWindowStart(
                            headlinePool,
                            now,
                            refreshHour,
                            refreshMinute
                    );
                    config.setCachedHeadlines(displayArticles);
                    config.setHeadlinesLastRefresh(currentDeliveryWindowStart);
                } else {
                    displayArticles = config.getCachedHeadlines();
                }

                List<ArticleData> finalDisplayArticles = displayArticles;
                mainThreadPoster.post(() -> showHeadlines(finalDisplayArticles));
            } catch (Exception e) {
                Log.e(TAG, "Error loading rolling headlines", e);
                mainThreadPoster.post(() -> {
                    headlinesLoading.setVisibility(View.GONE);
                    if (headlinesContainer.getChildCount() == 0) {
                        headlinesStatus.setText("Error loading your latest-feed list");
                        headlinesStatus.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    public void loadNews() {
        if (!config.isTopStoriesAvailable()) {
            newsLoading.setVisibility(View.GONE);
            newsContainer.removeAllViews();
            newsWarning.setVisibility(View.GONE);
            newsResetHint.setVisibility(View.GONE);
            btnNewsShowAll.setVisibility(View.GONE);
            newsStatus.setText("AI Briefing needs a cloud API or on-device model. Latest From Your Feeds will still use all feed sources.");
            newsStatus.setVisibility(View.VISIBLE);
            return;
        }

        List<String> feeds = config.getTopStoryFeedUrls();
        if (feeds.isEmpty()) {
            newsStatus.setText("Choose at least one feed for AI Briefing in Settings");
            newsStatus.setVisibility(View.VISIBLE);
            newsWarning.setVisibility(View.GONE);
            newsResetHint.setVisibility(View.GONE);
            return;
        }

        newsStatus.setVisibility(View.GONE);
        newsWarning.setVisibility(View.GONE);
        newsResetHint.setVisibility(View.GONE);
        newsLoading.setVisibility(View.VISIBLE);
        newsContainer.removeAllViews();

        executor.execute(() -> {
            try {
                RssFetcher fetcher = new RssFetcher();
                List<ArticleData> allArticles = fetcher.fetchAllFeeds(feeds);
                int rssFailed = fetcher.getFailedFeedCount();
                int rssTotal = feeds.size();

                if (allArticles.isEmpty()) {
                    mainThreadPoster.post(() -> {
                        newsLoading.setVisibility(View.GONE);
                        if (rssFailed == rssTotal) {
                            newsStatus.setText("Failed to load feed sources");
                        } else {
                            newsStatus.setText("No recent articles found for your AI briefing");
                        }
                        newsStatus.setVisibility(View.VISIBLE);
                        if (rssFailed > 0 && rssFailed < rssTotal) {
                            newsWarning.setText("Failed to load " + rssFailed
                                    + " of " + rssTotal + " feed sources");
                            newsWarning.setVisibility(View.VISIBLE);
                        }
                    });
                    return;
                }

                // Run benchmark comparison if enabled (before the main path)
                if (config.isLlmBenchmarkEnabled()) {
                    LlmBenchmark.compare(activity, config, allArticles);
                }

                List<ArticleData> topArticles;
                boolean llmFailed = false;

                if (config.isOnDeviceLlmReady()) {
                    final DashboardNotifier notifier = new DashboardNotifier(activity);
                    OnDeviceLlmClient onDevice = new OnDeviceLlmClient(
                            activity,
                            config.getOnDeviceModelPath(),
                            config.isOnDeviceUseGpu(),
                            config.isOnDeviceBatchingEnabled());
                    try {
                        onDevice.initialize();
                        topArticles = onDevice.rankAndSummarize(allArticles,
                                config.getInterestProfile(), config.getArticleCount(),
                                new OnDeviceLlmClient.ProgressListener() {
                                    @Override
                                    public void onScoringStarted(int total) {
                                        mainThreadPoster.post(() -> {
                                            String phase = activity.getString(
                                                    R.string.llm_processing_weighting, 0, total);
                                            newsStatus.setText(phase);
                                            newsStatus.setVisibility(View.VISIBLE);
                                            notifier.showProcessingNotification(phase, 0, total);
                                        });
                                    }

                                    @Override
                                    public void onArticleScored(int scored, int total) {
                                        mainThreadPoster.post(() -> {
                                            String phase = activity.getString(
                                                    R.string.llm_processing_weighting,
                                                    scored, total);
                                            newsStatus.setText(phase);
                                            notifier.showProcessingNotification(phase, scored, total);
                                        });
                                    }

                                    @Override
                                    public void onSummarizingStarted(int total) {
                                        mainThreadPoster.post(() -> {
                                            String phase = activity.getString(
                                                    R.string.llm_processing_summarising,
                                                    0, total);
                                            newsStatus.setText(phase);
                                            notifier.showProcessingNotification(phase, 0, total);
                                        });
                                    }

                                    @Override
                                    public void onArticleSummarized(int summarized, int total) {
                                        mainThreadPoster.post(() -> {
                                            String phase = activity.getString(
                                                    R.string.llm_processing_summarising,
                                                    summarized, total);
                                            newsStatus.setText(phase);
                                            notifier.showProcessingNotification(phase, summarized, total);
                                        });
                                    }
                                });
                    } finally {
                        onDevice.close();
                        notifier.cancelProcessingNotification();
                    }
                } else {
                    LlmClient llm = new LlmClient(
                            config.getLlmBaseUrl(),
                            config.getLlmApiKey(),
                            config.getLlmModel());
                    topArticles = llm.rankAndSummarize(allArticles, config.getInterestProfile(),
                            config.getArticleCount());
                }

                // Check if LLM actually produced summaries or fell back
                boolean anySummary = false;
                for (ArticleData a : topArticles) {
                    if (a.llmSummary != null && !a.llmSummary.isEmpty()) {
                        anySummary = true;
                        break;
                    }
                }
                llmFailed = !anySummary;

                config.setCachedArticles(topArticles);
                config.setArticlesFetchDate();
                config.setDashboardLastRefresh(System.currentTimeMillis());

                final boolean showLlmWarning = llmFailed;
                final int failedFeeds = rssFailed;
                mainThreadPoster.post(() -> {
                    showArticles(topArticles);
                    showNewsWarnings(failedFeeds, rssTotal, showLlmWarning);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading news", e);
                mainThreadPoster.post(() -> {
                    newsLoading.setVisibility(View.GONE);
                    newsStatus.setText("Error loading your AI briefing");
                    newsStatus.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void showNewsWarnings(int rssFailed, int rssTotal, boolean llmFailed) {
        List<String> warnings = new ArrayList<>();
        if (rssFailed > 0) {
            warnings.add("Failed to load " + rssFailed + " of " + rssTotal + " feed sources");
        }
        if (llmFailed) {
            warnings.add("AI summaries unavailable - showing original descriptions");
        }

        if (!warnings.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) sb.append("\n");
                sb.append(warnings.get(i));
            }
            newsWarning.setText(sb.toString());
            newsWarning.setVisibility(View.VISIBLE);
        } else {
            newsWarning.setVisibility(View.GONE);
        }

        // Show reset hint when articles are loaded
        newsResetHint.setVisibility(View.VISIBLE);
    }

    private void showArticles(List<ArticleData> articles) {
        newsLoading.setVisibility(View.GONE);
        newsContainer.removeAllViews();
        currentBriefingArticles = new ArrayList<>(articles);

        if (articles.isEmpty()) {
            newsStatus.setText("No articles found for your AI briefing");
            newsStatus.setVisibility(View.VISIBLE);
            btnNewsShowAll.setVisibility(View.GONE);
            updateBriefingAvailability();
            return;
        }

        newsStatus.setVisibility(View.GONE);
        List<ArticleData> visibleArticles = limitArticles(articles, briefingExpanded);
        for (ArticleData article : visibleArticles) {
            View itemView = LayoutInflater.from(activity).inflate(R.layout.item_article, newsContainer, false);
            bindArticleView(itemView, article,
                    article.llmSummary != null && !article.llmSummary.isEmpty()
                            ? article.llmSummary
                            : article.originalDescription);
            newsContainer.addView(itemView);
        }
        updateShowAllButton(btnNewsShowAll, articles.size(), visibleArticles.size(), briefingExpanded);
        updateBriefingAvailability();
    }

    private void showHeadlines(List<ArticleData> articles) {
        headlinesLoading.setVisibility(View.GONE);
        headlinesContainer.removeAllViews();
        currentHeadlineArticles = new ArrayList<>(articles);

        if (articles.isEmpty()) {
            headlinesStatus.setText("No recent feed items from the last 24 hours");
            headlinesStatus.setVisibility(View.VISIBLE);
            headlinesHint.setVisibility(View.GONE);
            btnHeadlinesShowAll.setVisibility(View.GONE);
            return;
        }

        headlinesStatus.setVisibility(View.GONE);
        headlinesHint.setText(buildHeadlineWindowHint());
        headlinesHint.setVisibility(View.VISIBLE);
        List<ArticleData> visibleArticles = limitArticles(articles, headlinesExpanded);
        for (ArticleData article : visibleArticles) {
            View itemView = LayoutInflater.from(activity).inflate(R.layout.item_article, headlinesContainer, false);
            bindArticleView(itemView, article, buildHeadlineSubtitle(article));
            headlinesContainer.addView(itemView);
        }
        updateShowAllButton(btnHeadlinesShowAll, articles.size(), visibleArticles.size(), headlinesExpanded);
    }

    private List<ArticleData> limitArticles(List<ArticleData> articles, boolean expanded) {
        final int previewLimit = 5;
        if (expanded || articles.size() <= previewLimit) {
            return new ArrayList<>(articles);
        }
        return new ArrayList<>(articles.subList(0, previewLimit));
    }

    private void updateShowAllButton(MaterialButton button, int totalCount, int visibleCount, boolean expanded) {
        final int previewLimit = 5;
        if (totalCount <= previewLimit) {
            button.setVisibility(View.GONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setText(expanded ? R.string.news_show_fewer : R.string.news_show_all);
    }

    private void bindArticleView(View itemView, ArticleData article, String summaryText) {
        View root = itemView.findViewById(R.id.article_item_root);
        TextView title = itemView.findViewById(R.id.article_title);
        TextView source = itemView.findViewById(R.id.article_source);
        TextView summary = itemView.findViewById(R.id.article_summary);
        ImageView image = itemView.findViewById(R.id.article_image);

        title.setText(article.title);
        if (summaryText == null || summaryText.trim().isEmpty()) {
            summary.setVisibility(View.GONE);
        } else {
            summary.setVisibility(View.VISIBLE);
            summary.setText(summaryText);
        }

        String host = extractHost(article.sourceFeedUrl);
        if (host != null && !host.isEmpty()) {
            source.setText(host);
            source.setVisibility(View.VISIBLE);
        } else {
            source.setVisibility(View.GONE);
        }

        articleImageLoader.load(article.imageUrl, image);

        if (article.link != null && !article.link.isEmpty()) {
            root.setOnClickListener(v -> openArticleInCustomTab(article.link));
            root.setOnLongClickListener(v -> {
                openArticleInCustomTab(article.link);
                return true;
            });
        } else {
            root.setClickable(false);
            root.setFocusable(false);
        }
    }

    private String buildHeadlineWindowHint() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, config.getMorningRefreshHour());
        calendar.set(Calendar.MINUTE, config.getMorningRefreshMinute());
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        String timeLabel = DateFormat.getTimeFormat(activity).format(calendar.getTime());
        return "Showing the 24 hours leading up to " + timeLabel;
    }

    private String extractHost(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    private String buildHeadlineSubtitle(ArticleData article) {
        String relative = article.pubDate == 0L ? "Published recently" : relativeTimeFormatter.format(article.pubDate);
        if (article.originalDescription == null || article.originalDescription.trim().isEmpty()) {
            return relative;
        }
        return relative + " - " + article.originalDescription;
    }

    public void openArticleInCustomTab(String url) {
        try {
            CustomTabsIntent intent = new CustomTabsIntent.Builder().setShowTitle(true).build();
            intent.launchUrl(activity, Uri.parse(url));
        } catch (Exception e) {
            openArticleInBrowser(url);
        }
    }

    private void openArticleInBrowser(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        activity.startActivity(intent);
    }

    public void toggleBriefingPlayback() {
        if (currentBriefingArticles.isEmpty()) {
            briefingAudioStatus.setText(R.string.briefing_audio_empty);
            briefingAudioStatus.setVisibility(View.VISIBLE);
            Toast.makeText(activity, R.string.briefing_audio_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        if (briefingScriptGenerationInProgress || isAnyBriefingPlaying() || openAiBriefingSpeaker.isLoading()) {
            briefingScriptGenerationInProgress = false;
            briefingScriptGenerationRequestId++;
            if (morningBriefingSpeaker.isPlaying()) {
                morningBriefingSpeaker.stop();
            }
            if (openAiBriefingSpeaker.isPlaying() || openAiBriefingSpeaker.isLoading()) {
                openAiBriefingSpeaker.stop();
            }
            syncBriefingPlaybackStatus();
            return;
        }

        boolean shouldGenerateScriptWithLlm = config.isLlmConfigured();
        if (shouldGenerateScriptWithLlm) {
            briefingScriptGenerationInProgress = true;
            int requestId = ++briefingScriptGenerationRequestId;
            briefingAudioStatus.setText(R.string.briefing_audio_writing);
            briefingAudioStatus.setVisibility(View.VISIBLE);
            briefingAudioProgress.setVisibility(View.VISIBLE);
            invalidateMenuCallback.invalidateMenu();
            executor.execute(() -> {
                String transcript = buildBriefingTranscript();
                mainThreadPoster.post(() -> {
                    if (requestId != briefingScriptGenerationRequestId) {
                        return;
                    }
                    briefingScriptGenerationInProgress = false;
                    startBriefingPlayback(transcript);
                });
            });
            return;
        }

        startBriefingPlayback(buildFallbackBriefingTranscript());
    }

    public void updateBriefingAvailability() {
        boolean hasBriefing = !currentBriefingArticles.isEmpty();
        if (hasBriefing) {
            briefingAudioStatus.setText(R.string.briefing_audio_idle);
            briefingAudioStatus.setVisibility(View.VISIBLE);
        } else {
            briefingAudioStatus.setVisibility(View.GONE);
        }
        briefingAudioProgress.setVisibility(View.GONE);
        invalidateMenuCallback.invalidateMenu();
    }

    private void updateBriefingPlaybackUi(boolean isPlaying) {
        if (currentBriefingArticles.isEmpty()) {
            briefingAudioStatus.setVisibility(View.GONE);
            briefingAudioProgress.setVisibility(View.GONE);
            invalidateMenuCallback.invalidateMenu();
            return;
        }
        briefingAudioStatus.setText(isPlaying
                ? R.string.briefing_audio_playing
                : R.string.briefing_audio_idle);
        briefingAudioStatus.setVisibility(View.VISIBLE);
        briefingAudioProgress.setVisibility(View.GONE);
        invalidateMenuCallback.invalidateMenu();
    }

    private void syncBriefingPlaybackStatus() {
        if (briefingScriptGenerationInProgress) {
            briefingAudioStatus.setText(R.string.briefing_audio_writing);
            briefingAudioStatus.setVisibility(View.VISIBLE);
            briefingAudioProgress.setVisibility(View.VISIBLE);
            invalidateMenuCallback.invalidateMenu();
            return;
        }
        if (openAiBriefingSpeaker.isLoading()) {
            briefingAudioStatus.setText(R.string.briefing_audio_loading);
            briefingAudioStatus.setVisibility(View.VISIBLE);
            briefingAudioProgress.setVisibility(View.VISIBLE);
            invalidateMenuCallback.invalidateMenu();
            return;
        }
        updateBriefingPlaybackUi(isAnyBriefingPlaying());
    }

    private String buildBriefingTranscript() {
        String fallbackTranscript = buildFallbackBriefingTranscript();
        String structuredData = buildStructuredDashboardData();
        if (structuredData.trim().isEmpty()) {
            return fallbackTranscript;
        }

        String generated = null;
        if (config.isOnDeviceLlmReady()) {
            OnDeviceLlmClient onDevice = new OnDeviceLlmClient(
                    activity,
                    config.getOnDeviceModelPath(),
                    config.isOnDeviceUseGpu(),
                    config.isOnDeviceBatchingEnabled());
            try {
                onDevice.initialize();
                generated = onDevice.generateMorningBriefingScript(structuredData);
            } catch (Exception e) {
                Log.w(TAG, "On-device briefing script failed, falling back", e);
            } finally {
                onDevice.close();
            }
        } else if (config.isLlmConfigured()) {
            LlmClient llmClient = new LlmClient(
                    config.getLlmBaseUrl(),
                    config.getLlmApiKey(),
                    config.getLlmModel()
            );
            generated = llmClient.generateMorningBriefingScript(structuredData);
        }

        if (generated == null || generated.trim().isEmpty()) {
            return fallbackTranscript;
        }
        return generated.trim();
    }

    private String buildFallbackBriefingTranscript() {
        String calendarSummary = briefingDataProvider.getCalendarSummary();
        String emailSummary = briefingDataProvider.getEmailSummary();
        String socialSummary = briefingDataProvider.getSocialSummary();
        WeatherData weather = briefingDataProvider.getCachedWeather();

        return MorningBriefingSpeaker.buildScript(
                weather,
                currentHeadlineArticles,
                currentBriefingArticles,
                calendarSummary,
                emailSummary,
                socialSummary
        );
    }

    private String buildStructuredDashboardData() {
        List<String> sections = new ArrayList<>();

        WeatherData weather = briefingDataProvider.getCachedWeather();
        if (weather != null) {
            StringBuilder weatherSection = new StringBuilder();
            weatherSection.append("WEATHER\n");
            weatherSection.append("City: ").append(nullToEmpty(weather.cityName)).append('\n');
            weatherSection.append("Temperature C: ").append(Math.round(weather.temperature)).append('\n');
            weatherSection.append("Description: ").append(nullToEmpty(weather.description)).append('\n');
            if (weather.dailyMinTemp != null) {
                weatherSection.append("Daily min C: ").append(Math.round(weather.dailyMinTemp)).append('\n');
            }
            if (weather.dailyMaxTemp != null) {
                weatherSection.append("Daily max C: ").append(Math.round(weather.dailyMaxTemp)).append('\n');
            }
            weatherSection.append("Humidity percent: ").append(weather.humidity).append('\n');
            weatherSection.append("Wind kmh: ").append(Math.round(weather.windSpeed)).append('\n');
            String rainSummary = buildWeatherRainData(weather);
            if (!rainSummary.isEmpty()) {
                weatherSection.append("Rain outlook: ").append(rainSummary).append('\n');
            }
            sections.add(weatherSection.toString().trim());
        }

        if (!currentHeadlineArticles.isEmpty()) {
            StringBuilder headlinesSection = new StringBuilder("LATEST HEADLINES\n");
            for (int i = 0; i < Math.min(6, currentHeadlineArticles.size()); i++) {
                ArticleData article = currentHeadlineArticles.get(i);
                headlinesSection.append(i + 1)
                        .append(". Title: ").append(nullToEmpty(article.title)).append('\n');
                if (!isBlank(article.originalDescription)) {
                    headlinesSection.append("   Summary: ")
                            .append(article.originalDescription.trim())
                            .append('\n');
                }
            }
            sections.add(headlinesSection.toString().trim());
        }

        if (!currentBriefingArticles.isEmpty()) {
            StringBuilder briefingSection = new StringBuilder("AI BRIEFING\n");
            for (int i = 0; i < Math.min(6, currentBriefingArticles.size()); i++) {
                ArticleData article = currentBriefingArticles.get(i);
                briefingSection.append(i + 1)
                        .append(". Title: ").append(nullToEmpty(article.title)).append('\n');
                String summary = !isBlank(article.llmSummary)
                        ? article.llmSummary
                        : article.originalDescription;
                if (!isBlank(summary)) {
                    briefingSection.append("   Summary: ").append(summary.trim()).append('\n');
                }
            }
            sections.add(briefingSection.toString().trim());
        }

        String calendarSummary = briefingDataProvider.getCalendarSummary();
        if (!isBlank(calendarSummary)) {
            sections.add("CALENDAR\n" + calendarSummary);
        }

        String emailSummary = briefingDataProvider.getEmailSummary();
        if (!isBlank(emailSummary)) {
            sections.add("PERSONAL NOTES\n" + emailSummary);
        }

        String socialSummary = briefingDataProvider.getSocialSummary();
        if (!isBlank(socialSummary)) {
            sections.add("SOCIAL STATUS\n" + socialSummary);
        }

        return String.join("\n\n", sections).trim();
    }

    private void startBriefingPlayback(String transcript) {
        boolean started;
        if (config.isBriefingUseOpenAiTtsEnabled()) {
            if (morningBriefingSpeaker.isPlaying()) {
                morningBriefingSpeaker.stop();
            }
            started = openAiBriefingSpeaker.toggle(transcript, config);
        } else {
            if (openAiBriefingSpeaker.isPlaying() || openAiBriefingSpeaker.isLoading()) {
                openAiBriefingSpeaker.stop();
            }
            started = morningBriefingSpeaker.toggle(transcript);
        }
        if (started) {
            syncBriefingPlaybackStatus();
        } else {
            updateBriefingAvailability();
        }
    }

    private String buildWeatherRainData(WeatherData weather) {
        if (weather.hourlyTime == null
                || weather.hourlyPrecipitationMm == null
                || weather.hourlyPrecipitationProbability == null) {
            return "";
        }
        StringBuilder rain = new StringBuilder();
        int count = Math.min(weather.hourlyTime.length,
                Math.min(weather.hourlyPrecipitationMm.length, weather.hourlyPrecipitationProbability.length));
        for (int i = 0; i < Math.min(6, count); i++) {
            if (i > 0) {
                rain.append("; ");
            }
            rain.append(weather.hourlyTime[i])
                    .append(": ")
                    .append(String.format(Locale.getDefault(), "%.1f", weather.hourlyPrecipitationMm[i]))
                    .append(" mm, ")
                    .append(weather.hourlyPrecipitationProbability[i])
                    .append("%");
        }
        return rain.toString();
    }

    private boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    // Public getters for MainActivity
    public List<ArticleData> getCurrentBriefingArticles() {
        return currentBriefingArticles;
    }

    public List<ArticleData> getCurrentHeadlineArticles() {
        return currentHeadlineArticles;
    }

    public boolean isAnyBriefingPlaying() {
        return morningBriefingSpeaker.isPlaying() || openAiBriefingSpeaker.isPlaying();
    }

    public boolean isBriefingBusy() {
        return briefingScriptGenerationInProgress || openAiBriefingSpeaker.isLoading();
    }

    public void shutdown() {
        if (morningBriefingSpeaker != null) {
            morningBriefingSpeaker.shutdown();
        }
        if (openAiBriefingSpeaker != null) {
            openAiBriefingSpeaker.shutdown();
        }
    }
}
