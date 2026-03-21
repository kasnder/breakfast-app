package net.kollnig.breakfast.news;

import android.content.Context;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device LLM client using LiteRT-LM for article summarization and ranking.
 * Processes articles one at a time due to the ~4096 token context window of on-device models.
 * Runs synchronously — call from a background thread.
 */
public class OnDeviceLlmClient {
    private static final String TAG = "OnDeviceLlmClient";

    private final Context context;
    private final String modelPath;
    private final boolean useGpu;
    private final boolean isE2B;

    private Engine engine;

    public OnDeviceLlmClient(Context context, String modelPath, boolean useGpu) {
        this.context = context.getApplicationContext();
        this.modelPath = modelPath;
        this.useGpu = useGpu;
        this.isE2B = modelPath != null && modelPath.toLowerCase().contains("e2b");
    }

    /** Max chars for article description prompts (scoring / summarisation). */
    private int descLimit() { return isE2B ? 3000 : 500; }

    /** Max chars for the interest-profile snippet in prompts. */
    private int interestLimit() { return isE2B ? 2000 : 200; }

    /** Max chars for the morning-briefing input. */
    private int briefingInputLimit() { return isE2B ? 12000 : 1500; }

    /**
     * Initializes the LiteRT-LM engine. This can take several seconds.
     * Must be called before any inference methods.
     */
    public synchronized void initialize() throws Exception {
        if (engine != null) return;

        EngineConfig config = new EngineConfig(
                modelPath,
                useGpu ? new Backend.GPU() : new Backend.CPU(),
                null, // visionBackend
                null, // audioBackend
                null, // maxNumTokens
                context.getCacheDir().getPath()
        );
        engine = new Engine(config);
        engine.initialize();
        Log.i(TAG, "On-device LLM engine initialized: " + modelPath);
    }

    /**
     * Shuts down the engine and frees resources.
     */
    public synchronized void close() {
        if (engine != null) {
            try {
                engine.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing engine", e);
            }
            engine = null;
        }
    }

    public boolean isInitialized() {
        return engine != null;
    }

    /**
     * Callback interface for tracking on-device LLM processing progress.
     * All callbacks are invoked from the background thread that runs the LLM.
     */
    public interface ProgressListener {
        /**
         * Called at the start of the weighting (scoring) phase.
         * @param total number of articles to be scored
         */
        void onScoringStarted(int total);

        /**
         * Called after each article has been scored.
         * @param scored number of articles scored so far
         * @param total  total number of articles to score
         */
        void onArticleScored(int scored, int total);

        /**
         * Called at the start of the summarising phase.
         * @param total number of articles to be summarised
         */
        void onSummarizingStarted(int total);

        /**
         * Called after each article has been summarised.
         * @param summarized number of articles summarised so far
         * @param total      total number of articles to summarise
         */
        void onArticleSummarized(int summarized, int total);
    }

    /**
     * Ranks articles by relevance to the user's interest profile, then summarizes the top ones.
     *
     * Strategy (fits within ~4096 token context per call):
     * 1. Score each article's relevance using a short prompt per article
     * 2. Sort by score, take the top {@code count}
     * 3. Summarize each of the top articles individually
     *
     * Articles are interleaved across feed sources before the 30-article cap so that every
     * configured source has a fair chance of being represented in the ranked output.
     */
    public List<ArticleData> rankAndSummarize(List<ArticleData> articles, String interestProfile,
            int count) {
        return rankAndSummarize(articles, interestProfile, count, null);
    }

