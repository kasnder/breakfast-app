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
import java.util.List;
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

    private Engine engine;

    public OnDeviceLlmClient(Context context, String modelPath, boolean useGpu) {
        this.context = context.getApplicationContext();
        this.modelPath = modelPath;
        this.useGpu = useGpu;
    }

    /**
     * Initializes the LiteRT-LM engine. This can take several seconds.
     * Must be called before any inference methods.
     */
    public synchronized void initialize() throws Exception {
        if (engine != null) return;

        EngineConfig config = new EngineConfig(
                modelPath,
                useGpu ? new Backend.GPU() : new Backend.CPU(),
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
     * Ranks articles by relevance to the user's interest profile, then summarizes the top ones.
     *
     * Strategy (fits within ~4096 token context per call):
     * 1. Score each article's relevance using a short prompt per article
     * 2. Sort by score, take the top {@code count}
     * 3. Summarize each of the top articles individually
     */
    public List<ArticleData> rankAndSummarize(List<ArticleData> articles, String interestProfile, int count) {
        if (articles.isEmpty()) return new ArrayList<>();
        if (engine == null) {
            Log.e(TAG, "Engine not initialized");
            return fallbackTopArticles(articles, count);
        }

        try {
            // Step 1: Score articles for relevance (lightweight prompt per article)
            List<ScoredArticle> scored = new ArrayList<>();
            int maxArticles = Math.min(articles.size(), 30);

            for (int i = 0; i < maxArticles; i++) {
                ArticleData article = articles.get(i);
                float score = scoreArticle(article, interestProfile);
                scored.add(new ScoredArticle(article, score));
            }

            // Sort by score descending
            Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));

            // Step 2: Summarize the top articles
            int topCount = Math.min(count, scored.size());
            List<ArticleData> result = new ArrayList<>();
            for (int i = 0; i < topCount; i++) {
                ScoredArticle sa = scored.get(i);
                sa.article.interestScore = sa.score;
                sa.article.llmSummary = summarizeArticle(sa.article);
                result.add(sa.article);
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error in on-device ranking", e);
            return fallbackTopArticles(articles, count);
        }
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

            String userPrompt = "User interests: " + truncate(interestProfile, 200)
                    + "\n\nArticle title: " + truncate(article.title, 150)
                    + "\nArticle description: " + truncate(article.originalDescription, 300)
                    + "\n\nRelevance score (0.0-1.0):";

            ConversationConfig convConfig = new ConversationConfig(
                    Contents.of(systemPrompt),
                    null,
                    new SamplerConfig(1, 0.95f, 0.1f),
                    null,
                    false
            );

            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt);
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

            String userPrompt = "Title: " + truncate(article.title, 200)
                    + "\nDescription: " + truncate(article.originalDescription, 500)
                    + "\n\nSummary:";

            ConversationConfig convConfig = new ConversationConfig(
                    Contents.of(systemPrompt),
                    null,
                    new SamplerConfig(10, 0.95f, 0.3f),
                    null,
                    false
            );

            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt);
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
     * Kept shorter than the cloud version due to context limits.
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
            String trimmedData = trimDashboardData(structuredDashboardData, 1500);

            String systemPrompt = "You are writing a brief spoken morning briefing. "
                    + "Be concise, calm, and informative. Cover weather, then headlines, then personal items. "
                    + "Do not invent facts. Keep it under 3 minutes when read aloud.";

            String userPrompt = "Dashboard data:\n\n" + trimmedData
                    + "\n\nWrite a brief spoken morning briefing script:";

            ConversationConfig convConfig = new ConversationConfig(
                    Contents.of(systemPrompt),
                    null,
                    new SamplerConfig(10, 0.95f, 0.4f),
                    null,
                    false
            );

            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt);
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
     * Returns the default path for the Gemma3-1B model.
     */
    public static String getDefaultModelPath(Context context) {
        return new File(getModelDirectory(context),
                "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm").getAbsolutePath();
    }

    // --- Helpers ---

    private float parseScore(String text) {
        try {
            // Extract the first decimal number from the response
            Pattern pattern = Pattern.compile("(\\d+\\.?\\d*)");
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                float score = Float.parseFloat(matcher.group(1));
                return Math.max(0f, Math.min(1f, score));
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
        Collections.sort(sorted, (a, b) -> Long.compare(b.pubDate, a.pubDate));
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
