package net.kollnig.breakfast.todoist;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TodoistClient {
    private static final String BASE_URL = "https://api.todoist.com/api/v1";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String apiKey;

    public TodoistClient(String apiKey) {
        this.apiKey = sanitizeInlineValue(apiKey);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public List<TodoistTask> fetchProjectTasks(String projectId) throws Exception {
        JSONArray tasksJson = getPagedResults("/tasks", projectId);
        Map<String, TodoistSection> sections;
        try {
            sections = fetchSections(projectId);
        } catch (Exception ignored) {
            sections = new HashMap<>();
        }
        List<TodoistTask> tasks = new ArrayList<>();
        for (int i = 0; i < tasksJson.length(); i++) {
            JSONObject item = tasksJson.getJSONObject(i);
            TodoistTask task = new TodoistTask();
            task.id = item.optString("id");
            task.content = item.optString("content");
            task.description = item.optString("description");
            task.priority = item.optInt("priority", 1);
            task.url = item.optString("url");
            task.sectionId = item.optString("section_id");
            task.taskOrder = item.optInt("child_order",
                    item.optInt("order", Integer.MAX_VALUE));

            JSONObject dueObj = item.optJSONObject("due");
            if (dueObj != null) {
                TodoistTask.Due due = new TodoistTask.Due();
                due.date = dueObj.optString("date");
                due.datetime = dueObj.optString("datetime");
                due.string = dueObj.optString("string");
                due.isRecurring = dueObj.optBoolean("is_recurring", false);
                task.due = due;
            }

            TodoistSection section = sections.get(task.sectionId);
            if (section != null) {
                task.sectionName = section.name;
                task.sectionOrder = section.order;
            }
            tasks.add(task);
        }
        return tasks;
    }

    public TodoistTask addTask(String projectId, String content) throws Exception {
        String normalizedProjectId = sanitizeInlineValue(projectId);
        JSONObject requestBody = new JSONObject();
        requestBody.put("project_id", normalizedProjectId);
        requestBody.put("content", sanitizeInlineValue(content));
        JSONObject item = postJsonObject("/tasks", requestBody);

        TodoistTask task = new TodoistTask();
        task.id = item.optString("id");
        task.content = item.optString("content");
        task.description = item.optString("description");
        task.priority = item.optInt("priority", 1);
        task.url = item.optString("url");
        task.sectionId = item.optString("section_id");
        task.taskOrder = item.optInt("child_order", item.optInt("order", Integer.MAX_VALUE));
        JSONObject dueObj = item.optJSONObject("due");
        if (dueObj != null) {
            TodoistTask.Due due = new TodoistTask.Due();
            due.date = dueObj.optString("date");
            due.datetime = dueObj.optString("datetime");
            due.string = dueObj.optString("string");
            due.isRecurring = dueObj.optBoolean("is_recurring", false);
            task.due = due;
        }
        return task;
    }

    public void closeTask(String taskId) throws Exception {
        String normalizedTaskId = sanitizeInlineValue(taskId);
        postJsonObject("/tasks/" + normalizedTaskId + "/close", null);
    }

    private Map<String, TodoistSection> fetchSections(String projectId) throws Exception {
        JSONArray sectionsJson = getPagedResults("/sections", projectId);
        Map<String, TodoistSection> sections = new HashMap<>();
        for (int i = 0; i < sectionsJson.length(); i++) {
            JSONObject item = sectionsJson.getJSONObject(i);
            TodoistSection section = new TodoistSection();
            section.id = item.optString("id");
            section.name = item.optString("name");
            section.order = item.optInt("section_order",
                    item.optInt("order", Integer.MAX_VALUE));
            sections.put(section.id, section);
        }
        return sections;
    }

    private JSONArray getPagedResults(String path, String projectId) throws Exception {
        JSONArray combined = new JSONArray();
        String cursor = null;
        do {
            JSONObject page = getJsonObject(path, projectId, cursor);
            JSONArray results = page.optJSONArray("results");
            if (results != null) {
                for (int i = 0; i < results.length(); i++) {
                    combined.put(results.get(i));
                }
            }
            if (page.isNull("next_cursor")) {
                cursor = null;
            } else {
                cursor = page.optString("next_cursor", null);
            }
            if (cursor != null) {
                cursor = cursor.trim();
            }
            if (cursor != null && (cursor.isEmpty() || "null".equalsIgnoreCase(cursor))) {
                cursor = null;
            }
        } while (cursor != null);
        return combined;
    }

    private JSONObject getJsonObject(String path, String projectId, String cursor) throws Exception {
        String normalizedProjectId = sanitizeInlineValue(projectId);
        HttpUrl baseUrl = HttpUrl.parse(BASE_URL + path);
        if (baseUrl == null) {
            throw new IOException("Todoist URL could not be parsed");
        }
        HttpUrl.Builder urlBuilder = baseUrl.newBuilder()
                .addQueryParameter("project_id", normalizedProjectId);
        if (cursor != null && !cursor.isEmpty()) {
            urlBuilder.addQueryParameter("cursor", cursor);
        }
        HttpUrl url = urlBuilder.build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .get()
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String body = response.body() != null ? response.body().string() : "";
                String suffix = body.isEmpty() ? "" : ": " + body;
                throw new IOException("Todoist request failed with HTTP " + response.code() + suffix);
            }
            return new JSONObject(response.body().string());
        }
    }

    private JSONObject postJsonObject(String path, JSONObject payload) throws Exception {
        HttpUrl url = HttpUrl.parse(BASE_URL + path);
        if (url == null) {
            throw new IOException("Todoist URL could not be parsed");
        }

        RequestBody body = RequestBody.create(
                payload == null ? "{}" : payload.toString(),
                JSON
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String suffix = responseBody.isEmpty() ? "" : ": " + responseBody;
                throw new IOException("Todoist request failed with HTTP " + response.code() + suffix);
            }
            if (responseBody.isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(responseBody);
        }
    }

    private String sanitizeInlineValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\r", "").replace("\n", "").trim();
        if (normalized.regionMatches(true, 0, "Bearer ", 0, 7)) {
            normalized = normalized.substring(7).trim();
        }
        return normalized;
    }
}