    /**
     * Like {@link #rankAndSummarize(List, String, int)} but reports progress via
     * {@code listener} (may be null).
     */
    public List<ArticleData> rankAndSummarize(List<ArticleData> articles, String interestProfile,
            int count, ProgressListener listener) {
        if (articles.isEmpty()) return new ArrayList<>();
        if (engine == null) {
            Log.e(TAG, "Engine not initialized");
            return fallbackTopArticles(articles, count);
        }

        long startMs = System.currentTimeMillis();
        try {
            // Interleave articles across feed sources so every source gets fair representation
            // before the 30-article scoring cap is applied.
            List<ArticleData> interleaved = interleaveBySource(articles);
            int maxArticles = Math.min(interleaved.size(), 30);

            // Step 1: Score articles for relevance (lightweight prompt per article)
            List<ScoredArticle> scored = new ArrayList<>();
            if (listener != null) listener.onScoringStarted(maxArticles);

            long scoreStart = System.currentTimeMillis();
            for (int i = 0; i < maxArticles; i++) {
                ArticleData article = interleaved.get(i);
                float score = scoreArticle(article, interestProfile);
                scored.add(new ScoredArticle(article, score));
                if (listener != null) listener.onArticleScored(i + 1, maxArticles);
            }
            Log.i(TAG, "Scoring " + maxArticles + " articles took "
                    + (System.currentTimeMillis() - scoreStart) + " ms");

            // Sort by score descending
            scored.sort((a, b) -> Float.compare(b.score, a.score));

            // Step 2: Summarize the top articles
            int topCount = Math.min(count, scored.size());
            List<ArticleData> result = new ArrayList<>();
            if (listener != null) listener.onSummarizingStarted(topCount);
            long sumStart = System.currentTimeMillis();
            for (int i = 0; i < topCount; i++) {
                ScoredArticle sa = scored.get(i);
                sa.article.interestScore = sa.score;
                sa.article.llmSummary = summarizeArticle(sa.article);
                result.add(sa.article);
                if (listener != null) listener.onArticleSummarized(i + 1, topCount);
            }
            Log.i(TAG, "Summarizing " + topCount + " articles took "
                    + (System.currentTimeMillis() - sumStart) + " ms");
            Log.i(TAG, "On-device rankAndSummarize total: " + result.size() + " articles in "
                    + (System.currentTimeMillis() - startMs) + " ms");

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error in on-device ranking", e);
            return fallbackTopArticles(articles, count);
        }
    }

    /**
     * Interleaves articles from different feed sources in a round-robin fashion so that no
     * single source dominates the articles that make it past the scoring-count cap.
     * The relative order within each source is preserved (newest-first as they come from
     * the fetcher).
     */
    private List<ArticleData> interleaveBySource(List<ArticleData> articles) {
        // Group by source feed URL, preserving insertion order.
        Map<String, List<ArticleData>> bySource = new LinkedHashMap<>();
        for (ArticleData article : articles) {
            String key = article.sourceFeedUrl != null ? article.sourceFeedUrl : "";
            bySource.computeIfAbsent(key, k -> new ArrayList<>()).add(article);
        }
        if (bySource.size() <= 1) {
            // Only one source (or no source info) – no interleaving needed.
            return articles;
        }
        List<List<ArticleData>> buckets = new ArrayList<>(bySource.values());
        List<ArticleData> result = new ArrayList<>(articles.size());
        int maxBucketSize = 0;
        for (List<ArticleData> bucket : buckets) {
            if (bucket.size() > maxBucketSize) maxBucketSize = bucket.size();
        }
        for (int i = 0; i < maxBucketSize; i++) {
            for (List<ArticleData> bucket : buckets) {
                if (i < bucket.size()) {
                    result.add(bucket.get(i));
                }
            }
        }
        return result;
    }

