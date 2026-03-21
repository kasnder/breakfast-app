package net.kollnig.breakfast.todoist;

import android.content.Context;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import net.kollnig.breakfast.AppConfig;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import org.json.JSONObject;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class OnDeviceTodoVoiceClient {
    private static final String TAG = "OnDeviceTodoVoice";
    private static final int JSON_RETRY_COUNT = 3;
    private static final int SAMPLER_TOP_K = 40;
    private static final double SAMPLER_TOP_P = 0.95;
    private static final double INITIAL_TEMPERATURE = 0.1;
    private static final double RETRY_TEMPERATURE = 0.0;
    private static final int SAMPLER_SEED = 0;

    private final Context context;
    private final String modelPath;
    private final String acceleratorType;

    public OnDeviceTodoVoiceClient(Context context, String modelPath, String acceleratorType) {
        this.context = context.getApplicationContext();
        this.modelPath = modelPath;
        this.acceleratorType = acceleratorType;
    }

    public OpenAiTodoVoiceClient.VoiceTodoCommand interpretAudio(
            File audioFile,
            List<TodoistTask> tasks
    ) throws Exception {
        Engine engine = null;
        try {
            EngineConfig config = new EngineConfig(
                    modelPath,
                    buildTextBackend(),
                    null,
                    new Backend.CPU(), // Audio processing uses CPU; GPU audio backend is not yet supported
                    null,
                    context.getCacheDir().getPath()
            );
            engine = new Engine(config);
            engine.initialize();

            String previousInvalidJson = null;
            for (int attempt = 1; attempt <= JSON_RETRY_COUNT; attempt++) {
                SamplerConfig samplerConfig = new SamplerConfig(
                        SAMPLER_TOP_K,
                        SAMPLER_TOP_P,
                        attempt == 1 ? INITIAL_TEMPERATURE : RETRY_TEMPERATURE,
                        SAMPLER_SEED
                );
                ConversationConfig conversationConfig = new ConversationConfig(
                        Contents.Companion.of(
                                "You transcribe audio of spoken todo commands and convert them into JSON. " +
                                        "Return only JSON with keys: action, title, task_id, transcript. " +
                                        "action must be one of add, complete, none. " +
                                        "Use task_id only when matching an existing todo to complete. " +
                                        "For add, put the spoken todo text into title in a cleaned-up form. " +
                                        "If the intent is unclear, return action none."
                        ),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        samplerConfig,
                        null,
                        false
                );

                StringBuilder taskList = new StringBuilder();
                taskList.append("Open tasks:\n");
                for (TodoistTask task : tasks) {
                    taskList.append("- id=").append(task.id)
                            .append(", title=").append(task.content)
                            .append("\n");
                }
                if (previousInvalidJson != null) {
                    taskList.append("\nPrevious output was invalid JSON. ");
                    taskList.append("Retry and return valid JSON only. Invalid output:\n");
                    taskList.append(previousInvalidJson);
                }

                try (Conversation conversation = engine.createConversation(conversationConfig)) {
                    Message response = conversation.sendMessage(
                            Contents.Companion.of(
                                    new Content.AudioFile(audioFile.getAbsolutePath()),
                                    new Content.Text(taskList.toString())
                            ),
                            Collections.emptyMap()
                    );
                    String cleaned = stripMarkdownCodeFences(response.toString());
                    try {
                        JSONObject commandJson = new JSONObject(cleaned);
                        OpenAiTodoVoiceClient.VoiceTodoCommand command = new OpenAiTodoVoiceClient.VoiceTodoCommand();
                        command.action = commandJson.optString("action", "none");
                        command.title = commandJson.optString("title", "");
                        command.taskId = commandJson.optString("task_id", "");
                        command.transcript = commandJson.optString("transcript", "");
                        return command;
                    } catch (Exception parseError) {
                        previousInvalidJson = cleaned;
                        if (attempt == JSON_RETRY_COUNT) {
                            throw parseError;
                        }
                    }
                }
            }
            throw new IllegalStateException("On-device audio todo command parsing failed");
        } catch (Exception e) {
            Log.e(TAG, "On-device audio todo command parsing failed", e);
            throw e;
        } finally {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception closeError) {
                    Log.w(TAG, "Error closing on-device audio voice engine", closeError);
                }
            }
        }
    }

    public OpenAiTodoVoiceClient.VoiceTodoCommand interpretTranscript(
            String transcript,
            List<TodoistTask> tasks
    ) throws Exception {
        Engine engine = null;
        try {
            EngineConfig config = new EngineConfig(
                    modelPath,
                    buildTextBackend(),
                    null,
                    null,
                    null,
                    context.getCacheDir().getPath()
            );
            engine = new Engine(config);
            engine.initialize();

            String previousInvalidJson = null;
            for (int attempt = 1; attempt <= JSON_RETRY_COUNT; attempt++) {
                SamplerConfig samplerConfig = new SamplerConfig(
                        SAMPLER_TOP_K,
                        SAMPLER_TOP_P,
                        attempt == 1 ? INITIAL_TEMPERATURE : RETRY_TEMPERATURE,
                        SAMPLER_SEED
                );
                ConversationConfig conversationConfig = new ConversationConfig(
                        Contents.Companion.of(
                                "You convert spoken todo commands into JSON. " +
                                        "Return only JSON with keys: action, title, task_id, transcript. " +
                                        "action must be one of add, complete, none. " +
                                        "Use task_id only when matching an existing todo to complete. " +
                                        "For add, put the spoken todo text into title in a cleaned-up form. " +
                                        "If the intent is unclear, return action none."
                        ),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        samplerConfig,
                        null,
                        false
                );

                StringBuilder userPrompt = new StringBuilder();
                userPrompt.append("Transcript: ").append(transcript).append("\n\n");
                userPrompt.append("Open tasks:\n");
                for (TodoistTask task : tasks) {
                    userPrompt.append("- id=").append(task.id)
                            .append(", title=").append(task.content)
                            .append("\n");
                }
                if (previousInvalidJson != null) {
                    userPrompt.append("\nPrevious output was invalid JSON. ");
                    userPrompt.append("Retry and return valid JSON only. Invalid output:\n");
                    userPrompt.append(previousInvalidJson);
                }

                try (Conversation conversation = engine.createConversation(conversationConfig)) {
                    Message response = conversation.sendMessage(userPrompt.toString(), Collections.emptyMap());
                    String cleaned = stripMarkdownCodeFences(response.toString());
                    try {
                        JSONObject commandJson = new JSONObject(cleaned);
                        OpenAiTodoVoiceClient.VoiceTodoCommand command = new OpenAiTodoVoiceClient.VoiceTodoCommand();
                        command.action = commandJson.optString("action", "none");
                        command.title = commandJson.optString("title", "");
                        command.taskId = commandJson.optString("task_id", "");
                        command.transcript = commandJson.optString("transcript", transcript);
                        return command;
                    } catch (Exception parseError) {
                        previousInvalidJson = cleaned;
                        if (attempt == JSON_RETRY_COUNT) {
                            throw parseError;
                        }
                    }
                }
            }
            throw new IllegalStateException("On-device todo command parsing failed");
        } catch (Exception e) {
            Log.e(TAG, "On-device todo command parsing failed", e);
            throw e;
        } finally {
            if (engine != null) {
                try {
                    engine.close();
                } catch (Exception closeError) {
                    Log.w(TAG, "Error closing on-device todo voice engine", closeError);
                }
            }
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

    private Backend buildTextBackend() {
        if (AppConfig.ACCELERATOR_NPU.equals(acceleratorType)) {
            return new Backend.NPU();
        } else if (AppConfig.ACCELERATOR_GPU.equals(acceleratorType)) {
            return new Backend.GPU();
        } else {
            return new Backend.CPU();
        }
    }
}
