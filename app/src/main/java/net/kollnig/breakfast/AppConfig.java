package net.kollnig.breakfast;

import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Manages all configuration for the Breakfast app via SharedPreferences.
 */
public class AppConfig {
    private static final int SETTINGS_EXPORT_VERSION = 1;
    private static final String PREFS_NAME = "BreakfastPrefs";
    private static final String KEY_CITY = "weather_city";
    private static final String KEY_RSS_FEEDS = "rss_feeds";
    private static final String KEY_LLM_BASE_URL = "llm_base_url";
    private static final String KEY_LLM_API_KEY = "llm_api_key";
    private static final String KEY_LLM_MODEL = "llm_model";
    private static final String KEY_INTEREST_PROFILE = "interest_profile";
    private static final String KEY_BRIEFING_USE_OPENAI_TTS = "briefing_use_openai_tts";
    private static final String KEY_TODOIST_API_KEY = "todoist_api_key";
    private static final String KEY_TODOIST_PROJECT_ID = "todoist_project_id";
    private static final String KEY_TODOIST_ENABLED = "module_todoist_enabled";
    private static final String KEY_CACHED_TODOIST_TASKS = "cached_todoist_tasks";
    private static final String KEY_TODOIST_LAST_REFRESH = "todoist_last_refresh";
    private static final String KEY_CACHED_WEATHER = "cached_weather";
    private static final String KEY_CACHED_ARTICLES = "cached_articles";
    private static final String KEY_CACHED_HEADLINES = "cached_headlines";
    private static final String KEY_CACHED_HEADLINE_POOL = "cached_headline_pool";
    // Legacy keys retained so existing installs keep their social-break state.
    private static final String KEY_INSTAGRAM_TIMER_START = "instagram_timer_start";
    private static final String KEY_INSTAGRAM_TIMER_START_DATE = "instagram_timer_start_date";
    private static final String KEY_INSTAGRAM_BLOCKED_DATE = "instagram_blocked_date";
    private static final String KEY_TIMER_DURATION_MINS = "timer_duration_mins";
    private static final String KEY_FRICTION_WORD_COUNT = "friction_word_count";
    private static final String KEY_ARTICLE_COUNT = "article_count";
    private static final String KEY_ARTICLES_FETCH_DATE = "articles_fetch_date";
    private static final String KEY_HEADLINES_LAST_REFRESH = "headlines_last_refresh";
    private static final String KEY_MORNING_REFRESH_HOUR = "morning_refresh_hour";
    private static final String KEY_MORNING_REFRESH_MINUTE = "morning_refresh_minute";
    private static final String KEY_MORNING_NOTIFICATION_ENABLED = "morning_notification_enabled";
    // Legacy preference key retained for backwards compatibility with existing installs.
    private static final String KEY_ENABLE_REFRESH_BUTTON = "news_refresh_on_open";
    private static final String KEY_WEATHER_ENABLED = "module_weather_enabled";
    private static final String KEY_NEWS_ENABLED = "module_news_enabled";
    private static final String KEY_SOCIAL_ENABLED = "module_social_enabled";
    private static final String KEY_SOCIAL_INSTAGRAM_ENABLED = "social_instagram_enabled";
    private static final String KEY_SOCIAL_LINKEDIN_ENABLED = "social_linkedin_enabled";
    private static final String KEY_SOCIAL_PRESS_HOME_ON_TIMEOUT = "social_press_home_on_timeout";
    private static final String KEY_EMAIL_ENABLED = "module_email_enabled";
    private static final String KEY_CALENDAR_ENABLED = "module_calendar_enabled";
    private static final String KEY_EMAIL_NOTES = "module_email_notes";
    private static final String KEY_CALENDAR_IDS = "module_calendar_ids";
    private static final String KEY_CALENDAR_SELECTION_CUSTOMIZED = "module_calendar_selection_customized";
    private static final String KEY_MODULE_ORDER = "module_order";
    private static final String KEY_WELCOME_DISMISSED = "welcome_dismissed";
    private static final String KEY_DASHBOARD_LAST_REFRESH = "dashboard_last_refresh";
    private static final String KEY_ON_DEVICE_LLM_ENABLED = "on_device_llm_enabled";
    private static final String KEY_ON_DEVICE_MODEL_PATH = "on_device_model_path";
    private static final String KEY_ON_DEVICE_USE_GPU = "on_device_use_gpu";
    private static final String KEY_LLM_BENCHMARK_ENABLED = "llm_benchmark_enabled";
    private static final String KEY_NEWS_CACHE_SCHEMA_VERSION = "news_cache_schema_version";
    private static final int NEWS_CACHE_SCHEMA_VERSION = 2;

