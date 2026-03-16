package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maintains a rolling raw-feed pool plus stable 24-hour snapshots so rotating
 * feeds do not lose headlines before the user opens Breakfast.
 */
public final class NewsCache {
    private static final long SNAPSHOT_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long POOL_WINDOW_MS = 48L * 60L * 60L * 1000L;

    private NewsCache() {}

    public static List<ArticleData> mergeRollingWindow(
            List<ArticleData> existing,
            List<ArticleData> fetched,
            long nowMs
    ) {
        Map<String, ArticleData> merged = new LinkedHashMap<>();
        addRecent(merged, existing, nowMs, POOL_WINDOW_MS);
        addRecent(merged, fetched, nowMs, POOL_WINDOW_MS);

        List<ArticleData> articles = new ArrayList<>(merged.values());
        articles.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
        return articles;
    }

    public static List<ArticleData> snapshotWindow(List<ArticleData> articles, long windowEndMs) {
        Map<String, ArticleData> filtered = new LinkedHashMap<>();
        addRecent(filtered, articles, windowEndMs, SNAPSHOT_WINDOW_MS);

        List<ArticleData> snapshot = new ArrayList<>(filtered.values());
        snapshot.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
        return snapshot;
    }

    public static List<ArticleData> snapshotForDeliveryWindow(
            List<ArticleData> articles,
            long nowMs,
            int hourOfDay,
            int minute
    ) {
        long windowEndMs = computeWindowStart(nowMs, hourOfDay, minute);
        Map<String, ArticleData> filtered = new LinkedHashMap<>();
        addBetween(filtered, articles, windowEndMs - SNAPSHOT_WINDOW_MS, windowEndMs);

        List<ArticleData> snapshot = new ArrayList<>(filtered.values());
        snapshot.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
        return snapshot;
    }

    public static long computeWindowStart(long nowMs, int hourOfDay, int minute) {
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);

        Calendar windowStart = Calendar.getInstance();
        windowStart.setTimeInMillis(nowMs);
        windowStart.set(Calendar.HOUR_OF_DAY, hourOfDay);
        windowStart.set(Calendar.MINUTE, minute);
        windowStart.set(Calendar.SECOND, 0);
        windowStart.set(Calendar.MILLISECOND, 0);

        if (windowStart.after(now)) {
            windowStart.add(Calendar.DAY_OF_YEAR, -1);
        }
        return windowStart.getTimeInMillis();
    }

    public static List<ArticleData> mergeSinceWindowStart(
            List<ArticleData> existing,
            List<ArticleData> fetched,
            long nowMs,
            int hourOfDay,
            int minute
    ) {
        long windowStartMs = computeWindowStart(nowMs, hourOfDay, minute);
        Map<String, ArticleData> merged = new LinkedHashMap<>();
        addSince(merged, existing, windowStartMs);
        addSince(merged, fetched, windowStartMs);

        List<ArticleData> articles = new ArrayList<>(merged.values());
        articles.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
        return articles;
    }

    public static List<ArticleData> snapshotSinceWindowStart(
            List<ArticleData> articles,
            long nowMs,
            int hourOfDay,
            int minute
    ) {
        long windowStartMs = computeWindowStart(nowMs, hourOfDay, minute);
        Map<String, ArticleData> filtered = new LinkedHashMap<>();
        addSince(filtered, articles, windowStartMs);

        List<ArticleData> snapshot = new ArrayList<>(filtered.values());
        snapshot.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
        return snapshot;
    }

    private static void addRecent(
            Map<String, ArticleData> merged,
            List<ArticleData> articles,
            long nowMs,
            long windowMs
    ) {
        if (articles == null) {
            return;
        }

        long cutoff = nowMs - windowMs;
        for (ArticleData article : articles) {
            if (article == null || article.title == null || article.title.trim().isEmpty()) {
                continue;
            }
            if (article.pubDate != 0L && article.pubDate < cutoff) {
                continue;
            }

            String key = buildKey(article);
            ArticleData previous = merged.get(key);
            if (previous == null || article.pubDate > previous.pubDate) {
                merged.put(key, article);
            }
        }
    }

    private static void addBetween(
            Map<String, ArticleData> merged,
            List<ArticleData> articles,
            long startInclusiveMs,
            long endInclusiveMs
    ) {
        if (articles == null) {
            return;
        }

        for (ArticleData article : articles) {
            if (article == null || article.title == null || article.title.trim().isEmpty()) {
                continue;
            }
            if (article.pubDate != 0L
                    && (article.pubDate < startInclusiveMs || article.pubDate > endInclusiveMs)) {
                continue;
            }

            String key = buildKey(article);
            ArticleData previous = merged.get(key);
            if (previous == null || article.pubDate > previous.pubDate) {
                merged.put(key, article);
            }
        }
    }

    private static void addSince(Map<String, ArticleData> merged, List<ArticleData> articles, long cutoffMs) {
        if (articles == null) {
            return;
        }

        for (ArticleData article : articles) {
            if (article == null || article.title == null || article.title.trim().isEmpty()) {
                continue;
            }
            if (article.pubDate != 0L && article.pubDate < cutoffMs) {
                continue;
            }

            String key = buildKey(article);
            ArticleData previous = merged.get(key);
            if (previous == null || article.pubDate > previous.pubDate) {
                merged.put(key, article);
            }
        }
    }

    private static String buildKey(ArticleData article) {
        String link = article.link == null ? "" : article.link.trim().toLowerCase(Locale.US);
        if (!link.isEmpty()) {
            return "link:" + link;
        }
        String source = article.sourceFeedUrl == null ? "" : article.sourceFeedUrl.trim().toLowerCase(Locale.US);
        String title = article.title == null ? "" : article.title.trim().toLowerCase(Locale.US);
        return "title:" + source + "|" + title;
    }
}
