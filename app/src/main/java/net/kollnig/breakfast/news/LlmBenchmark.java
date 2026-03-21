package net.kollnig.breakfast.news;

import android.content.Context;
import android.util.Log;

import net.kollnig.breakfast.AppConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs both cloud and on-device LLM backends on the same articles and logs a
 * structured comparison.  Meant to be triggered from a debug menu or adb command
 * — all work happens synchronously on the calling thread.
 *
 * Usage from NewsDashboardModule (on a background thread):
 *   LlmBenchmark.compare(activity, config, articles);
 *
 * Then read output with:
 *   adb logcat -s LlmBenchmark
 */
public class LlmBenchmark {
    private static final String TAG = "LlmBenchmark";

    public static void compare(Context context, AppConfig config, List<ArticleData> articles) {
        if (articles.isEmpty()) {
            Log.i(TAG, "No articles to benchmark");
            return;
        }

        String interestProfile = config.getInterestProfile();
        int count = config.getArticleCount();

        Log.i(TAG, "=== LLM BENCHMARK START ===");
        Log.i(TAG, "Articles: " + articles.size() + ", requested top: " + count);
        Log.i(TAG, "Interest profile: " + (interestProfile.isEmpty() ? "(none)" : interestProfile));

        // --- Cloud LLM ---
        List<ArticleData> cloudResults = null;
        long cloudMs = -1;
        if (config.isLlmConfigured()) {
            Log.i(TAG, "--- Cloud LLM (" + config.getLlmModel() + ") ---");
            // Deep-copy articles so both runs start from the same state
            List<ArticleData> cloudInput = deepCopy(articles);
            long start = System.currentTimeMillis();
            try {
                LlmClient llm = new LlmClient(
                        config.getLlmBaseUrl(),
                        config.getLlmApiKey(),
                        config.getLlmModel());
                cloudResults = llm.rankAndSummarize(cloudInput, interestProfile, count);
                cloudMs = System.currentTimeMillis() - start;
                Log.i(TAG, "Cloud finished in " + cloudMs + " ms, returned " + cloudResults.size() + " articles");
                logResults("CLOUD", cloudResults);
            } catch (Exception e) {
                cloudMs = System.currentTimeMillis() - start;
                Log.e(TAG, "Cloud LLM failed after " + cloudMs + " ms", e);
            }
        } else {
            Log.i(TAG, "Cloud LLM not configured — skipping");
        }

        // --- On-Device LLM ---
        List<ArticleData> deviceResults = null;
        long deviceMs = -1;
        String modelPath = config.getOnDeviceModelPath();
        if (!modelPath.isEmpty() && new java.io.File(modelPath).exists()) {
            Log.i(TAG, "--- On-Device LLM (Gemma via LiteRT-LM) ---");
            List<ArticleData> deviceInput = deepCopy(articles);
            long start = System.currentTimeMillis();
            OnDeviceLlmClient onDevice = new OnDeviceLlmClient(
                    context, modelPath, config.getOnDeviceAccelerator());
            try {
                long initStart = System.currentTimeMillis();
                onDevice.initialize();
                long initMs = System.currentTimeMillis() - initStart;
                Log.i(TAG, "Engine init: " + initMs + " ms");

                deviceResults = onDevice.rankAndSummarize(deviceInput, interestProfile, count);
                deviceMs = System.currentTimeMillis() - start;
                Log.i(TAG, "On-device finished in " + deviceMs + " ms (incl. init), returned "
                        + deviceResults.size() + " articles");
                logResults("DEVICE", deviceResults);
            } catch (Exception e) {
                deviceMs = System.currentTimeMillis() - start;
                Log.e(TAG, "On-device LLM failed after " + deviceMs + " ms", e);
            } finally {
                onDevice.close();
            }
        } else {
            Log.i(TAG, "On-device model not available — skipping");
        }

        // --- Comparison ---
        if (cloudResults != null && deviceResults != null) {
            Log.i(TAG, "=== COMPARISON ===");
            Log.i(TAG, String.format("Speed: cloud=%dms, device=%dms (%.1fx)",
                    cloudMs, deviceMs, (float) deviceMs / Math.max(1, cloudMs)));

            // Compare article selection overlap
            List<String> cloudTitles = new ArrayList<>();
            for (ArticleData a : cloudResults) cloudTitles.add(a.title);
            List<String> deviceTitles = new ArrayList<>();
            for (ArticleData a : deviceResults) deviceTitles.add(a.title);

            int overlap = 0;
            for (String t : deviceTitles) {
                if (cloudTitles.contains(t)) overlap++;
            }
            Log.i(TAG, String.format("Selection overlap: %d/%d articles in common (%.0f%%)",
                    overlap, Math.max(cloudResults.size(), deviceResults.size()),
                    100f * overlap / Math.max(1, Math.max(cloudResults.size(), deviceResults.size()))));

            // Compare ranking order for overlapping articles
            for (int i = 0; i < Math.min(cloudResults.size(), deviceResults.size()); i++) {
                ArticleData c = cloudResults.get(i);
                ArticleData d = deviceResults.get(i);
                Log.i(TAG, String.format("  Rank %d: cloud=%.2f \"%s\" | device=%.2f \"%s\"",
                        i + 1, c.interestScore, truncate(c.title, 40),
                        d.interestScore, truncate(d.title, 40)));
            }

            // Compare summary quality side-by-side
            Log.i(TAG, "--- Summary comparison (first 3) ---");
            for (int i = 0; i < Math.min(3, cloudResults.size()); i++) {
                ArticleData c = cloudResults.get(i);
                Log.i(TAG, "  CLOUD #" + (i + 1) + ": " + nullToEmpty(c.llmSummary));
                // Find same article in device results
                for (ArticleData d : deviceResults) {
                    if (d.title.equals(c.title)) {
                        Log.i(TAG, "  DEVICE   : " + nullToEmpty(d.llmSummary));
                        break;
                    }
                }
            }
        }

        Log.i(TAG, "=== LLM BENCHMARK END ===");
    }

    private static void logResults(String label, List<ArticleData> results) {
        for (int i = 0; i < results.size(); i++) {
            ArticleData a = results.get(i);
            Log.i(TAG, String.format("  %s #%d [%.2f] %s", label, i + 1, a.interestScore,
                    truncate(a.title, 60)));
            if (a.llmSummary != null && !a.llmSummary.isEmpty()) {
                Log.i(TAG, "    Summary: " + truncate(a.llmSummary, 120));
            }
        }
    }

    private static List<ArticleData> deepCopy(List<ArticleData> articles) {
        List<ArticleData> copy = new ArrayList<>();
        for (ArticleData a : articles) {
            ArticleData c = new ArticleData(a.title, a.originalDescription, a.link,
                    a.imageUrl, a.pubDate, a.sourceFeedUrl);
            copy.add(c);
        }
        return copy;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