    public static final String MODULE_WEATHER = "weather";
    public static final String MODULE_NEWS = "news";
    public static final String MODULE_SOCIAL = "social";
    public static final String MODULE_EMAIL = "email";
    public static final String MODULE_CALENDAR = "calendar";
    public static final String MODULE_TODOIST = "todoist";

    private final SharedPreferences prefs;
    private final Gson gson;

    public AppConfig(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        ensureCacheCompatibility();
    }

    private void ensureCacheCompatibility() {
        int storedVersion = prefs.getInt(KEY_NEWS_CACHE_SCHEMA_VERSION, 0);
        if (storedVersion >= NEWS_CACHE_SCHEMA_VERSION) {
            return;
        }

        prefs.edit()
                .putInt(KEY_NEWS_CACHE_SCHEMA_VERSION, NEWS_CACHE_SCHEMA_VERSION)
                .remove(KEY_CACHED_ARTICLES)
                .remove(KEY_CACHED_HEADLINES)
                .remove(KEY_CACHED_HEADLINE_POOL)
                .remove(KEY_ARTICLES_FETCH_DATE)
                .remove(KEY_HEADLINES_LAST_REFRESH)
                .apply();
    }

    // --- Weather City ---

    public String getCity() {
        return prefs.getString(KEY_CITY, "");
    }

    public void setCity(String city) {
        prefs.edit().putString(KEY_CITY, city).apply();
    }

    // --- RSS Feeds ---

    public List<String> getRssFeeds() {
        List<String> urls = new ArrayList<>();
        for (FeedConfig feed : getFeedConfigs()) {
            if (feed.url != null && !feed.url.trim().isEmpty()) {
                urls.add(feed.url);
            }
        }
        return urls;
    }

