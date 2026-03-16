package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;

/**
 * Encapsulates on-device speech playback for the morning briefing.
 */
public class MorningBriefingSpeaker {
    private static final String UTTERANCE_ID = "breakfast_morning_briefing";

    public interface Listener {
        void onPlaybackStateChanged(boolean isPlaying);
        void onUnavailable();
    }

    private TextToSpeech textToSpeech;
    private final Listener listener;
    private boolean isReady;
    private boolean isPlaying;

    public MorningBriefingSpeaker(Context context, Listener listener) {
        this.listener = listener;
        textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                notifyUnavailable();
                return;
            }

            int languageStatus = textToSpeech.setLanguage(Locale.getDefault());
            if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                    || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                notifyUnavailable();
                return;
            }

            textToSpeech.setSpeechRate(0.96f);
            textToSpeech.setPitch(1.0f);
            textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    isPlaying = true;
                    notifyPlaybackChanged(true);
                }

                @Override
                public void onDone(String utteranceId) {
                    isPlaying = false;
                    notifyPlaybackChanged(false);
                }

                @Override
                public void onError(String utteranceId) {
                    isPlaying = false;
                    notifyPlaybackChanged(false);
                    notifyUnavailable();
                }
            });
            isReady = true;
        });
    }

    public boolean isReady() {
        return isReady;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean toggle(String script) {
        if (isPlaying) {
            stop();
            return true;
        }

        if (!isReady) {
            notifyUnavailable();
            return false;
        }

        if (script.isEmpty()) {
            return false;
        }

        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID);
        int result = textToSpeech.speak(script, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID);
        if (result != TextToSpeech.SUCCESS) {
            notifyUnavailable();
            return false;
        }
        return true;
    }

    public void stop() {
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
        isPlaying = false;
        notifyPlaybackChanged(false);
    }

    public void shutdown() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        isPlaying = false;
        isReady = false;
    }

    public static String buildScript(WeatherData weatherData,
                                     List<ArticleData> headlineArticles,
                                     List<ArticleData> briefingArticles,
                                     String calendarSummary,
                                     String notesSummary,
                                     String socialSummary) {
        StringBuilder script = new StringBuilder("Here is your morning briefing. ");

        if (weatherData != null) {
            script.append("Weather first. In ")
                    .append(safeText(weatherData.cityName))
                    .append(", it is ")
                    .append(Math.round(weatherData.temperature))
                    .append(" degrees and ")
                    .append(safeText(weatherData.description))
                    .append(". ");
            if (weatherData.dailyMinTemp != null && weatherData.dailyMaxTemp != null) {
                script.append("Expect a low of ")
                        .append(Math.round(weatherData.dailyMinTemp))
                        .append(" and a high of ")
                        .append(Math.round(weatherData.dailyMaxTemp))
                        .append(". ");
            }
            script.append("Humidity is ")
                    .append(weatherData.humidity)
                    .append(" percent, with wind around ")
                    .append(Math.round(weatherData.windSpeed))
                    .append(" kilometers per hour. ");
            String rainSummary = buildRainSummary(weatherData);
            if (!rainSummary.isEmpty()) {
                script.append(rainSummary).append(" ");
            }
        }

        if (headlineArticles != null && !headlineArticles.isEmpty()) {
            script.append("Latest from your feeds. ");
            int maxStories = Math.min(4, headlineArticles.size());
            for (int i = 0; i < maxStories; i++) {
                ArticleData article = headlineArticles.get(i);
                script.append("Headline ")
                        .append(i + 1)
                        .append(". ")
                        .append(safeText(article.title))
                        .append(". ");
                if (article.originalDescription != null && !article.originalDescription.trim().isEmpty()) {
                    script.append(cleanForSpeech(article.originalDescription)).append(" ");
                }
            }
        }

        if (briefingArticles != null && !briefingArticles.isEmpty()) {
            script.append("AI briefing. ");
            int maxStories = Math.min(4, briefingArticles.size());
            for (int i = 0; i < maxStories; i++) {
                ArticleData article = briefingArticles.get(i);
                script.append("Story ")
                        .append(i + 1)
                        .append(". ")
                        .append(safeText(article.title))
                        .append(". ");

                String summary = article.llmSummary;
                if (summary == null || summary.trim().isEmpty()) {
                    summary = article.originalDescription;
                }
                if (summary != null && !summary.trim().isEmpty()) {
                    script.append(cleanForSpeech(summary)).append(" ");
                }
            }
        }

        if (calendarSummary != null && !calendarSummary.trim().isEmpty()) {
            script.append("Calendar. ").append(cleanForSpeech(calendarSummary)).append(" ");
        }

        if (notesSummary != null && !notesSummary.trim().isEmpty()
                && !"Email integration is not implemented yet.".equals(notesSummary.trim())) {
            script.append("Notes. ").append(cleanForSpeech(notesSummary)).append(" ");
        }

        if (socialSummary != null && !socialSummary.trim().isEmpty()) {
            script.append("Social apps. ").append(cleanForSpeech(socialSummary)).append(" ");
        }

        script.append("That is your Breakfast briefing.");
        return cleanForSpeech(script.toString()).trim();
    }

    private static String buildRainSummary(WeatherData weatherData) {
        if (weatherData.hourlyTime == null
                || weatherData.hourlyPrecipitationMm == null
                || weatherData.hourlyPrecipitationProbability == null) {
            return "";
        }

        int count = Math.min(weatherData.hourlyTime.length,
                Math.min(weatherData.hourlyPrecipitationMm.length,
                        weatherData.hourlyPrecipitationProbability.length));
        double strongestMm = 0.0;
        String strongestTime = "";
        for (int i = 0; i < Math.min(12, count); i++) {
            if (weatherData.hourlyPrecipitationMm[i] > strongestMm) {
                strongestMm = weatherData.hourlyPrecipitationMm[i];
                strongestTime = formatHour(weatherData.hourlyTime[i]);
            }
        }
        if (strongestMm <= 0.0) {
            return "No meaningful rain is expected in the next few hours.";
        }
        return "The heaviest rain in the next few hours looks to be around "
                + strongestTime + ", at about "
                + String.format(Locale.getDefault(), "%.1f", strongestMm)
                + " millimeters.";
    }

    private static String formatHour(String isoLocalTime) {
        if (isoLocalTime == null) {
            return "";
        }
        int marker = isoLocalTime.indexOf('T');
        if (marker >= 0 && isoLocalTime.length() >= marker + 6) {
            return isoLocalTime.substring(marker + 1, marker + 6);
        }
        return isoLocalTime;
    }

    private static String safeText(String text) {
        return text == null ? "" : cleanForSpeech(text);
    }

    private static String cleanForSpeech(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\n', ' ')
                .replace("•", ". ")
                .replace("·", ". ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void notifyPlaybackChanged(boolean playing) {
        if (listener != null) {
            listener.onPlaybackStateChanged(playing);
        }
    }

    private void notifyUnavailable() {
        if (listener != null) {
            listener.onUnavailable();
        }
    }
}
