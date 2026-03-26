package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for an OpenAI-compatible LLM API.
 * Ranks articles by relevance to the user's interest profile and generates summaries.
 */
public class LlmClient {
    private static final String TAG = "LlmClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_ARTICLES_PER_SOURCE_TO_RANK = 50;
    private static final int DESCRIPTION_PROMPT_LIMIT = 200;
    private static final double RANKING_TEMPERATURE = 0.3;
    private static final double BRIEFING_TEMPERATURE = 0.4;

    private final OkHttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public LlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Given a list of articles and the user's interest profile, rank them by relevance
     * and return the top {@code count} with LLM-generated summaries.
     * Runs synchronously — call from a background thread.
     */
    public List<ArticleData> rankAndSummarize(List<ArticleData> articles, String interestProfile, int count) {
        if (articles.isEmpty()) return new ArrayList<>();
        long startMs = System.currentTimeMillis();

        try {
            // Build the prompt
            StringBuilder articleList = new StringBuilder();
            List<ArticleData> candidateArticles = buildRankingPool(articles);
            for (int i = 0; i < candidateArticles.size(); i++) {
                ArticleData a = candidateArticles.get(i);
                articleList.append(String.format("[%d] Title: %s\nDescription: %s\n\n",
                        i, a.title, truncate(a.originalDescription, DESCRIPTION_PROMPT_LIMIT)));
            }

            String systemPrompt = "You are a personal news curator for the Breakfast morning newspaper app. " +
                    "Your job is to select the " + count + " most interesting articles for the user based on their interests, " +
                    "then provide a 1-2 sentence summary for each.\n\n" +
                    "User's interest profile:\n" + interestProfile;

            String userPrompt = "Here are today's articles from the user's RSS feeds:\n\n" +
                    articleList.toString() +
                    "\nRespond with ONLY a JSON array of exactly " + count + " objects (or fewer if less than " + count + " articles are available). " +
                    "Each object should have:\n" +
                    "- \"index\": the article number from the list above\n" +
                    "- \"score\": relevance score from 0.0 to 1.0\n" +
                    "- \"summary\": a 1-2 sentence summary of the article\n\n" +
                    "Return ONLY the JSON array, no other text.";

            // Build the API request
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("temperature", RANKING_TEMPERATURE);

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.put(sysMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.put(userMsg);

            requestBody.put("messages", messages);

            String url = baseUrl + "/chat/completions";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            Response response = client.newCall(request).execute();

            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "LLM API call failed: " + response.code());
                return fallbackTopArticles(articles, count);
            }

            String responseStr = response.body().string();
            JSONObject responseJson = new JSONObject(responseStr);
            JSONArray choices = responseJson.getJSONArray("choices");
            String content = choices.getJSONObject(0).getJSONObject("message").getString("content");

            // Parse the JSON response — strip markdown code fences if present
            content = content.trim();
            if (content.startsWith("```")) {
                // Remove opening fence (e.g., ```json)
                int firstNewline = content.indexOf('\n');
                if (firstNewline != -1) {
                    content = content.substring(firstNewline + 1);
                }
                // Remove closing fence
                if (content.endsWith("```")) {
                    content = content.substring(0, content.length() - 3);
                }
                content = content.trim();
            }
            JSONArray rankedArray = new JSONArray(content);

            List<ArticleData> result = new ArrayList<>();
            for (int i = 0; i < rankedArray.length() && i < count; i++) {
                JSONObject item = rankedArray.getJSONObject(i);
                int index = item.getInt("index");
                if (index >= 0 && index < candidateArticles.size()) {
                    ArticleData article = candidateArticles.get(index);
                    article.interestScore = (float) item.getDouble("score");
                    article.llmSummary = item.getString("summary");
                    result.add(article);
                }
            }

            // Sort by score descending
            Collections.sort(result, (a, b) -> Float.compare(b.interestScore, a.interestScore));
            Log.i(TAG, "Cloud rankAndSummarize: " + result.size() + " articles in "
                    + (System.currentTimeMillis() - startMs) + " ms");
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Error in LLM ranking after " + (System.currentTimeMillis() - startMs) + " ms", e);
            return fallbackTopArticles(articles, count);
        }
    }

    /**
     * Turns structured dashboard data into a spoken morning-briefing script.
     * Returns null when the LLM is unavailable or the response is unusable.
     */
    public String generateMorningBriefingScript(String structuredDashboardData) {
        if (structuredDashboardData == null || structuredDashboardData.trim().isEmpty()) {
            return null;
        }

        try {
            String systemPrompt = "You are writing a spoken morning briefing for a commuter. "
                    + "Turn the structured dashboard data below into a natural 4-8 minute script. "
                    + "Be concise, calm, and informative. "
                    + "Cover weather first, then the most important headlines, then calendar and personal items. "
                    + "Do not invent facts. Use only the provided information.";

            String userPrompt = "Structured dashboard data:\n\n"
                    + structuredDashboardData
                    + "\n\nReturn only the finished script.";

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("temperature", BRIEFING_TEMPERATURE);

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.put(sysMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.put(userMsg);

            requestBody.put("messages", messages);

            String url = baseUrl + "/chat/completions";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Morning briefing script call failed: " + response.code());
                    return null;
                }

                String responseStr = response.body().string();
                JSONObject responseJson = new JSONObject(responseStr);
                JSONArray choices = responseJson.getJSONArray("choices");
                String content = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
                return stripMarkdownCodeFences(content);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating morning briefing script", e);
            return null;
        }
    }

    /**
     * Fallback when LLM is unavailable: return the most recent articles without summaries.
     */
    private List<ArticleData> fallbackTopArticles(List<ArticleData> articles, int count) {
        List<ArticleData> sorted = new ArrayList<>(articles);
        Collections.sort(sorted, (a, b) -> Long.compare(b.pubDate, a.pubDate));
        List<ArticleData> top = new ArrayList<>();
        for (int i = 0; i < Math.min(count, sorted.size()); i++) {
            top.add(sorted.get(i));
        }
        return top;
    }

    private List<ArticleData> buildRankingPool(List<ArticleData> articles) {
        Map<String, List<ArticleData>> bySource = new LinkedHashMap<>();
        for (ArticleData article : articles) {
            String key = article.sourceFeedUrl != null ? article.sourceFeedUrl : "";
            List<ArticleData> bucket = bySource.computeIfAbsent(key, k -> new ArrayList<>());
            if (bucket.size() < MAX_ARTICLES_PER_SOURCE_TO_RANK) {
                bucket.add(article);
            }
        }
        if (bySource.size() <= 1) {
            return new ArrayList<>(articles.subList(0,
                    Math.min(articles.size(), MAX_ARTICLES_PER_SOURCE_TO_RANK)));
        }

        List<List<ArticleData>> buckets = new ArrayList<>(bySource.values());
        List<ArticleData> result = new ArrayList<>();
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

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    private String stripMarkdownCodeFences(String content) {
        if (content == null) return null;
        content = content.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline != -1) {
                content = content.substring(firstNewline + 1);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
        }
        return content.trim();
    }
}