    public List<FeedConfig> getFeedConfigs() {
        String json = prefs.getString(KEY_RSS_FEEDS, "[]");
        if (json == null || json.trim().isEmpty()) {
            json = "[]";
        }
        try {
            Type configListType = new TypeToken<List<FeedConfig>>() {}.getType();
            List<FeedConfig> configs = gson.fromJson(json, configListType);
            if (configs != null && !configs.isEmpty()) {
                List<FeedConfig> cleaned = new ArrayList<>();
                for (FeedConfig config : configs) {
                    if (config == null || config.url == null || config.url.trim().isEmpty()) {
                        continue;
                    }
                    cleaned.add(new FeedConfig(config.url, config.includeInTopStories, config.includeInHeadlines));
                }
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        } catch (JsonSyntaxException ignored) {
            // Older installs stored this as a plain array of strings.
        }

        Type legacyListType = new TypeToken<List<String>>() {}.getType();
        List<String> legacyFeeds;
        try {
            legacyFeeds = gson.fromJson(json, legacyListType);
        } catch (JsonSyntaxException ignored) {
            legacyFeeds = new ArrayList<>();
        }
        List<FeedConfig> migrated = new ArrayList<>();
        if (legacyFeeds != null) {
            for (String url : legacyFeeds) {
                if (url != null && !url.trim().isEmpty()) {
                    migrated.add(new FeedConfig(url, false, true));
                }
            }
        }
        // Persist the migrated format so we don't re-parse legacy data every time.
        if (!migrated.isEmpty()) {
            prefs.edit().putString(KEY_RSS_FEEDS, gson.toJson(migrated)).apply();
        }
        return migrated;
    }

    public void setRssFeeds(List<String> feeds) {
        List<FeedConfig> configs = new ArrayList<>();
        for (String url : feeds) {
            if (url != null && !url.trim().isEmpty()) {
                configs.add(new FeedConfig(url, false, true));
            }
        }
        setFeedConfigs(configs);
    }

    public void setFeedConfigs(List<FeedConfig> feeds) {
        prefs.edit().putString(KEY_RSS_FEEDS, gson.toJson(feeds)).apply();
        invalidateNewsCaches();
    }

    public boolean addRssFeed(String url) {
        List<FeedConfig> feeds = getFeedConfigs();
        for (FeedConfig feed : feeds) {
            if (url.equals(feed.url)) {
                return false;
            }
        }
        feeds.add(new FeedConfig(url, false, true));
        setFeedConfigs(feeds);
        return true;
    }

    public void removeRssFeed(String url) {
        List<FeedConfig> feeds = getFeedConfigs();
        feeds.removeIf(feed -> url.equals(feed.url));
        setFeedConfigs(feeds);
    }

    public void updateFeedConfig(FeedConfig updatedFeed) {
        List<FeedConfig> feeds = getFeedConfigs();
        for (int i = 0; i < feeds.size(); i++) {
            FeedConfig feed = feeds.get(i);
            if (feed.url.equals(updatedFeed.url)) {
                feeds.set(i, updatedFeed);
                setFeedConfigs(feeds);
                return;
            }
        }
        feeds.add(updatedFeed);
        setFeedConfigs(feeds);
    }

    private void invalidateNewsCaches() {
        prefs.edit()
                .remove(KEY_CACHED_ARTICLES)
                .remove(KEY_ARTICLES_FETCH_DATE)
                .remove(KEY_CACHED_HEADLINES)
                .remove(KEY_CACHED_HEADLINE_POOL)
                .remove(KEY_HEADLINES_LAST_REFRESH)
                .apply();
    }

    public List<String> getHeadlineFeedUrls() {
        List<String> urls = new ArrayList<>();
        for (FeedConfig feed : getFeedConfigs()) {
            if (feed.includeInHeadlines && feed.url != null && !feed.url.trim().isEmpty()) {
                urls.add(feed.url);
            }
        }
        return urls;
    }

    public List<String> getTopStoryFeedUrls() {
        List<String> urls = new ArrayList<>();
        for (FeedConfig feed : getFeedConfigs()) {
            if (feed.includeInTopStories && feed.url != null && !feed.url.trim().isEmpty()) {
                urls.add(feed.url);
            }
        }
        return urls;
    }

    public List<String> getEffectiveHeadlineFeedUrls() {
        if (isTopStoriesAvailable()) {
            return getHeadlineFeedUrls();
        }

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (FeedConfig feed : getFeedConfigs()) {
            if (feed.url != null && !feed.url.trim().isEmpty()) {
                urls.add(feed.url);
            }
        }
        return new ArrayList<>(urls);
    }

    // --- LLM Config ---

    public String getLlmBaseUrl() {
        return prefs.getString(KEY_LLM_BASE_URL, "https://api.openai.com/v1");
    }

    public void setLlmBaseUrl(String url) {
        prefs.edit().putString(KEY_LLM_BASE_URL, url).apply();
    }

    public String getLlmApiKey() {
        return prefs.getString(KEY_LLM_API_KEY, "");
    }

    public void setLlmApiKey(String key) {
        prefs.edit().putString(KEY_LLM_API_KEY, key).apply();
    }

    public String getLlmModel() {
        return prefs.getString(KEY_LLM_MODEL, "gpt-4o-mini");
    }

    public void setLlmModel(String model) {
        prefs.edit().putString(KEY_LLM_MODEL, model).apply();
    }

    // --- On-Device LLM Config ---

    public boolean isOnDeviceLlmEnabled() {
        return prefs.getBoolean(KEY_ON_DEVICE_LLM_ENABLED, false);
    }

    public void setOnDeviceLlmEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ON_DEVICE_LLM_ENABLED, enabled).apply();
    }

    public String getOnDeviceModelPath() {
        return prefs.getString(KEY_ON_DEVICE_MODEL_PATH, "");
    }

    public void setOnDeviceModelPath(String path) {
        prefs.edit().putString(KEY_ON_DEVICE_MODEL_PATH, path).apply();
    }

    public boolean isOnDeviceUseGpu() {
        return prefs.getBoolean(KEY_ON_DEVICE_USE_GPU, true);
    }

    public void setOnDeviceUseGpu(boolean useGpu) {
        prefs.edit().putBoolean(KEY_ON_DEVICE_USE_GPU, useGpu).apply();
    }

    public boolean isLlmBenchmarkEnabled() {
        return prefs.getBoolean(KEY_LLM_BENCHMARK_ENABLED, false);
    }

    public void setLlmBenchmarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LLM_BENCHMARK_ENABLED, enabled).apply();
    }

    // --- Interest Profile ---

    public String getInterestProfile() {
        return prefs.getString(KEY_INTEREST_PROFILE, "");
    }

    public void setInterestProfile(String profile) {
        prefs.edit().putString(KEY_INTEREST_PROFILE, profile).apply();
    }

    public List<String> getModuleOrder() {
        String json = prefs.getString(KEY_MODULE_ORDER, null);
        Type listType = new TypeToken<List<String>>() {}.getType();
        List<String> storedOrder = null;
        if (json != null) {
            try {
                storedOrder = gson.fromJson(json, listType);
            } catch (JsonSyntaxException ignored) {
                storedOrder = null;
            }
        }
        return normalizeModuleOrder(storedOrder);
    }

    public void setModuleOrder(List<String> moduleOrder) {
        prefs.edit().putString(KEY_MODULE_ORDER, gson.toJson(normalizeModuleOrder(moduleOrder))).apply();
    }

    public static List<String> getDefaultModuleOrder() {
        return new ArrayList<>(DashboardModuleRegistry.getModuleIds());
    }

    private List<String> normalizeModuleOrder(List<String> storedOrder) {
        List<String> defaultOrder = getDefaultModuleOrder();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (storedOrder != null) {
            for (String moduleId : storedOrder) {
                if (defaultOrder.contains(moduleId)) {
                    normalized.add(moduleId);
                }
            }
        }
        for (String moduleId : defaultOrder) {
            normalized.add(moduleId);
        }
        return new ArrayList<>(normalized);
    }

    // --- Todoist ---

    public String getTodoistApiKey() {
        return prefs.getString(KEY_TODOIST_API_KEY, "");
    }

    public void setTodoistApiKey(String key) {
        prefs.edit().putString(KEY_TODOIST_API_KEY, key).apply();
    }

    public String getTodoistProjectId() {
        return prefs.getString(KEY_TODOIST_PROJECT_ID, "");
    }

    public void setTodoistProjectId(String projectId) {
        prefs.edit().putString(KEY_TODOIST_PROJECT_ID, projectId).apply();
    }

    public boolean isTodoistModuleEnabled() {
        return prefs.getBoolean(KEY_TODOIST_ENABLED, false);
    }

    public void setTodoistModuleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TODOIST_ENABLED, enabled).apply();
    }

    public boolean isTodoistConfigured() {
        return !getTodoistApiKey().isEmpty() && !getTodoistProjectId().isEmpty();
    }

    public List<TodoistTask> getCachedTodoistTasks() {
        String json = prefs.getString(KEY_CACHED_TODOIST_TASKS, "[]");
        Type listType = new TypeToken<List<TodoistTask>>() {}.getType();
        List<TodoistTask> tasks = gson.fromJson(json, listType);
        return tasks != null ? tasks : new ArrayList<>();
    }

    public void setCachedTodoistTasks(List<TodoistTask> tasks) {
        prefs.edit().putString(KEY_CACHED_TODOIST_TASKS, gson.toJson(tasks)).apply();
    }

    public long getTodoistLastRefresh() {
        return prefs.getLong(KEY_TODOIST_LAST_REFRESH, 0L);
    }

    public void setTodoistLastRefresh(long timestamp) {
        prefs.edit().putLong(KEY_TODOIST_LAST_REFRESH, timestamp).apply();
    }

    public void clearTodoistCache() {
        prefs.edit()
                .remove(KEY_CACHED_TODOIST_TASKS)
                .remove(KEY_TODOIST_LAST_REFRESH)
                .apply();
    }

    public boolean isBriefingUseOpenAiTtsEnabled() {
        return prefs.getBoolean(KEY_BRIEFING_USE_OPENAI_TTS, false);
    }

    public void setBriefingUseOpenAiTtsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BRIEFING_USE_OPENAI_TTS, enabled).apply();
    }

    // --- Cached Weather ---

    public WeatherData getCachedWeather() {
        String json = prefs.getString(KEY_CACHED_WEATHER, null);
        if (json == null) return null;
        return gson.fromJson(json, WeatherData.class);
    }

    public void setCachedWeather(WeatherData data) {
        prefs.edit().putString(KEY_CACHED_WEATHER, gson.toJson(data)).apply();
    }

    // --- Cached Articles ---

    public List<ArticleData> getCachedArticles() {
        String json = prefs.getString(KEY_CACHED_ARTICLES, "[]");
        Type listType = new TypeToken<List<ArticleData>>() {}.getType();
        List<ArticleData> articles = gson.fromJson(json, listType);
        return articles != null ? articles : new ArrayList<>();
    }

    public void setCachedArticles(List<ArticleData> articles) {
        prefs.edit().putString(KEY_CACHED_ARTICLES, gson.toJson(articles)).apply();
    }

    public List<ArticleData> getCachedHeadlines() {
        String json = prefs.getString(KEY_CACHED_HEADLINES, "[]");
        Type listType = new TypeToken<List<ArticleData>>() {}.getType();
        List<ArticleData> articles = gson.fromJson(json, listType);
        return articles != null ? articles : new ArrayList<>();
    }

    public void setCachedHeadlines(List<ArticleData> articles) {
        prefs.edit().putString(KEY_CACHED_HEADLINES, gson.toJson(articles)).apply();
    }

    public List<ArticleData> getCachedHeadlinePool() {
        String json = prefs.getString(KEY_CACHED_HEADLINE_POOL, "[]");
        Type listType = new TypeToken<List<ArticleData>>() {}.getType();
        List<ArticleData> articles = gson.fromJson(json, listType);
        return articles != null ? articles : new ArrayList<>();
    }

    public void setCachedHeadlinePool(List<ArticleData> articles) {
        prefs.edit().putString(KEY_CACHED_HEADLINE_POOL, gson.toJson(articles)).apply();
    }

    // --- Personalized Modules ---

    public boolean isWeatherModuleEnabled() {
        return prefs.getBoolean(KEY_WEATHER_ENABLED, true);
    }

    public void setWeatherModuleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WEATHER_ENABLED, enabled).apply();
    }

    public boolean isNewsModuleEnabled() {
        return prefs.getBoolean(KEY_NEWS_ENABLED, true);
    }

    public void setNewsModuleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NEWS_ENABLED, enabled).apply();
    }

    public boolean isSocialModuleEnabled() {
        return prefs.getBoolean(KEY_SOCIAL_ENABLED, true);
    }

    public void setSocialModuleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOCIAL_ENABLED, enabled).apply();
    }

    public boolean isInstagramSocialEnabled() {
        return prefs.getBoolean(KEY_SOCIAL_INSTAGRAM_ENABLED, true);
    }

    public void setInstagramSocialEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOCIAL_INSTAGRAM_ENABLED, enabled).apply();
    }

    public boolean isLinkedinSocialEnabled() {
        return prefs.getBoolean(KEY_SOCIAL_LINKEDIN_ENABLED, true);
    }

    public void setLinkedinSocialEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOCIAL_LINKEDIN_ENABLED, enabled).apply();
    }

    public boolean isAnySocialAppEnabled() {
        return isInstagramSocialEnabled() || isLinkedinSocialEnabled();
    }

    public boolean shouldPressHomeWhenSocialTimeIsUp() {
        return prefs.getBoolean(KEY_SOCIAL_PRESS_HOME_ON_TIMEOUT, true);
    }

    public void setPressHomeWhenSocialTimeIsUp(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOCIAL_PRESS_HOME_ON_TIMEOUT, enabled).apply();
    }

    /**
     * Social blocking rules are only active while the social module is enabled.
     */
    public boolean areSocialBlocksEnabled() {
        return isSocialModuleEnabled() && isAnySocialAppEnabled();
    }

    public boolean isEmailModuleEnabled() {
        return prefs.getBoolean(KEY_EMAIL_ENABLED, false);
    }

    public void setEmailModuleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_EMAIL_ENABLED, enabled).apply();
    }

    public boolean isCalendarModuleEnabled() {
        return prefs.getBoolean(KEY_CALENDAR_ENABLED, false);
    }

    public void setCalendarModuleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CALENDAR_ENABLED, enabled).apply();
    }

    public String getEmailNotes() {
        return prefs.getString(KEY_EMAIL_NOTES, "");
    }

    public void setEmailNotes(String notes) {
        prefs.edit().putString(KEY_EMAIL_NOTES, notes).apply();
    }

    public java.util.Set<Long> getSelectedCalendarIds() {
        java.util.Set<String> rawIds = prefs.getStringSet(KEY_CALENDAR_IDS, new java.util.HashSet<>());
        java.util.Set<Long> ids = new java.util.HashSet<>();
        if (rawIds == null) {
            return ids;
        }

        for (String rawId : rawIds) {
            try {
                ids.add(Long.parseLong(rawId));
            } catch (NumberFormatException ignored) {
                // Ignore stale or malformed IDs.
            }
        }
        return ids;
    }

    public void setSelectedCalendarIds(java.util.Set<Long> calendarIds) {
        java.util.Set<String> rawIds = new java.util.HashSet<>();
        if (calendarIds != null) {
            for (Long id : calendarIds) {
                if (id != null) {
                    rawIds.add(String.valueOf(id));
                }
            }
        }
        prefs.edit().putStringSet(KEY_CALENDAR_IDS, rawIds).apply();
    }

    public boolean hasExplicitCalendarSelection() {
        return prefs.getBoolean(KEY_CALENDAR_SELECTION_CUSTOMIZED, false);
    }

    public void setHasExplicitCalendarSelection(boolean customized) {
        prefs.edit().putBoolean(KEY_CALENDAR_SELECTION_CUSTOMIZED, customized).apply();
    }

    public boolean isWelcomeDismissed() {
        return prefs.getBoolean(KEY_WELCOME_DISMISSED, false);
    }

    public void setWelcomeDismissed(boolean dismissed) {
        prefs.edit().putBoolean(KEY_WELCOME_DISMISSED, dismissed).apply();
    }

    public long getDashboardLastRefresh() {
        return prefs.getLong(KEY_DASHBOARD_LAST_REFRESH, 0L);
    }

    public void setDashboardLastRefresh(long timestamp) {
        prefs.edit().putLong(KEY_DASHBOARD_LAST_REFRESH, timestamp).apply();
    }

    // --- Timer Duration ---

    /** Duration of the social media break in minutes. Default 10, range 1–120. */
    public int getTimerDurationMins() {
        return prefs.getInt(KEY_TIMER_DURATION_MINS, 10);
    }

    public void setTimerDurationMins(int minutes) {
        int clamped = Math.max(1, Math.min(120, minutes));
        prefs.edit().putInt(KEY_TIMER_DURATION_MINS, clamped).apply();
    }

    // --- Friction Gate ---

    /** How many words must be typed to confirm a reset. Default 0 (off), range 0–50. */
    public int getFrictionWordCount() {
        return prefs.getInt(KEY_FRICTION_WORD_COUNT, 0);
    }

    public void setFrictionWordCount(int count) {
        int clamped = Math.max(0, Math.min(50, count));
        prefs.edit().putInt(KEY_FRICTION_WORD_COUNT, clamped).apply();
    }

    /** Returns true if friction is enabled (word count > 0). */
    public boolean isFrictionEnabled() {
        return getFrictionWordCount() > 0;
    }

    // --- Article Count ---

    /** How many RSS articles to show. Default 5, range 1–20. */
    public int getArticleCount() {
        return prefs.getInt(KEY_ARTICLE_COUNT, 5);
    }

    public void setArticleCount(int count) {
        int clamped = Math.max(1, Math.min(20, count));
        prefs.edit().putInt(KEY_ARTICLE_COUNT, clamped).apply();
    }

    // --- Social Break Timer ---

    /**
     * Start the social media timer. Records the current time.
     */
    public void startSocialTimer() {
        int deliveryDay = getCurrentDeliveryDayInt();
        prefs.edit()
                .putLong(KEY_INSTAGRAM_TIMER_START, System.currentTimeMillis())
                .putInt(KEY_INSTAGRAM_TIMER_START_DATE, deliveryDay)
                .apply();
    }

    /**
     * Get the timestamp when the social timer was started, or 0 if not started.
     */
    public long getSocialTimerStart() {
        return prefs.getLong(KEY_INSTAGRAM_TIMER_START, 0);
    }

    /**
     * Get the date when the social timer was started, encoded as YYYYMMDD,
     * or 0 if unknown/not started.
     */
    public int getSocialTimerStartDateInt() {
        return prefs.getInt(KEY_INSTAGRAM_TIMER_START_DATE, 0);
    }

    /**
     * Clear the social timer (e.g. on midnight reset).
     */
    public void clearSocialTimer() {
        prefs.edit()
                .remove(KEY_INSTAGRAM_TIMER_START)
                .remove(KEY_INSTAGRAM_TIMER_START_DATE)
                .remove(KEY_INSTAGRAM_BLOCKED_DATE)
                .apply();
    }

    /**
     * Mark the social break as used up for today.
     */
    public void setSocialBlockedToday() {
        prefs.edit().putInt(KEY_INSTAGRAM_BLOCKED_DATE, getCurrentDeliveryDayInt()).apply();
    }

    /**
     * Check if the social break has been used up today.
     */
    public boolean isSocialBlockedToday() {
        int today = getCurrentDeliveryDayInt();

        // If the timer was started in the current delivery window and is no longer running,
        // (even if the app process was killed and the "blocked date" wasn't written).
        long start = getSocialTimerStart();
        int startDate = getSocialTimerStartDateInt();
        if (start != 0 && startDate == today && !isSocialTimerRunning()) {
            return true;
        }

        // Backward-compatible flag used by older flows.
        int blockedDate = prefs.getInt(KEY_INSTAGRAM_BLOCKED_DATE, 0);
        return blockedDate == today;
    }

    /**
     * Check if the social timer is currently running (started but not yet expired).
     */
    public boolean isSocialTimerRunning() {
        long start = getSocialTimerStart();
        if (start == 0) return false;
        if (getSocialTimerStartDateInt() != getCurrentDeliveryDayInt()) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - start;
        return elapsed < getTimerDurationMins() * 60_000L;
    }

    /**
     * Get remaining social media time in milliseconds. Returns 0 if expired or not started.
     */
    public long getSocialTimeRemaining() {
        long start = getSocialTimerStart();
        if (start == 0) return 0;
        if (getSocialTimerStartDateInt() != getCurrentDeliveryDayInt()) {
            return 0;
        }
        long elapsed = System.currentTimeMillis() - start;
        long remaining = (getTimerDurationMins() * 60_000L) - elapsed;
        return Math.max(0, remaining);
    }

    public long getCurrentDeliveryWindowStartMs() {
        return getDeliveryWindowStartMs(System.currentTimeMillis());
    }

    public long getNextDeliveryWindowStartMs() {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(getCurrentDeliveryWindowStartMs());
        next.add(Calendar.DAY_OF_YEAR, 1);
        return next.getTimeInMillis();
    }

    private int getCurrentDeliveryDayInt() {
        return getDeliveryDayInt(System.currentTimeMillis());
    }

    private int getDeliveryDayInt(long timestampMs) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(getDeliveryWindowStartMs(timestampMs));
        int month1 = cal.get(Calendar.MONTH) + 1; // Calendar.MONTH is 0-based
        return cal.get(Calendar.YEAR) * 10000 + month1 * 100 + cal.get(Calendar.DAY_OF_MONTH);
    }

    private long getDeliveryWindowStartMs(long timestampMs) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(timestampMs);

        Calendar windowStart = Calendar.getInstance();
        windowStart.setTimeInMillis(timestampMs);
        windowStart.set(Calendar.HOUR_OF_DAY, getMorningRefreshHour());
        windowStart.set(Calendar.MINUTE, getMorningRefreshMinute());
        windowStart.set(Calendar.SECOND, 0);
        windowStart.set(Calendar.MILLISECOND, 0);

        if (windowStart.after(now)) {
            windowStart.add(Calendar.DAY_OF_YEAR, -1);
        }
        return windowStart.getTimeInMillis();
    }

    private int getTodayDateInt() {
        Calendar cal = Calendar.getInstance();
        int month1 = cal.get(Calendar.MONTH) + 1; // Calendar.MONTH is 0-based
        return cal.get(Calendar.YEAR) * 10000 + month1 * 100 + cal.get(Calendar.DAY_OF_MONTH);
    }

    // --- Daily Article Fetch ---

    /**
     * Returns the date (YYYYMMDD) when articles were last fetched, or 0 if never.
     */
    public int getArticlesFetchDate() {
        return prefs.getInt(KEY_ARTICLES_FETCH_DATE, 0);
    }

    /**
     * Records today as the article fetch date.
     */
    public void setArticlesFetchDate() {
        prefs.edit().putInt(KEY_ARTICLES_FETCH_DATE, getTodayDateInt()).apply();
    }

    /**
     * Returns true if articles should be refreshed (never fetched today).
     */
    public boolean shouldRefreshArticles() {
        return getArticlesFetchDate() != getTodayDateInt();
    }

    /**
     * Clears the article cache so a fresh fetch will happen.
     */
    public void clearArticleCache() {
        prefs.edit()
                .remove(KEY_CACHED_ARTICLES)
                .remove(KEY_ARTICLES_FETCH_DATE)
                .apply();
    }

    public long getHeadlinesLastRefresh() {
        return prefs.getLong(KEY_HEADLINES_LAST_REFRESH, 0L);
    }

    public void setHeadlinesLastRefresh(long timestamp) {
        prefs.edit().putLong(KEY_HEADLINES_LAST_REFRESH, timestamp).apply();
    }

    public void clearHeadlineCache() {
        prefs.edit()
                .remove(KEY_CACHED_HEADLINES)
                .remove(KEY_CACHED_HEADLINE_POOL)
                .remove(KEY_HEADLINES_LAST_REFRESH)
                .apply();
    }

    public int getMorningRefreshHour() {
        return prefs.getInt(KEY_MORNING_REFRESH_HOUR, 8);
    }

    public void setMorningRefreshHour(int hour) {
        int clamped = Math.max(0, Math.min(23, hour));
        prefs.edit().putInt(KEY_MORNING_REFRESH_HOUR, clamped).apply();
    }

    public int getMorningRefreshMinute() {
        return prefs.getInt(KEY_MORNING_REFRESH_MINUTE, 0);
    }

    public void setMorningRefreshMinute(int minute) {
        int clamped = Math.max(0, Math.min(59, minute));
        prefs.edit().putInt(KEY_MORNING_REFRESH_MINUTE, clamped).apply();
    }

    public boolean isMorningNotificationEnabled() {
        return prefs.getBoolean(KEY_MORNING_NOTIFICATION_ENABLED, true);
    }

    public void setMorningNotificationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MORNING_NOTIFICATION_ENABLED, enabled).apply();
    }

    public boolean isRefreshButtonEnabled() {
        return prefs.getBoolean(KEY_ENABLE_REFRESH_BUTTON, false);
    }

    public void setRefreshButtonEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLE_REFRESH_BUTTON, enabled).apply();
    }

    // --- LLM Configuration Validity ---

    public boolean isLlmConfigured() {
        return !getLlmApiKey().isEmpty() && !getLlmBaseUrl().isEmpty();
    }

    public boolean isTopStoriesAvailable() {
        return isLlmConfigured() || isOnDeviceLlmReady();
    }

    public boolean isOnDeviceLlmReady() {
        return isOnDeviceLlmEnabled()
                && !getOnDeviceModelPath().isEmpty()
                && new java.io.File(getOnDeviceModelPath()).exists();
    }

    public String exportSettingsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", SETTINGS_EXPORT_VERSION);
        root.addProperty("preferencesName", PREFS_NAME);

        JsonObject preferences = new JsonObject();
        for (java.util.Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            JsonObject serializedPreference = serializePreferenceValue(entry.getValue());
            if (serializedPreference != null) {
                preferences.add(entry.getKey(), serializedPreference);
            }
        }
        root.add("preferences", preferences);
        return gson.toJson(root);
    }

    public void importSettingsJson(String json) {
        JsonObject root;
        try {
            root = gson.fromJson(json, JsonObject.class);
        } catch (JsonParseException exception) {
            throw new IllegalArgumentException("Invalid settings file", exception);
        }

        if (root == null || !root.has("preferences") || !root.get("preferences").isJsonObject()) {
            throw new IllegalArgumentException("Invalid settings file");
        }

        JsonObject preferences = root.getAsJsonObject("preferences");
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (java.util.Map.Entry<String, JsonElement> entry : preferences.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }

            JsonObject serializedPreference = entry.getValue().getAsJsonObject();
            if (!serializedPreference.has("type") || !serializedPreference.has("value")) {
                continue;
            }

            String key = entry.getKey();
            String type = serializedPreference.get("type").getAsString();
            JsonElement value = serializedPreference.get("value");

            switch (type) {
                case "string":
                    if (value.isJsonNull()) {
                        editor.remove(key);
                    } else {
                        editor.putString(key, value.getAsString());
                    }
                    break;
                case "boolean":
                    editor.putBoolean(key, value.getAsBoolean());
                    break;
                case "int":
                    editor.putInt(key, value.getAsInt());
                    break;
                case "long":
                    editor.putLong(key, value.getAsLong());
                    break;
                case "float":
                    editor.putFloat(key, value.getAsFloat());
                    break;
                case "string_set":
                    if (!value.isJsonArray()) {
                        break;
                    }
                    java.util.Set<String> values = new java.util.HashSet<>();
                    for (JsonElement item : value.getAsJsonArray()) {
                        if (!item.isJsonNull()) {
                            values.add(item.getAsString());
                        }
                    }
                    editor.putStringSet(key, values);
                    break;
                default:
                    break;
            }
        }
        editor.apply();
        ensureCacheCompatibility();
    }

    private JsonObject serializePreferenceValue(Object value) {
        JsonObject serializedPreference = new JsonObject();
        if (value instanceof String) {
            serializedPreference.addProperty("type", "string");
            serializedPreference.addProperty("value", (String) value);
            return serializedPreference;
        }
        if (value instanceof Boolean) {
            serializedPreference.addProperty("type", "boolean");
            serializedPreference.addProperty("value", (Boolean) value);
            return serializedPreference;
        }
        if (value instanceof Integer) {
            serializedPreference.addProperty("type", "int");
            serializedPreference.addProperty("value", (Integer) value);
            return serializedPreference;
        }
        if (value instanceof Long) {
            serializedPreference.addProperty("type", "long");
            serializedPreference.addProperty("value", (Long) value);
            return serializedPreference;
        }
        if (value instanceof Float) {
            serializedPreference.addProperty("type", "float");
            serializedPreference.addProperty("value", (Float) value);
            return serializedPreference;
        }
        if (value instanceof java.util.Set) {
            serializedPreference.addProperty("type", "string_set");
            JsonArray values = new JsonArray();
            for (Object item : (java.util.Set<?>) value) {
                if (item instanceof String) {
                    values.add((String) item);
                }
            }
            serializedPreference.add("value", values);
            return serializedPreference;
        }
        return null;
    }
}
