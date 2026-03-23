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
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-device LLM client using LiteRT-LM for article summarization and ranking.
 * Packs multiple articles into a single prompt when they fit the ~4096-token context window,
 * falling back to one-article-at-a-time processing for any items whose batch response
 * cannot be parsed.
 * Runs synchronously — call from a background thread.
 */
public class OnDeviceLlmClient {
    private static final String TAG = "OnDeviceLlmClient";

    /**
     * Conservative characters-per-token estimate used for context budget calculations.
     * English text averages ~4 chars/token; using 3 to stay safely inside the window.
     */
    private static final int CHARS_PER_TOKEN = 3;

    /** Total token context window of the on-device models. */
    private static final int CONTEXT_TOKENS = 4096;

    // --- Budget reservation constants (all values in tokens) ---

    /** Tokens reserved for the system prompt in a scoring call. */
    private static final int SCORING_SYSTEM_PROMPT_TOKENS = 80;
    /** Maximum number of articles scored per {@link #rankAndSummarize} invocation. */
    private static final int MAX_ARTICLES_TO_SCORE = 30;
    /** Output tokens per scored article ("N: 0.85\n" ≈ 4–5 tokens; 8 gives headroom). */
    private static final int OUTPUT_TOKENS_PER_SCORE = 8;
    /** Framing/instruction overhead tokens reserved in a scoring call. */
    private static final int SCORING_FRAMING_TOKENS = 50;

    /** Tokens reserved for the system prompt in a summarising call. */
    private static final int SUMMARISING_SYSTEM_PROMPT_TOKENS = 60;
    /** Output tokens per summarised article (1–2 sentences ≈ 40–50 tokens; 55 gives headroom). */
    private static final int OUTPUT_TOKENS_PER_SUMMARY = 55;
    /** Framing/instruction overhead tokens reserved in a summarising call. */
    private static final int SUMMARISING_FRAMING_TOKENS = 30;

    /**
     * Characters of fixed formatting per article inside a batch prompt
     * ("N. Title: \nDescription: \n\n" is roughly 30 chars).
     */
    private static final int ARTICLE_PROMPT_FORMATTING_CHARS = 30;

    private final Context context;
    private final String modelPath;
    private final boolean useGpu;
    private final boolean batchingEnabled;
    private final boolean isE2B;

    private Engine engine;

    public OnDeviceLlmClient(Context context, String modelPath, boolean useGpu) {
        this(context, modelPath, useGpu, true);
    }

