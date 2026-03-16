package net.kollnig.breakfast.weather;

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

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches weather data from Open-Meteo (free, no API key required).
 * Uses the geocoding API to resolve city name to coordinates,
 * then the forecast API for current weather.
 */
public class WeatherClient {
    private static final String TAG = "WeatherClient";
    private static final int HOURLY_FORECAST_HOURS = 24;
    private static final int FORECAST_DAYS = 2;
    private static final String GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=1&language=en&format=json";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=%s&longitude=%s"
            + "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code"
            + "&hourly=precipitation_probability,precipitation"
            + "&daily=temperature_2m_min,temperature_2m_max"
            + "&forecast_days=" + FORECAST_DAYS
            + "&timezone=auto";

    private final OkHttpClient client;

    public WeatherClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Fetch weather for the given city name. Runs synchronously — call from a background thread.
     * Returns null on failure.
     */
    public WeatherData fetchWeather(String cityName) {
        try {
            // Step 1: Geocode the city name
            String geoUrl = String.format(GEOCODE_URL, java.net.URLEncoder.encode(cityName, "UTF-8"));
            Request geoRequest = new Request.Builder().url(geoUrl).build();
            Response geoResponse = client.newCall(geoRequest).execute();

            if (!geoResponse.isSuccessful() || geoResponse.body() == null) {
                Log.e(TAG, "Geocoding failed: " + geoResponse.code());
                return null;
            }

            JSONObject geoJson = new JSONObject(geoResponse.body().string());
            JSONArray results = geoJson.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.e(TAG, "City not found: " + cityName);
                return null;
            }

            JSONObject location = results.getJSONObject(0);
            double lat = location.getDouble("latitude");
            double lon = location.getDouble("longitude");
            String resolvedName = location.optString("name", cityName);

            // Step 2: Fetch current weather
            String weatherUrl = String.format(WEATHER_URL, lat, lon);
            Request weatherRequest = new Request.Builder().url(weatherUrl).build();
            Response weatherResponse = client.newCall(weatherRequest).execute();

            if (!weatherResponse.isSuccessful() || weatherResponse.body() == null) {
                Log.e(TAG, "Weather fetch failed: " + weatherResponse.code());
                return null;
            }

            JSONObject weatherJson = new JSONObject(weatherResponse.body().string());
            JSONObject current = weatherJson.getJSONObject("current");

            double temp = current.getDouble("temperature_2m");
            int humidity = current.getInt("relative_humidity_2m");
            double wind = current.getDouble("wind_speed_10m");
            int weatherCode = current.getInt("weather_code");
            String currentTime = current.optString("time", "");

            String description = weatherCodeToDescription(weatherCode);
            String icon = weatherCodeToEmoji(weatherCode);

            // Hourly rain forecast for the next 24 hours, even when the current day is almost over.
            String[] hourlyTime = null;
            int[] hourlyProb = null;
            double[] hourlyMm = null;

            JSONObject hourly = weatherJson.optJSONObject("hourly");
            if (hourly != null) {
                JSONArray timeArr = hourly.optJSONArray("time");
                JSONArray probArr = hourly.optJSONArray("precipitation_probability");
                JSONArray mmArr = hourly.optJSONArray("precipitation");

                if (timeArr != null && probArr != null && mmArr != null) {
                    int n = Math.min(timeArr.length(), Math.min(probArr.length(), mmArr.length()));

                    int startIdx = 0;
                    if (!currentTime.isEmpty()) {
                        // Prefer exact match; otherwise choose the first time >= current time.
                        // Open‑Meteo returns local ISO strings like "2026-03-16T14:00", which are lexicographically sortable.
                        for (int i = 0; i < n; i++) {
                            String t = timeArr.optString(i, "");
                            if (currentTime.equals(t)) {
                                startIdx = i;
                                break;
                            }
                        }
                        if (startIdx == 0) {
                            for (int i = 0; i < n; i++) {
                                String t = timeArr.optString(i, "");
                                if (!t.isEmpty() && t.compareTo(currentTime) >= 0) {
                                    startIdx = i;
                                    break;
                                }
                            }
                        }
                    }

                    int count = Math.min(HOURLY_FORECAST_HOURS, Math.max(0, n - startIdx));
                    hourlyTime = new String[count];
                    hourlyProb = new int[count];
                    hourlyMm = new double[count];

                    for (int i = 0; i < count; i++) {
                        int idx = startIdx + i;
                        hourlyTime[i] = timeArr.optString(idx, "");
                        hourlyProb[i] = probArr.optInt(idx, 0);
                        hourlyMm[i] = mmArr.optDouble(idx, 0.0);
                    }
                }
            }

            // Daily min/max temperatures for today (index 0)
            Double dailyMin = null;
            Double dailyMax = null;
            JSONObject daily = weatherJson.optJSONObject("daily");
            if (daily != null) {
                JSONArray minArr = daily.optJSONArray("temperature_2m_min");
                JSONArray maxArr = daily.optJSONArray("temperature_2m_max");
                if (minArr != null && minArr.length() > 0) {
                    dailyMin = minArr.optDouble(0);
                }
                if (maxArr != null && maxArr.length() > 0) {
                    dailyMax = maxArr.optDouble(0);
                }
            }

            return new WeatherData(resolvedName, temp, description, icon, humidity, wind,
                    hourlyTime, hourlyProb, hourlyMm, dailyMin, dailyMax);

        } catch (Exception e) {
            Log.e(TAG, "Error fetching weather", e);
            return null;
        }
    }

    private String weatherCodeToDescription(int code) {
        if (code == 0) return "Clear sky";
        if (code <= 3) return "Partly cloudy";
        if (code <= 48) return "Foggy";
        if (code <= 57) return "Drizzle";
        if (code <= 67) return "Rain";
        if (code <= 77) return "Snow";
        if (code <= 82) return "Rain showers";
        if (code <= 86) return "Snow showers";
        if (code <= 99) return "Thunderstorm";
        return "Unknown";
    }

    private String weatherCodeToEmoji(int code) {
        if (code == 0) return "sun";
        if (code <= 3) return "cloud_sun";
        if (code <= 48) return "fog";
        if (code <= 57) return "drizzle";
        if (code <= 67) return "rain";
        if (code <= 77) return "snow";
        if (code <= 82) return "rain_heavy";
        if (code <= 86) return "snow_heavy";
        if (code <= 99) return "thunder";
        return "unknown";
    }
}
