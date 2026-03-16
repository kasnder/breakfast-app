package net.kollnig.breakfast.weather;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

public class WeatherDashboardModule {
    public interface RelativeTimeFormatter {
        String formatRelativeTime(long timestamp);
    }

    private final View rootView;
    private final AppConfig config;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final RelativeTimeFormatter relativeTimeFormatter;
    private final TextView weatherCity;
    private final TextView weatherTemp;
    private final TextView weatherDescription;
    private final TextView weatherDetails;
    private final TextView weatherRainHeadline;
    private final LinearLayout weatherRainHourlyContainer;
    private final TextView weatherSetupHint;
    private final TextView weatherCacheStatus;
    private final TextView weatherError;

    public WeatherDashboardModule(View rootView, AppConfig config, ExecutorService executor,
                                  Handler mainHandler, RelativeTimeFormatter relativeTimeFormatter) {
        this.rootView = rootView;
        this.config = config;
        this.executor = executor;
        this.mainHandler = mainHandler;
        this.relativeTimeFormatter = relativeTimeFormatter;
        this.weatherCity = rootView.findViewById(R.id.weather_city);
        this.weatherTemp = rootView.findViewById(R.id.weather_temp);
        this.weatherDescription = rootView.findViewById(R.id.weather_description);
        this.weatherDetails = rootView.findViewById(R.id.weather_details);
        this.weatherRainHeadline = rootView.findViewById(R.id.weather_rain_headline);
        this.weatherRainHourlyContainer = rootView.findViewById(R.id.weather_rain_hourly_container);
        this.weatherSetupHint = rootView.findViewById(R.id.weather_setup_hint);
        this.weatherCacheStatus = rootView.findViewById(R.id.weather_cache_status);
        this.weatherError = rootView.findViewById(R.id.weather_error);
    }

    public void refreshUi() {
        String city = config.getCity();
        if (city.isEmpty()) {
            weatherCity.setText("Weather");
            weatherTemp.setText("--");
            weatherDescription.setText("No city set");
            weatherDetails.setText("");
            weatherRainHeadline.setText("");
            weatherRainHeadline.setVisibility(View.GONE);
            weatherRainHourlyContainer.removeAllViews();
            weatherSetupHint.setVisibility(View.VISIBLE);
            weatherCacheStatus.setVisibility(View.GONE);
            weatherError.setVisibility(View.GONE);
            return;
        }

        weatherSetupHint.setVisibility(View.GONE);
        WeatherData cached = config.getCachedWeather();
        if (cached != null) {
            showWeather(cached);
            showWeatherCacheStatus(cached.fetchTime, true);
        } else {
            weatherCity.setText(city);
            weatherTemp.setText("--");
            weatherDescription.setText("Loading...");
            weatherDetails.setText("");
            weatherCacheStatus.setVisibility(View.GONE);
        }
    }

    public void refreshData() {
        String city = config.getCity();
        if (city.isEmpty()) {
            return;
        }

        weatherError.setVisibility(View.GONE);

        executor.execute(() -> {
            WeatherClient client = new WeatherClient();
            WeatherData data = client.fetchWeather(city);
            mainHandler.post(() -> {
                if (data != null) {
                    config.setCachedWeather(data);
                    config.setDashboardLastRefresh(System.currentTimeMillis());
                    weatherError.setVisibility(View.GONE);
                    showWeather(data);
                    showWeatherCacheStatus(data.fetchTime, false);
                } else {
                    weatherError.setText("Unable to load weather data");
                    weatherError.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void showWeather(WeatherData data) {
        weatherCity.setText(data.cityName);
        weatherTemp.setText(String.format(Locale.getDefault(), "%.0f°", data.temperature));
        weatherDescription.setText(data.description);
        StringBuilder details = new StringBuilder();
        if (data.dailyMinTemp != null && data.dailyMaxTemp != null) {
            details.append(String.format(Locale.getDefault(),
                    "Min %.0f° / Max %.0f° · ",
                    data.dailyMinTemp, data.dailyMaxTemp));
        }
        details.append(String.format(Locale.getDefault(),
                "Humidity %d%% · Wind %.0f km/h", data.humidity, data.windSpeed));
        weatherDetails.setText(details.toString());

        weatherRainHeadline.setText("");
        weatherRainHeadline.setVisibility(View.GONE);
        renderHourlyRainChips(data);
        weatherSetupHint.setVisibility(View.GONE);
    }

    private void showWeatherCacheStatus(long timestamp, boolean cached) {
        if (timestamp <= 0) {
            weatherCacheStatus.setVisibility(View.GONE);
            return;
        }
        String prefix = cached ? "Showing cached weather" : "Updated";
        weatherCacheStatus.setText(prefix + " " + relativeTimeFormatter.formatRelativeTime(timestamp));
        weatherCacheStatus.setVisibility(View.VISIBLE);
    }

    private void renderHourlyRainChips(WeatherData data) {
        weatherRainHourlyContainer.removeAllViews();

        if (data == null || data.hourlyTime == null
                || data.hourlyPrecipitationProbability == null
                || data.hourlyPrecipitationMm == null) {
            return;
        }

        int n = Math.min(data.hourlyTime.length,
                Math.min(data.hourlyPrecipitationProbability.length, data.hourlyPrecipitationMm.length));
        if (n <= 0) {
            return;
        }

        int hours = Math.min(12, n);
        for (int i = 0; i < hours; i++) {
            String t = formatHourMinute(data.hourlyTime[i]);
            double mm = Math.max(0.0, data.hourlyPrecipitationMm[i]);

            View chipRoot = LayoutInflater.from(rootView.getContext())
                    .inflate(R.layout.item_weather_rain_chip, weatherRainHourlyContainer, false);
            TextView timeView = chipRoot.findViewById(R.id.rain_chip_time);
            FrameLayout barContainer = chipRoot.findViewById(R.id.rain_chip_bar_container);
            View barFill = chipRoot.findViewById(R.id.rain_chip_bar_fill);
            TextView detailsView = chipRoot.findViewById(R.id.rain_chip_amount);

            timeView.setText((t == null || t.isEmpty()) ? "--:--" : t);

            double cappedMm = Math.min(mm, 2.0);
            int fillHeight = (int) Math.round(barContainer.getLayoutParams().height * (cappedMm / 2.0));
            FrameLayout.LayoutParams fillLayoutParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    fillHeight
            );
            fillLayoutParams.gravity = android.view.Gravity.BOTTOM;
            barFill.setLayoutParams(fillLayoutParams);

            detailsView.setText(String.format(Locale.getDefault(), "%.1fmm", mm));
            weatherRainHourlyContainer.addView(chipRoot);
        }
    }

    private String formatHourMinute(String isoLocalTime) {
        if (isoLocalTime == null) {
            return "";
        }
        int tIdx = isoLocalTime.indexOf('T');
        if (tIdx < 0) {
            return "";
        }
        if (isoLocalTime.length() >= tIdx + 6) {
            return isoLocalTime.substring(tIdx + 1, tIdx + 6);
        }
        return "";
    }
}