    /**
     * Scores a single article's relevance to the interest profile.
     * Returns a float between 0.0 and 1.0.
     */
    private float scoreArticle(ArticleData article, String interestProfile) {
        if (interestProfile == null || interestProfile.trim().isEmpty()) {
            // No interest profile — score by recency (newer = higher)
            long ageHours = (System.currentTimeMillis() - article.pubDate) / (1000 * 60 * 60);
            return Math.max(0f, 1f - (ageHours / 24f));
        }

        try {
            String systemPrompt = "You are a relevance scorer. Given an article and user interests, "
                    + "respond with ONLY a number from 0.0 to 1.0 indicating relevance. Nothing else.";

            String userPrompt = "User interests: " + truncate(interestProfile, interestLimit())
                    + "\n\nArticle title: " + truncate(article.title, 300)
                    + "\nArticle description: " + truncate(article.originalDescription, descLimit())
                    + "\n\nRelevance score (0.0-1.0):";

            SamplerConfig samplerConfig = new SamplerConfig(1, 1.0, 0.0, 0);
            ConversationConfig convConfig = new ConversationConfig(
                    Contents.Companion.of(systemPrompt),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    samplerConfig,
                    null,
                    false
            );

            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt, Collections.emptyMap());
                String text = response.toString().trim();
                return parseScore(text);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to score article: " + article.title, e);
            return 0.5f;
        }
    }

    /**
     * Summarizes a single article in 1-2 sentences.
     */
    private String summarizeArticle(ArticleData article) {
        try {
            String systemPrompt = "You are a concise news summarizer. "
                    + "Summarize the article in 1-2 sentences. Be factual and brief.";

            String userPrompt = "Title: " + truncate(article.title, 300)
                    + "\nDescription: " + truncate(article.originalDescription, descLimit())
                    + "\n\nSummary:";

            SamplerConfig samplerConfig = new SamplerConfig(40, 0.95, 0.2, 0);
            ConversationConfig convConfig = new ConversationConfig(
                    Contents.Companion.of(systemPrompt),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    samplerConfig,
                    null,
                    false
            );

            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt, Collections.emptyMap());
                String summary = response.toString().trim();
                // Clean up any leading/trailing artifacts
                if (summary.isEmpty()) return null;
                return summary;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to summarize article: " + article.title, e);
            return null;
        }
    }

    /**
     * Generates a morning briefing script from structured dashboard data.
     */
    public String generateMorningBriefingScript(String structuredDashboardData) {
        if (structuredDashboardData == null || structuredDashboardData.trim().isEmpty()) {
            return null;
        }
        if (engine == null) {
            Log.e(TAG, "Engine not initialized");
            return null;
        }

        try {
            // Trim input to fit context — keep only the most important sections
            String trimmedData = trimDashboardData(structuredDashboardData, briefingInputLimit());

            String systemPrompt = "You are writing a brief spoken morning briefing. "
                    + "Be concise, calm, and informative. Cover weather, then headlines, then personal items. "
                    + "Do not invent facts. Keep it under 3 minutes when read aloud.";

            String userPrompt = "Dashboard data:\n\n" + trimmedData
                    + "\n\nWrite a brief spoken morning briefing script:";

            SamplerConfig samplerConfig = new SamplerConfig(40, 0.95, 0.7, 0);
            ConversationConfig convConfig = new ConversationConfig(
                    Contents.Companion.of(systemPrompt),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    samplerConfig,
                    null,
                    false
            );

            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt, Collections.emptyMap());
                String script = response.toString().trim();
                if (script.isEmpty()) return null;
                return script;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating on-device briefing script", e);
            return null;
        }
    }

    /**
     * Checks whether the model file exists at the configured path.
     */
    public static boolean isModelAvailable(String modelPath) {
        if (modelPath == null || modelPath.trim().isEmpty()) return false;
        return new File(modelPath).exists();
    }

    /**
     * Returns the default model storage directory for downloaded models.
     */
    public static File getModelDirectory(Context context) {
        File dir = new File(context.getFilesDir(), "litertlm-models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Returns the model path for a given variant.
     */
    public static String getModelPath(Context context, String variant) {
        String filename;
        if ("gemma-e2b".equals(variant)) {
            filename = "gemma-3n-E2B-it-int4.litertlm";
        } else {
            filename = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm";
        }
        return new File(getModelDirectory(context), filename).getAbsolutePath();
    }

    /**
     * Returns the default path for the current model (uses 1B as default).
     */
    public static String getDefaultModelPath(Context context) {
        return getModelPath(context, "gemma-1b");
    }

    // --- Helpers ---

    private float parseScore(String text) {
        try {
            // Extract the first decimal number from the response
            Pattern pattern = Pattern.compile("(\\d+\\.?\\d*)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String group = matcher.group(1);
                if (group != null) {
                    float score = Float.parseFloat(group);
                    return Math.max(0f, Math.min(1f, score));
                }
            }
        } catch (Exception ignored) {}
        return 0.5f;
    }

    private String trimDashboardData(String data, int maxChars) {
        if (data.length() <= maxChars) return data;
        // Try to cut at a section boundary
        String trimmed = data.substring(0, maxChars);
        int lastSection = trimmed.lastIndexOf("\n\n");
        if (lastSection > maxChars / 2) {
            return trimmed.substring(0, lastSection);
        }
        return trimmed;
    }

    private List<ArticleData> fallbackTopArticles(List<ArticleData> articles, int count) {
        List<ArticleData> sorted = new ArrayList<>(articles);
        sorted.sort((a, b) -> Long.compare(b.pubDate, a.pubDate));
        List<ArticleData> top = new ArrayList<>();
        for (int i = 0; i < Math.min(count, sorted.size()); i++) {
            top.add(sorted.get(i));
        }
        return top;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private static class ScoredArticle {
        final ArticleData article;
        final float score;

        ScoredArticle(ArticleData article, float score) {
            this.article = article;
            this.score = score;
        }
    }
}
