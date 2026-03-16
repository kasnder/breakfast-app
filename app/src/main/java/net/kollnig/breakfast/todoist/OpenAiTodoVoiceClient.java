package net.kollnig.breakfast.todoist;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OpenAiTodoVoiceClient {
    private static final MediaType AUDIO_M4A = MediaType.get("audio/mp4");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiTodoVoiceClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = TextUtils.isEmpty(model) ? "gpt-4o-mini" : model.trim();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public VoiceTodoCommand transcribeAndInterpret(File audioFile, List<TodoistTask> tasks) throws Exception {
        String transcript = transcribeAudio(audioFile);
        return interpretTranscript(transcript, tasks);
    }

    private String transcribeAudio(File audioFile) throws Exception {
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "gpt-4o-mini-transcribe")
                .addFormDataPart(
                        "file",
                        audioFile.getName(),
                        RequestBody.create(audioFile, AUDIO_M4A)
                )
                .build();

        Request request = new Request.Builder()
                .url(baseUrl + "/audio/transcriptions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Transcription failed with HTTP " + response.code());
            }
            JSONObject json = new JSONObject(response.body().string());
            return json.optString("text", "").trim();
        }
    }

    private VoiceTodoCommand interpretTranscript(String transcript, List<TodoistTask> tasks) throws Exception {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("temperature", 0.1);
        requestBody.put("response_format", new JSONObject().put("type", "json_object"));

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content",
                        "You convert spoken todo commands into JSON. " +
                        "Return only JSON with keys: action, title, task_id, transcript. " +
                        "action must be one of add, complete, none. " +
                        "Use task_id only when matching an existing todo to complete. " +
                        "For add, put the spoken todo text into title in a cleaned-up form. " +
                        "If the intent is unclear, return action none."));

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Transcript: ").append(transcript).append("\n\n");
        userPrompt.append("Open tasks:\n");
        for (TodoistTask task : tasks) {
            userPrompt.append("- id=").append(task.id)
                    .append(", title=").append(task.content)
                    .append("\n");
        }
        messages.put(new JSONObject()
                .put("role", "user")
                .put("content", userPrompt.toString()));

        requestBody.put("messages", messages);

        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), JSON))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Voice command interpretation failed with HTTP " + response.code());
            }
            JSONObject json = new JSONObject(response.body().string());
            String content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            JSONObject commandJson = new JSONObject(stripMarkdownCodeFences(content));
            VoiceTodoCommand command = new VoiceTodoCommand();
            command.action = commandJson.optString("action", "none");
            command.title = commandJson.optString("title", "");
            command.taskId = commandJson.optString("task_id", "");
            command.transcript = commandJson.optString("transcript", transcript);
            return command;
        }
    }

    private String stripMarkdownCodeFences(String text) {
        if (text == null) {
            return "";
        }
        String content = text.trim();
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

    public static class VoiceTodoCommand {
        public String action;
        public String title;
        public String taskId;
        public String transcript;
    }
}
