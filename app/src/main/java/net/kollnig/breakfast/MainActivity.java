package net.kollnig.breakfast;

import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import net.kollnig.distractionlib.FrictionGateActivity;
import net.kollnig.breakfast.main.AccessibilityServiceState;
import net.kollnig.breakfast.main.MainDashboardViews;
import net.kollnig.breakfast.main.MainWindowStyler;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private AppConfig config;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CalendarRepository calendarRepository = new CalendarRepository();
    private MainDashboardViews dashboardViews;

    // Dashboard modules
    private SocialDashboardModule socialModule;
    private NewsDashboardModule newsModule;
    private WeatherDashboardModule weatherModule;
    private EmailDashboardModule emailModule;
    private CalendarDashboardModule calendarModule;
    private TodoistDashboardModule todoistModule;

    private final ActivityResultLauncher<Intent> frictionGateLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (socialModule != null) {
                    socialModule.onFrictionGateResult(result.getResultCode());
                }
            });
    private final ActivityResultLauncher<String> calendarPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
                    onCalendarPermissionUpdated());
    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    this::onAudioPermissionUpdated);
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                    });
    private final ActivityResultLauncher<Intent> speechRecognitionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (todoistModule == null) {
                    return;
                }
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    todoistModule.onSpeechRecognitionResult("");
                    return;
                }
                ArrayList<String> matches = result.getData()
                        .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                String transcript = (matches == null || matches.isEmpty()) ? "" : matches.get(0);
                todoistModule.onSpeechRecognitionResult(transcript);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        MainWindowStyler.applyNavigationBarColor(this);
        ensureNotificationPermission();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setTitle(R.string.app_name);

        config = new AppConfig(this);
        dashboardViews = new MainDashboardViews(this);

        // Social module
        socialModule = new SocialDashboardModule(
                this,
                dashboardViews.getCardSocial(),
                config,
                intent -> frictionGateLauncher.launch(intent),
                () -> startActivity(new Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                this::isAccessibilityServiceEnabled);

        // News module
        newsModule = new NewsDashboardModule(
                this,
                dashboardViews.getCardNews(),
                dashboardViews.getCardHeadlines(),
                config,
                executor,
                mainHandler::post,
                this::formatRelativeTime,
                this::invalidateOptionsMenu,
                new NewsDashboardModule.BriefingDataProvider() {
                    @Override
                    public String getCalendarSummary() {
                        return dashboardViews.getCardCalendar().getVisibility() == View.VISIBLE
                                ? getTextValue(calendarModule.getSummaryText())
                                : "";
                    }

                    @Override
                    public String getEmailSummary() {
                        return dashboardViews.getCardEmail().getVisibility() == View.VISIBLE
                                ? getTextValue(emailModule.getSummaryText())
                                : "";
                    }

                    @Override
                    public String getSocialSummary() {
                        return dashboardViews.getCardSocial().getVisibility() == View.VISIBLE
                                ? socialModule.buildSocialSummary()
                                : "";
                    }

                    @Override
                    public WeatherData getCachedWeather() {
                        return config.getCachedWeather();
                    }
                });

        // Weather module
        weatherModule = new WeatherDashboardModule(
                dashboardViews.getCardWeather(),
                config,
                executor,
                mainHandler,
                this::formatRelativeTime);

        // Email module
        emailModule = new EmailDashboardModule(dashboardViews.getCardEmail());

        // Calendar module
        calendarModule = new CalendarDashboardModule(
                this,
                dashboardViews.getCardCalendar(),
                config,
                calendarRepository,
                executor,
                mainHandler::post,
                () -> calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR),
                () -> startActivity(new Intent(this, SettingsActivity.class)),
                this::openCalendarApp);

        // Todoist module
        todoistModule = new TodoistDashboardModule(
                this,
                dashboardViews.getCardTodoist(),
                config,
                executor,
                mainHandler::post,
                this::formatRelativeTime,
                () -> startActivity(new Intent(this, SettingsActivity.class)),
                newsModule::openArticleInCustomTab,
                this::invalidateOptionsMenu,
                () -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO),
                intent -> speechRecognitionLauncher.launch(intent));

        findViewById(R.id.btn_dismiss_welcome).setOnClickListener(v -> {
            config.setWelcomeDismissed(true);
            dashboardViews.getCardWelcome().setVisibility(View.GONE);
        });

        MainWindowStyler.applySystemBarPadding(findViewById(R.id.main));
        DashboardScheduler.scheduleMorningRefresh(this);
        newsModule.updateBriefingAvailability();
    }

    @Override
    protected void onResume() {
        super.onResume();

        socialModule.checkAccessibilityOnResume();

        applyModuleVisibility();
        emailModule.render();
        calendarModule.refreshOverview();
        todoistModule.refreshUi();
        weatherModule.refreshUi();
        socialModule.refresh();

        // Fetch fresh weather data
        if (config.isWeatherModuleEnabled()) {
            weatherModule.refreshData();
        }
        if (config.isTodoistModuleEnabled()) {
            todoistModule.refreshData();
        }

        newsModule.loadCachedHeadlines();
        newsModule.loadCachedArticles();
    }

    @Override
    protected void onPause() {
        super.onPause();
        socialModule.onPause();
        if (todoistModule != null) {
            todoistModule.stopVoiceCaptureOnPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        newsModule.shutdown();
        if (todoistModule != null) {
            todoistModule.release();
        }
        executor.shutdown();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        MenuItem refreshItem = menu.findItem(R.id.action_refresh);
        if (refreshItem != null) {
            refreshItem.setVisible(config.isRefreshButtonEnabled());
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh) {
            runFullRefresh(true);
            return true;
        }
        if (item.getItemId() == R.id.action_play_briefing) {
            newsModule.toggleBriefingPlayback();
            return true;
        }
        if (item.getItemId() == R.id.action_todoist_voice) {
            todoistModule.toggleVoiceCapture();
            return true;
        }
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem playItem = menu.findItem(R.id.action_play_briefing);
        if (playItem != null) {
            boolean visible = config.isNewsModuleEnabled();
            boolean enabled = !newsModule.getCurrentBriefingArticles().isEmpty();
            boolean busy = newsModule.isBriefingBusy();
            playItem.setVisible(visible);
            playItem.setEnabled(enabled);
            playItem.setIcon(busy
                    ? R.drawable.ic_refresh
                    : (newsModule.isAnyBriefingPlaying()
                    ? R.drawable.ic_pause_24
                    : R.drawable.ic_play_arrow_24));
            CharSequence title = getString(busy
                    ? R.string.briefing_audio_working
                    : (newsModule.isAnyBriefingPlaying()
                    ? R.string.stop_briefing
                    : R.string.play_briefing));
            playItem.setTitle(title);
        }
        MenuItem voiceItem = menu.findItem(R.id.action_todoist_voice);
        if (voiceItem != null) {
            boolean visible = config.isTodoistModuleEnabled() && config.isTodoistConfigured();
            voiceItem.setVisible(visible);
            voiceItem.setEnabled(visible);
            boolean recording = todoistModule != null && todoistModule.isVoiceRecording();
            voiceItem.setIcon(recording
                    ? R.drawable.ic_stop_24
                    : R.drawable.ic_mic_24);
            voiceItem.setTitle(getString(recording
                    ? R.string.todoist_voice_stop
                    : R.string.todoist_voice));
        }
        return super.onPrepareOptionsMenu(menu);
    }

    // ==================== ACCESSIBILITY CHECK ====================

    private boolean isAccessibilityServiceEnabled() {
        return AccessibilityServiceState.isEnabled(this, DistractionControlService.class);
    }

    private void onCalendarPermissionUpdated() {
        if (calendarModule != null) {
            calendarModule.refreshOverview();
        }
    }

    private void onAudioPermissionUpdated(boolean granted) {
        if (todoistModule != null) {
            todoistModule.onAudioPermissionResult(granted);
        }
    }

    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void applyModuleVisibility() {
        dashboardViews.applyModuleVisibility(config, this::invalidateOptionsMenu);
    }

    private void openCalendarApp() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("content://com.android.calendar/time/" + System.currentTimeMillis()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private void runFullRefresh(boolean clearArticleCacheFirst) {
        if (config.isWeatherModuleEnabled()) {
            weatherModule.refreshData();
        }
        if (config.isTodoistModuleEnabled()) {
            todoistModule.refreshData();
        }
        if (config.isNewsModuleEnabled()) {
            if (clearArticleCacheFirst) {
                config.clearArticleCache();
                config.clearHeadlineCache();
            }
            newsModule.loadHeadlines();
            newsModule.loadNews();
        }
    }

    private String formatRelativeTime(long timestamp) {
        long deltaMinutes = Math.max(0L, (System.currentTimeMillis() - timestamp) / 60000L);
        if (deltaMinutes < 1) {
            return "just now";
        }
        if (deltaMinutes < 60) {
            return deltaMinutes + " min ago";
        }
        long hours = deltaMinutes / 60L;
        if (hours < 24) {
            return hours + "h ago";
        }
        long days = hours / 24L;
        return days + "d ago";
    }

    private String getTextValue(CharSequence text) {
        return text == null ? "" : text.toString().trim();
    }
}
