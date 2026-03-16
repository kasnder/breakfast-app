package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Uses OpenAI's speech endpoint to create an MP3 and plays it locally.
 */
public class OpenAiBriefingSpeaker {
    private static final String TAG = "OpenAiBriefingSpeaker";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface Listener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onUnavailable(String message);
    }

    private final Context context;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    private MediaPlayer mediaPlayer;
    private volatile boolean isPlaying;
    private volatile boolean isLoading;

    public OpenAiBriefingSpeaker(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isLoading() {
        return isLoading;
    }

    public boolean toggle(String script, AppConfig config) {
        if (isPlaying || isLoading) {
            stop();
            return true;
        }

        if (!config.isLlmConfigured()) {
            notifyUnavailable("OpenAI TTS needs a base URL and API key.");
            return false;
        }

        if (script.isEmpty()) {
            return false;
        }

        isLoading = true;
        executor.execute(() -> generateAndPlay(script, config));
        return true;
    }

    public void stop() {
        isLoading = false;
        releasePlayer();
        setPlaying(false);
    }

    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    private void generateAndPlay(String script, AppConfig config) {
        File outputFile = new File(context.getCacheDir(), "briefing-openai-tts.mp3");
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", "gpt-4o-mini-tts");
            requestBody.put("voice", "alloy");
            requestBody.put("format", "mp3");
            requestBody.put("input", script);
            requestBody.put("instructions", "Read this like a calm, concise morning briefing.");

            String baseUrl = config.getLlmBaseUrl();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            Request request = new Request.Builder()
                    .url(baseUrl + "/audio/speech")
                    .addHeader("Authorization", "Bearer " + config.getLlmApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    String message = "OpenAI TTS request failed (" + response.code() + ").";
                    Log.e(TAG, message);
                    postUnavailable(message);
                    return;
                }

                try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                    outputStream.write(response.body().bytes());
                }
            }

            mainHandler.post(() -> startPlayback(outputFile));
        } catch (Exception e) {
            Log.e(TAG, "OpenAI TTS playback failed", e);
            postUnavailable("OpenAI voice could not be loaded.");
        }
    }

    private void startPlayback(File outputFile) {
        isLoading = false;
        releasePlayer();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(outputFile.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(player -> {
                player.start();
                setPlaying(true);
            });
            mediaPlayer.setOnCompletionListener(player -> {
                releasePlayer();
                setPlaying(false);
            });
            mediaPlayer.setOnErrorListener((player, what, extra) -> {
                releasePlayer();
                setPlaying(false);
                notifyUnavailable("OpenAI voice playback failed.");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Unable to start OpenAI TTS playback", e);
            releasePlayer();
            setPlaying(false);
            notifyUnavailable("OpenAI voice playback failed.");
        }
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void setPlaying(boolean playing) {
        isPlaying = playing;
        if (listener != null) {
            listener.onPlaybackStateChanged(playing);
        }
    }

    private void postUnavailable(String message) {
        isLoading = false;
        setPlaying(false);
        notifyUnavailable(message);
    }

    private void notifyUnavailable(String message) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onUnavailable(message);
            }
        });
    }
}