    public OnDeviceLlmClient(Context context, String modelPath, boolean useGpu, boolean batchingEnabled) {
        this.context = context.getApplicationContext();
        this.modelPath = modelPath;
        this.useGpu = useGpu;
        this.batchingEnabled = batchingEnabled;
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
            // before the scoring cap is applied.
            List<ArticleData> interleaved = interleaveBySource(articles);
            int maxArticles = Math.min(interleaved.size(), MAX_ARTICLES_TO_SCORE);

            // Step 1: Score articles for relevance in batches (fewer LLM calls).
            List<ScoredArticle> scored = new ArrayList<>();
            if (listener != null) listener.onScoringStarted(maxArticles);

            long scoreStart = System.currentTimeMillis();
            int i = 0;
            while (i < maxArticles) {
                int batchSize = batchingEnabled 
                        ? computeBatchSize(interleaved, i, maxArticles, scoringContentBudget(interestProfile))
                        : 1;
                List<ArticleData> batch = interleaved.subList(i, i + batchSize);
                List<Float> scores = scoreArticlesBatch(batch, interestProfile);
                for (int j = 0; j < scores.size(); j++) {
                    scored.add(new ScoredArticle(batch.get(j), scores.get(j)));
                    if (listener != null) listener.onArticleScored(i + j + 1, maxArticles);
                }
                i += batchSize;
            }
            Log.i(TAG, "Scoring " + maxArticles + " articles took "
                    + (System.currentTimeMillis() - scoreStart) + " ms");

            // Sort by score descending
            scored.sort((a, b) -> Float.compare(b.score, a.score));

            // Step 2: Summarize the top articles in batches.
            int topCount = Math.min(count, scored.size());
            List<ArticleData> result = new ArrayList<>();
            if (listener != null) listener.onSummarizingStarted(topCount);
            long sumStart = System.currentTimeMillis();
            int j = 0;
            while (j < topCount) {
                int batchSize = batchingEnabled
                        ? computeBatchSizeFromScored(scored, j, topCount, summarizingContentBudget(topCount - j))
                        : 1;
                // Build a zero-copy view over the scored list instead of allocating a new list.
                final int jOffset = j;
                List<ArticleData> batch = new AbstractList<ArticleData>() {
                    @Override public ArticleData get(int index) {
                        return scored.get(jOffset + index).article;
                    }
                    @Override public int size() { return batchSize; }
                };
                List<String> summaries = summarizeArticlesBatch(batch);
                for (int k = 0; k < batch.size(); k++) {
                    ScoredArticle sa = scored.get(j + k);
                    sa.article.interestScore = sa.score;
                    sa.article.llmSummary = summaries.get(k);
                    result.add(sa.article);
                    if (listener != null) listener.onArticleSummarized(j + k + 1, topCount);
                }
                j += batchSize;
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

    // ---- Batch scoring ----

    /**
     * Scores a batch of articles in a single LLM call.
     * The response is expected to be one line per article: "N: score".
     * Any article whose score cannot be parsed falls back to {@link #scoreArticleSingle}.
     *
     * @return list of scores in the same order as {@code batch}
     */
    private List<Float> scoreArticlesBatch(List<ArticleData> batch, String interestProfile) {
        if (batch.isEmpty()) return new ArrayList<>();
        if (batch.size() == 1) {
            List<Float> r = new ArrayList<>();
            r.add(scoreArticleSingle(batch.get(0), interestProfile));
            return r;
        }

        // No interest profile – score every article by recency without an LLM call.
        if (interestProfile == null || interestProfile.trim().isEmpty()) {
            List<Float> r = new ArrayList<>();
            for (ArticleData a : batch) r.add(scoreByRecency(a));
            return r;
        }

        try {
            String systemPrompt = "You are a relevance scorer. "
                    + "Given user interests and a numbered list of articles, respond with ONLY "
                    + "a numbered list of relevance scores from 0.0 to 1.0, one per line in the "
                    + "format \"N: score\" (example: \"1: 0.85\"). Nothing else.";

            StringBuilder userPrompt = new StringBuilder();
            userPrompt.append("User interests: ")
                      .append(truncate(interestProfile, interestLimit()))
                      .append("\n\nArticles:\n");
            for (int i = 0; i < batch.size(); i++) {
                ArticleData a = batch.get(i);
                userPrompt.append(i + 1).append(". Title: ")
                          .append(truncate(a.title, 300))
                          .append("\nDescription: ")
                          .append(truncate(a.originalDescription, descLimit()))
                          .append("\n\n");
            }
            userPrompt.append("Relevance scores (0.0-1.0):");

            int maxOutputTokens = batch.size() * OUTPUT_TOKENS_PER_SCORE;
            SamplerConfig samplerConfig = new SamplerConfig(maxOutputTokens, 1.0, 0.0, 0);
            ConversationConfig convConfig = new ConversationConfig(
                    Contents.Companion.of(systemPrompt),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    samplerConfig,
                    null,
                    false
            );

            List<Float> parsed;
            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt.toString(),
                        Collections.emptyMap());
                parsed = parseBatchScores(response.toString().trim(), batch.size());
            }

            // Fill in per-article fallback for any items we could not parse.
            List<Float> result = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                if (i < parsed.size() && parsed.get(i) != null) {
                    result.add(parsed.get(i));
                } else {
                    Log.w(TAG, "Batch score missing for article " + (i + 1)
                            + ", falling back to single call");
                    result.add(scoreArticleSingle(batch.get(i), interestProfile));
                }
            }
            return result;

        } catch (Exception e) {
            Log.w(TAG, "Batch scoring failed, falling back to per-article scoring", e);
            List<Float> r = new ArrayList<>();
            for (ArticleData a : batch) r.add(scoreArticleSingle(a, interestProfile));
            return r;
        }
    }

    /**
     * Parses a batch scoring response into a list of floats.
     * Accepts lines like "1: 0.8", "[2] 0.3", "3. 0.95", or bare numbers on separate lines.
     * Returns a list whose size may be smaller than {@code expectedCount} if the model
     * produced fewer lines than expected.
     */
    private List<Float> parseBatchScores(String response, int expectedCount) {
        List<Float> scores = new ArrayList<>();
        // First try: numbered lines "N: score" (with flexible delimiters).
        Pattern numberedLine = Pattern.compile(
                "^\\[?(\\d+)\\]?[:.)]\\s*(\\d+\\.?\\d*)");
        String[] lines = response.split("\\r?\\n");
        float[] byIndex = new float[expectedCount];
        boolean[] filled = new boolean[expectedCount];
        boolean anyNumbered = false;
        for (String line : lines) {
            Matcher m = numberedLine.matcher(line.trim());
            if (m.find()) {
                try {
                    int idx = Integer.parseInt(m.group(1)) - 1;
                    float score = Float.parseFloat(m.group(2));
                    if (idx >= 0 && idx < expectedCount) {
                        byIndex[idx] = Math.max(0f, Math.min(1f, score));
                        filled[idx] = true;
                        anyNumbered = true;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        if (anyNumbered) {
            for (int i = 0; i < expectedCount; i++) {
                scores.add(filled[i] ? byIndex[i] : null);
            }
            return scores;
        }
        // Fallback: sequential non-empty lines, each containing a number.
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            scores.add(parseScore(line));
            if (scores.size() == expectedCount) break;
        }
        return scores;
    }

    /**
     * Scores a single article's relevance to the interest profile.
     * Returns a float between 0.0 and 1.0.
     */
    private float scoreArticleSingle(ArticleData article, String interestProfile) {
        if (interestProfile == null || interestProfile.trim().isEmpty()) {
            return scoreByRecency(article);
        }

        try {
            String systemPrompt = "You are a relevance scorer. Given an article and user interests, "
                    + "respond with ONLY a number from 0.0 to 1.0 indicating relevance. Nothing else.";

            String userPrompt = "User interests: " + truncate(interestProfile, interestLimit())
                    + "\n\nArticle title: " + truncate(article.title, 300)
                    + "\nArticle description: " + truncate(article.originalDescription, descLimit())
                    + "\n\nRelevance score (0.0-1.0):";

            SamplerConfig samplerConfig = new SamplerConfig(OUTPUT_TOKENS_PER_SCORE, 1.0, 0.0, 0);
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

    private float scoreByRecency(ArticleData article) {
        long ageHours = (System.currentTimeMillis() - article.pubDate) / (1000 * 60 * 60);
        return Math.max(0f, 1f - (ageHours / 24f));
    }

    // ---- Batch summarising ----

    /**
     * Summarises a batch of articles in a single LLM call.
     * The response is expected to be lines of the form "N: summary text".
     * Any article whose summary cannot be parsed falls back to {@link #summarizeArticleSingle}.
     *
     * @return list of summaries (may contain null for articles that failed) in the same order
     */
    private List<String> summarizeArticlesBatch(List<ArticleData> batch) {
        if (batch.isEmpty()) return new ArrayList<>();
        if (batch.size() == 1) {
            List<String> r = new ArrayList<>();
            r.add(summarizeArticleSingle(batch.get(0)));
            return r;
        }

        try {
            String systemPrompt = "You are a concise news summarizer. "
                    + "For each numbered article, write a 1-2 sentence factual summary. "
                    + "Respond with ONLY a numbered list in the format \"N: summary\" "
                    + "(example: \"1: Scientists discover…\"). Nothing else.";

            StringBuilder userPrompt = new StringBuilder("Articles:\n");
            for (int i = 0; i < batch.size(); i++) {
                ArticleData a = batch.get(i);
                userPrompt.append(i + 1).append(". Title: ")
                          .append(truncate(a.title, 300))
                          .append("\nDescription: ")
                          .append(truncate(a.originalDescription, descLimit()))
                          .append("\n\n");
            }
            userPrompt.append("Summaries:");

            int maxOutputTokens = batch.size() * OUTPUT_TOKENS_PER_SUMMARY;
            SamplerConfig samplerConfig = new SamplerConfig(maxOutputTokens, 0.95, 0.2, 0);
            ConversationConfig convConfig = new ConversationConfig(
                    Contents.Companion.of(systemPrompt),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    samplerConfig,
                    null,
                    false
            );

            List<String> parsed;
            try (Conversation conversation = engine.createConversation(convConfig)) {
                Message response = conversation.sendMessage(userPrompt.toString(),
                        Collections.emptyMap());
                parsed = parseBatchSummaries(response.toString().trim(), batch.size());
            }

            // Fill in per-article fallback for any items we could not parse.
            List<String> result = new ArrayList<>(batch.size());
            for (int i = 0; i < batch.size(); i++) {
                if (i < parsed.size() && parsed.get(i) != null && !parsed.get(i).isEmpty()) {
                    result.add(parsed.get(i));
                } else {
                    Log.w(TAG, "Batch summary missing for article " + (i + 1)
                            + ", falling back to single call");
                    result.add(summarizeArticleSingle(batch.get(i)));
                }
            }
            return result;

        } catch (Exception e) {
            Log.w(TAG, "Batch summarising failed, falling back to per-article summarising", e);
            List<String> r = new ArrayList<>();
            for (ArticleData a : batch) r.add(summarizeArticleSingle(a));
            return r;
        }
    }

    /**
     * Parses a batch summarising response into a list of strings.
     * Handles multi-line summaries: lines that do not start with "N:" are treated as
     * continuations of the previous article's summary.
     * Returns a list of size {@code expectedCount}; slots where the model produced no output
     * remain null.
     */
    private List<String> parseBatchSummaries(String response, int expectedCount) {
        List<String> summaries = new ArrayList<>(Collections.<String>nCopies(expectedCount, null));
        Pattern startPattern = Pattern.compile("^\\[?(\\d+)\\]?[:.)]\\s*(.*)$");

        int currentIdx = -1;
        StringBuilder currentText = new StringBuilder();

        for (String rawLine : response.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            Matcher m = startPattern.matcher(line);
            if (m.matches()) {
                // Flush the previous article's accumulated text.
                if (currentIdx >= 0 && currentIdx < expectedCount) {
                    String text = currentText.toString().trim();
                    if (!text.isEmpty()) summaries.set(currentIdx, text);
                }
                try {
                    currentIdx = Integer.parseInt(m.group(1)) - 1;
                    currentText = new StringBuilder(m.group(2) != null ? m.group(2) : "");
                } catch (NumberFormatException e) {
                    currentIdx = -1;
                }
            } else if (currentIdx >= 0) {
                // Continuation of the current article's summary.
                if (currentText.length() > 0) currentText.append(" ");
                currentText.append(line);
            }
        }
        // Flush the last article.
        if (currentIdx >= 0 && currentIdx < expectedCount) {
            String text = currentText.toString().trim();
            if (!text.isEmpty()) summaries.set(currentIdx, text);
        }
        return summaries;
    }

    /**
     * Summarizes a single article in 1-2 sentences.
     */
    private String summarizeArticleSingle(ArticleData article) {
        try {
            String systemPrompt = "You are a concise news summarizer. "
                    + "Summarize the article in 1-2 sentences. Be factual and brief. "
                    + "Respond with ONLY the summary text. Nothing else.";

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

    // --- Batch sizing ---

    /**
     * Returns how many articles starting at {@code startIdx} fit in a single LLM call,
     * given {@code contentBudget} chars available for article text.
     * Always returns at least 1 so processing never stalls.
     */
    private int computeBatchSize(List<ArticleData> articles, int startIdx, int maxIdx,
            int contentBudget) {
        int remaining = contentBudget;
        int count = 0;
        for (int i = startIdx; i < maxIdx; i++) {
            ArticleData a = articles.get(i);
            int articleChars = ARTICLE_PROMPT_FORMATTING_CHARS
                    + Math.min(a.title != null ? a.title.length() : 0, 300)
                    + Math.min(a.originalDescription != null
                            ? a.originalDescription.length() : 0, descLimit());
            if (count > 0 && articleChars > remaining) break;
            remaining -= articleChars;
            count++;
        }
        return Math.max(1, count);
    }

    /**
     * Like {@link #computeBatchSize} but works directly on a {@link ScoredArticle} list,
     * avoiding the creation of an intermediate {@code List<ArticleData>}.
     */
    private int computeBatchSizeFromScored(List<ScoredArticle> scored, int startIdx, int maxIdx,
            int contentBudget) {
        int remaining = contentBudget;
        int count = 0;
        for (int i = startIdx; i < maxIdx; i++) {
            ArticleData a = scored.get(i).article;
            int articleChars = ARTICLE_PROMPT_FORMATTING_CHARS
                    + Math.min(a.title != null ? a.title.length() : 0, 300)
                    + Math.min(a.originalDescription != null
                            ? a.originalDescription.length() : 0, descLimit());
            if (count > 0 && articleChars > remaining) break;
            remaining -= articleChars;
            count++;
        }
        return Math.max(1, count);
    }

    /**
     * Character budget available for article content in a scoring call.
     * Reserves tokens for: system prompt, interest profile, output (up to
     * {@link #MAX_ARTICLES_TO_SCORE} × {@link #OUTPUT_TOKENS_PER_SCORE}), and framing.
     */
    private int scoringContentBudget(String interestProfile) {
        int interestTokens = (interestProfile != null
                ? Math.min(interestProfile.length(), interestLimit()) : 0) / CHARS_PER_TOKEN;
        int reserveTokens = SCORING_SYSTEM_PROMPT_TOKENS + interestTokens
                + MAX_ARTICLES_TO_SCORE * OUTPUT_TOKENS_PER_SCORE + SCORING_FRAMING_TOKENS;
        return Math.max(0, (CONTEXT_TOKENS - reserveTokens) * CHARS_PER_TOKEN);
    }

    /**
     * Character budget available for article content in a summarising call.
     * Reserves tokens for: system prompt, output ({@code remainingArticles} ×
     * {@link #OUTPUT_TOKENS_PER_SUMMARY}), and framing.
     *
     * @param remainingArticles number of articles still to be summarised; used to scale the
     *                          output reservation without over-reserving for small batches
     */
    private int summarizingContentBudget(int remainingArticles) {
        int reserveTokens = SUMMARISING_SYSTEM_PROMPT_TOKENS
                + remainingArticles * OUTPUT_TOKENS_PER_SUMMARY + SUMMARISING_FRAMING_TOKENS;
        return Math.max(0, (CONTEXT_TOKENS - reserveTokens) * CHARS_PER_TOKEN);
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
