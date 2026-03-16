package net.kollnig.breakfast.weather;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

/**
 * Simple data class for cached weather information.
 */
public class WeatherData {
    public String cityName;
    public double temperature;
    public String description;
    public String iconCode;
    public int humidity;
    public double windSpeed;
    // Daily min/max temperatures (nullable; when missing, skip in UI)
    public Double dailyMinTemp;
    public Double dailyMaxTemp;
    /**
     * Hourly forecast arrays aligned by index.
     * Times are local ISO strings like "2026-03-16T14:00" (from Open‑Meteo).
     */
    public String[] hourlyTime;
    public int[] hourlyPrecipitationProbability;
    public double[] hourlyPrecipitationMm;
    public long fetchTime;

    public WeatherData() {}

    public WeatherData(String cityName, double temperature, String description,
                       String iconCode, int humidity, double windSpeed) {
        this.cityName = cityName;
        this.temperature = temperature;
        this.description = description;
        this.iconCode = iconCode;
        this.humidity = humidity;
        this.windSpeed = windSpeed;
        this.fetchTime = System.currentTimeMillis();
    }

    public WeatherData(String cityName, double temperature, String description,
                       String iconCode, int humidity, double windSpeed,
                       String[] hourlyTime, int[] hourlyPrecipitationProbability, double[] hourlyPrecipitationMm) {
        this(cityName, temperature, description, iconCode, humidity, windSpeed);
        this.hourlyTime = hourlyTime;
        this.hourlyPrecipitationProbability = hourlyPrecipitationProbability;
        this.hourlyPrecipitationMm = hourlyPrecipitationMm;
    }

    public WeatherData(String cityName, double temperature, String description,
                       String iconCode, int humidity, double windSpeed,
                       String[] hourlyTime, int[] hourlyPrecipitationProbability, double[] hourlyPrecipitationMm,
                       Double dailyMinTemp, Double dailyMaxTemp) {
        this(cityName, temperature, description, iconCode, humidity, windSpeed,
                hourlyTime, hourlyPrecipitationProbability, hourlyPrecipitationMm);
        this.dailyMinTemp = dailyMinTemp;
        this.dailyMaxTemp = dailyMaxTemp;
    }
}
