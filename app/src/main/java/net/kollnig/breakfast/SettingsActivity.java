package net.kollnig.breakfast;

import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textfield.TextInputEditText;

import net.kollnig.distractionlib.FrictionGateActivity;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Calendar;
import java.util.HashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public class SettingsActivity extends AppCompatActivity {
    private static final List<FeedPreset> FEED_PRESETS = buildFeedPresets();

    private AppConfig config;
    private final CalendarRepository calendarRepository = new CalendarRepository();

    private TextInputEditText inputCity;
    private TextInputEditText inputTimerDuration;
    private TextInputEditText inputFrictionWords;
    private TextInputEditText inputArticleCount;
    private TextInputEditText inputLlmUrl;
    private TextInputEditText inputLlmKey;
    private TextInputEditText inputLlmModel;
    private TextInputEditText inputInterests;
    private TextInputEditText inputTodoistApiKey;
    private TextInputEditText inputTodoistProjectId;
    private CheckBox checkboxSocialInstagram;
    private CheckBox checkboxSocialLinkedin;
    private CheckBox checkboxSocialPressHome;
    private MaterialButton btnMorningRefreshTime;
    private LinearLayout rssFeedsContainer;
    private LinearLayout calendarListContainer;
    private LinearLayout moduleOrderContainer;
    private MaterialButton btnUnlockSocial;
    private MaterialButton btnGrantCalendarAccess;
    private MaterialSwitch switchMorningDelivery;
    private MaterialSwitch switchEnableRefreshButton;
    private MaterialSwitch switchBriefingOpenAiTts;
    private TextView calendarPermissionStatus;
    private TextView socialModuleLockHint;
    private TextView morningRefreshTimeText;
    private TextView refreshButtonModeText;
    private TextView briefingOpenAiTtsText;
    private List<String> moduleOrder = new ArrayList<>();
    private final Map<String, MaterialSwitch> moduleSwitches = new HashMap<>();
    private MaterialSwitch switchOnDeviceLlm;
    private MaterialSwitch switchOnDeviceGpu;
    private MaterialSwitch switchOnDeviceBatching;
    private MaterialSwitch switchLlmBenchmark;
    private MaterialButton btnDownloadModel;
    private TextView textOnDeviceLlmStatus;
    private com.google.android.material.textfield.TextInputEditText inputHuggingfaceToken;
    private android.widget.RadioGroup radioModelVariant;
    private boolean socialSettingsUnlocked;
    private boolean updatingSocialModuleSwitch;

    private final ActivityResultLauncher<String> calendarPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                refreshCalendarSettings();
            });

    private final ActivityResultLauncher<Intent> frictionGateLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    socialSettingsUnlocked = true;
                    refreshSocialSettingsLock();
                }
            });

    private final ActivityResultLauncher<String> exportSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), this::exportSettingsToUri);

    private final ActivityResultLauncher<String[]> importSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::importSettingsFromUri);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        config = new AppConfig(this);

        setupNavigationBarColor();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        setupWindowInsets();
        initViews();
        loadSettings();
    }

    private void initViews() {
        inputCity = findViewById(R.id.input_city);
        inputTimerDuration = findViewById(R.id.input_timer_duration);
        inputFrictionWords = findViewById(R.id.input_friction_words);
        inputArticleCount = findViewById(R.id.input_article_count);
        inputLlmUrl = findViewById(R.id.input_llm_url);
        inputLlmKey = findViewById(R.id.input_llm_key);
        inputLlmModel = findViewById(R.id.input_llm_model);
        inputInterests = findViewById(R.id.input_interests);
        inputTodoistApiKey = findViewById(R.id.input_todoist_key);
        inputTodoistProjectId = findViewById(R.id.input_todoist_project_id);
        checkboxSocialInstagram = findViewById(R.id.checkbox_social_instagram);
        checkboxSocialLinkedin = findViewById(R.id.checkbox_social_linkedin);
        checkboxSocialPressHome = findViewById(R.id.checkbox_social_press_home);
        btnMorningRefreshTime = findViewById(R.id.btn_morning_refresh_time);
        rssFeedsContainer = findViewById(R.id.rss_feeds_container);
        calendarListContainer = findViewById(R.id.calendar_list_container);
        moduleOrderContainer = findViewById(R.id.module_order_container);
        btnUnlockSocial = findViewById(R.id.btn_unlock_social);
        btnGrantCalendarAccess = findViewById(R.id.btn_grant_calendar_access);
        switchMorningDelivery = findViewById(R.id.switch_morning_delivery);
        switchEnableRefreshButton = findViewById(R.id.switch_news_refresh_on_open);
        switchBriefingOpenAiTts = findViewById(R.id.switch_briefing_openai_tts);
        calendarPermissionStatus = findViewById(R.id.calendar_permission_status);
        socialModuleLockHint = findViewById(R.id.text_social_module_lock_hint);
        morningRefreshTimeText = findViewById(R.id.text_morning_refresh_time);
        refreshButtonModeText = findViewById(R.id.text_news_refresh_mode);
        briefingOpenAiTtsText = findViewById(R.id.text_briefing_openai_tts);
        switchOnDeviceLlm = findViewById(R.id.switch_on_device_llm);
        switchOnDeviceGpu = findViewById(R.id.switch_on_device_gpu);
        switchOnDeviceBatching = findViewById(R.id.switch_on_device_batching);
        switchLlmBenchmark = findViewById(R.id.switch_llm_benchmark);
        btnDownloadModel = findViewById(R.id.btn_download_model);
        textOnDeviceLlmStatus = findViewById(R.id.text_on_device_llm_status);
        inputHuggingfaceToken = findViewById(R.id.input_huggingface_token);
        radioModelVariant = findViewById(R.id.radio_model_variant);

        // Add feed button
        findViewById(R.id.btn_add_feed).setOnClickListener(v -> showAddFeedDialog());
        btnMorningRefreshTime.setOnClickListener(v -> showMorningRefreshTimeDialog());

        // Accessibility settings button
        findViewById(R.id.btn_accessibility).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        findViewById(R.id.btn_export_settings).setOnClickListener(v -> {
            saveAllSettings();
            exportSettingsLauncher.launch(buildSettingsExportFileName());
        });
        findViewById(R.id.btn_import_settings).setOnClickListener(v -> showImportSettingsConfirmation());

        btnGrantCalendarAccess.setOnClickListener(v ->
                calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR));

        // Unlock social settings via friction gate
        btnUnlockSocial.setOnClickListener(v -> launchSocialUnlock());

        // Auto-save on focus loss for text fields
        setupAutoSave(inputCity, () -> config.setCity(inputCity.getText().toString().trim()));
        setupAutoSave(inputTimerDuration, () -> {
            try { config.setTimerDurationMins(Integer.parseInt(inputTimerDuration.getText().toString().trim())); }
            catch (NumberFormatException ignored) {}
        });
        setupAutoSave(inputFrictionWords, () -> {
            try {
                config.setFrictionWordCount(Integer.parseInt(inputFrictionWords.getText().toString().trim()));
                refreshSocialSettingsLock();
            } catch (NumberFormatException ignored) {}
        });
        setupAutoSave(inputArticleCount, () -> {
            try { config.setArticleCount(Integer.parseInt(inputArticleCount.getText().toString().trim())); }
            catch (NumberFormatException ignored) {}
        });
        setupAutoSave(inputLlmUrl, () -> {
            config.setLlmBaseUrl(inputLlmUrl.getText().toString().trim());
            refreshFeedsList();
        });
        setupAutoSave(inputLlmKey, () -> {
            config.setLlmApiKey(inputLlmKey.getText().toString().trim());
            refreshFeedsList();
        });
        setupAutoSave(inputLlmModel, () -> config.setLlmModel(inputLlmModel.getText().toString().trim()));
        setupAutoSave(inputInterests, () -> config.setInterestProfile(inputInterests.getText().toString().trim()));
        setupAutoSave(inputTodoistApiKey, () -> {
            config.setTodoistApiKey(inputTodoistApiKey.getText().toString().trim());
            config.clearTodoistCache();
        });
        setupAutoSave(inputTodoistProjectId, () -> {
            config.setTodoistProjectId(inputTodoistProjectId.getText().toString().trim());
            config.clearTodoistCache();
        });
        checkboxSocialInstagram.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setInstagramSocialEnabled(isChecked);
            refreshSocialBlockingState();
        });
        checkboxSocialLinkedin.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setLinkedinSocialEnabled(isChecked);
            refreshSocialBlockingState();
        });
        checkboxSocialPressHome.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setPressHomeWhenSocialTimeIsUp(isChecked);
            refreshSocialBlockingState();
        });
        switchMorningDelivery.setOnCheckedChangeListener((buttonView, isChecked) ->
                config.setMorningNotificationEnabled(isChecked));
        switchEnableRefreshButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setRefreshButtonEnabled(isChecked);
            updateRefreshButtonSummary();
        });
        switchBriefingOpenAiTts.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setBriefingUseOpenAiTtsEnabled(isChecked);
            updateBriefingTtsSummary();
        });
        switchOnDeviceLlm.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setOnDeviceLlmEnabled(isChecked);
            updateOnDeviceModelStatus();
            refreshFeedsList();
        });
        switchOnDeviceGpu.setOnCheckedChangeListener((buttonView, isChecked) ->
                config.setOnDeviceUseGpu(isChecked));
        switchOnDeviceBatching.setOnCheckedChangeListener((buttonView, isChecked) ->
                config.setOnDeviceBatchingEnabled(isChecked));
        switchLlmBenchmark.setOnCheckedChangeListener((buttonView, isChecked) ->
                config.setLlmBenchmarkEnabled(isChecked));
        inputHuggingfaceToken.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                config.setHuggingFaceToken(s.toString());
            }
        });
        radioModelVariant.setOnCheckedChangeListener((group, checkedId) -> {
            String newVariant;
            if (checkedId == R.id.radio_gemma_e2b) {
                newVariant = AppConfig.MODEL_VARIANT_GEMMA_E2B;
            } else {
                newVariant = AppConfig.MODEL_VARIANT_GEMMA_1B;
            }
            handleModelVariantChange(newVariant);
        });
        btnDownloadModel.setOnClickListener(v -> startModelDownload());
    }

    private void loadSettings() {
        socialSettingsUnlocked = false;
        inputCity.setText(config.getCity());
        inputTimerDuration.setText(String.valueOf(config.getTimerDurationMins()));
        inputFrictionWords.setText(String.valueOf(config.getFrictionWordCount()));
        inputArticleCount.setText(String.valueOf(config.getArticleCount()));
        inputLlmUrl.setText(config.getLlmBaseUrl());
        inputLlmKey.setText(config.getLlmApiKey());
        inputLlmModel.setText(config.getLlmModel());
        inputInterests.setText(config.getInterestProfile());
        inputTodoistApiKey.setText(config.getTodoistApiKey());
        inputTodoistProjectId.setText(config.getTodoistProjectId());
        checkboxSocialInstagram.setChecked(config.isInstagramSocialEnabled());
        checkboxSocialLinkedin.setChecked(config.isLinkedinSocialEnabled());
        checkboxSocialPressHome.setChecked(config.shouldPressHomeWhenSocialTimeIsUp());
        switchMorningDelivery.setChecked(config.isMorningNotificationEnabled());
        switchEnableRefreshButton.setChecked(config.isRefreshButtonEnabled());
        switchBriefingOpenAiTts.setChecked(config.isBriefingUseOpenAiTtsEnabled());
        switchOnDeviceLlm.setChecked(config.isOnDeviceLlmEnabled());
        switchOnDeviceGpu.setChecked(config.isOnDeviceUseGpu());
        switchOnDeviceBatching.setChecked(config.isOnDeviceBatchingEnabled());
        switchLlmBenchmark.setChecked(config.isLlmBenchmarkEnabled());
        inputHuggingfaceToken.setText(config.getHuggingFaceToken());
        String variant = config.getOnDeviceModelVariant();
        if (AppConfig.MODEL_VARIANT_GEMMA_E2B.equals(variant)) {
            radioModelVariant.check(R.id.radio_gemma_e2b);
        } else {
            radioModelVariant.check(R.id.radio_gemma_1b);
        }
        updateOnDeviceModelStatus();
        moduleOrder = new ArrayList<>(config.getModuleOrder());
        updateMorningRefreshTimeText();
        updateRefreshButtonSummary();
        updateBriefingTtsSummary();
        refreshModuleOrderList();
        refreshFeedsList();
        refreshSocialSettingsLock();
        refreshCalendarSettings();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveAllSettings();
    }

    private void saveAllSettings() {
        config.setCity(inputCity.getText().toString().trim());
        try { config.setTimerDurationMins(Integer.parseInt(inputTimerDuration.getText().toString().trim())); }
        catch (NumberFormatException ignored) {}
        try { config.setFrictionWordCount(Integer.parseInt(inputFrictionWords.getText().toString().trim())); }
        catch (NumberFormatException ignored) {}
        try { config.setArticleCount(Integer.parseInt(inputArticleCount.getText().toString().trim())); }
        catch (NumberFormatException ignored) {}
        config.setLlmBaseUrl(inputLlmUrl.getText().toString().trim());
        config.setLlmApiKey(inputLlmKey.getText().toString().trim());
        config.setLlmModel(inputLlmModel.getText().toString().trim());
        config.setInterestProfile(inputInterests.getText().toString().trim());
        config.setTodoistApiKey(inputTodoistApiKey.getText().toString().trim());
        config.setTodoistProjectId(inputTodoistProjectId.getText().toString().trim());
        config.setInstagramSocialEnabled(checkboxSocialInstagram.isChecked());
        config.setLinkedinSocialEnabled(checkboxSocialLinkedin.isChecked());
        config.setPressHomeWhenSocialTimeIsUp(checkboxSocialPressHome.isChecked());
        config.setMorningNotificationEnabled(switchMorningDelivery.isChecked());
        config.setRefreshButtonEnabled(switchEnableRefreshButton.isChecked());
        config.setBriefingUseOpenAiTtsEnabled(switchBriefingOpenAiTts.isChecked());
        config.setModuleOrder(moduleOrder);
        DashboardScheduler.scheduleMorningRefresh(this);
    }

    private void refreshModuleOrderList() {
        moduleOrderContainer.removeAllViews();
        moduleSwitches.clear();
        for (int i = 0; i < moduleOrder.size(); i++) {
            moduleOrderContainer.addView(createModuleOrderRow(moduleOrder.get(i), i));
        }
        refreshSocialSettingsLock();
    }

    private View createModuleOrderRow(String moduleId, int index) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_module_order, moduleOrderContainer, false);
        MaterialSwitch moduleSwitch = row.findViewById(R.id.module_switch);
        moduleSwitch.setText(getModuleLabel(moduleId));
        moduleSwitch.setChecked(isModuleEnabled(moduleId));
        moduleSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> onModuleToggled(moduleId, isChecked));
        moduleSwitches.put(moduleId, moduleSwitch);

        MaterialButton moveUpButton = row.findViewById(R.id.button_move_up);
        moveUpButton.setEnabled(index > 0);
        moveUpButton.setOnClickListener(v -> moveModule(index, index - 1));

        MaterialButton moveDownButton = row.findViewById(R.id.button_move_down);
        moveDownButton.setEnabled(index < moduleOrder.size() - 1);
        moveDownButton.setOnClickListener(v -> moveModule(index, index + 1));
        return row;
    }

    private void moveModule(int fromIndex, int toIndex) {
        if (fromIndex < 0 || fromIndex >= moduleOrder.size()
                || toIndex < 0 || toIndex >= moduleOrder.size()
                || fromIndex == toIndex) {
            return;
        }
        Collections.swap(moduleOrder, fromIndex, toIndex);
        config.setModuleOrder(moduleOrder);
        refreshModuleOrderList();
    }

    private void onModuleToggled(String moduleId, boolean isChecked) {
        if (AppConfig.MODULE_SOCIAL.equals(moduleId) && updatingSocialModuleSwitch) {
            return;
        }
        setModuleEnabled(moduleId, isChecked);
        if (AppConfig.MODULE_SOCIAL.equals(moduleId)) {
            refreshSocialBlockingState();
            refreshSocialSettingsLock();
        }
    }

    private boolean isModuleEnabled(String moduleId) {
        DashboardModuleDefinition module = DashboardModuleRegistry.findById(moduleId);
        return module != null && module.isEnabled(config);
    }

    private void setModuleEnabled(String moduleId, boolean enabled) {
        DashboardModuleDefinition module = DashboardModuleRegistry.findById(moduleId);
        if (module != null) {
            module.setEnabled(config, enabled);
        }
    }

    private String getModuleLabel(String moduleId) {
        DashboardModuleDefinition module = DashboardModuleRegistry.findById(moduleId);
        return module != null ? getString(module.getTitleResId()) : moduleId;
    }

    // --- RSS / Atom Feeds Management ---

    private void refreshFeedsList() {
        rssFeedsContainer.removeAllViews();
        List<FeedConfig> feeds = config.getFeedConfigs();

        for (FeedConfig feed : feeds) {
            View feedView = createFeedRow(feed);
            rssFeedsContainer.addView(feedView);
        }
    }

    private void refreshCalendarSettings() {
        calendarListContainer.removeAllViews();

        if (!hasCalendarPermission()) {
            calendarPermissionStatus.setText("Calendar access is required to show today’s events.");
            btnGrantCalendarAccess.setVisibility(View.VISIBLE);
            return;
        }

        btnGrantCalendarAccess.setVisibility(View.GONE);
        List<CalendarInfo> calendars = calendarRepository.getVisibleCalendars(this);
        Set<Long> selectedIds = config.getSelectedCalendarIds();
        boolean hasSelection = config.hasExplicitCalendarSelection();

        if (calendars.isEmpty()) {
            calendarPermissionStatus.setText("No visible calendars were found. In Google Calendar, enable 'Share Google Calendar data with other apps' and make sure your calendars are visible.");
            return;
        }

        calendarPermissionStatus.setText("Selected calendars appear in the morning overview.");
        for (CalendarInfo calendar : calendars) {
            calendarListContainer.addView(createCalendarRow(calendar, calendars, selectedIds, hasSelection));
        }
    }

    private View createCalendarRow(CalendarInfo calendar, List<CalendarInfo> allCalendars,
                                   Set<Long> selectedIds, boolean hasSelection) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_calendar_setting, calendarListContainer, false);
        View colorDot = row.findViewById(R.id.calendar_color_dot);
        GradientDrawable dotBackground = (GradientDrawable) colorDot.getBackground().mutate();
        dotBackground.setColor(calendar.color);

        CheckBox checkBox = row.findViewById(R.id.calendar_checkbox);
        checkBox.setText(buildCalendarLabel(calendar));
        checkBox.setChecked(!hasSelection || selectedIds.contains(calendar.id));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Set<Long> updatedIds;
            if (config.hasExplicitCalendarSelection()) {
                updatedIds = new HashSet<>(config.getSelectedCalendarIds());
            } else {
                updatedIds = new HashSet<>();
                for (CalendarInfo item : allCalendars) {
                    updatedIds.add(item.id);
                }
            }
            if (isChecked) {
                updatedIds.add(calendar.id);
            } else {
                updatedIds.remove(calendar.id);
            }
            config.setSelectedCalendarIds(updatedIds);
            config.setHasExplicitCalendarSelection(true);
        });
        return row;
    }

    private String buildCalendarLabel(CalendarInfo calendar) {
        if (TextUtils.isEmpty(calendar.accountName)) {
            return calendar.displayName;
        }
        return calendar.displayName + " (" + calendar.accountName + ")";
    }

    private boolean hasCalendarPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    private View createFeedRow(FeedConfig feed) {
        boolean topStoriesAvailable = config.isTopStoriesAvailable();
        View row = LayoutInflater.from(this).inflate(R.layout.item_feed_setting, rssFeedsContainer, false);
        TextView urlText = row.findViewById(R.id.feed_url);
        urlText.setText(feed.url);

        CheckBox headlinesCheckbox = row.findViewById(R.id.feed_checkbox_headlines);
        headlinesCheckbox.setChecked(feed.includeInHeadlines);

        CheckBox topStoriesCheckbox = row.findViewById(R.id.feed_checkbox_briefing);
        topStoriesCheckbox.setChecked(feed.includeInTopStories);
        topStoriesCheckbox.setEnabled(topStoriesAvailable);
        if (!topStoriesAvailable) {
            topStoriesCheckbox.setAlpha(0.5f);
        }
        topStoriesCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.updateFeedConfig(new FeedConfig(
                    feed.url,
                    isChecked,
                    headlinesCheckbox.isChecked()
            ));
            refreshFeedsList();
        });

        headlinesCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.updateFeedConfig(new FeedConfig(
                    feed.url,
                    topStoriesCheckbox.isChecked(),
                    isChecked
            ));
            refreshFeedsList();
        });

        MaterialButton removeBtn = row.findViewById(R.id.feed_remove_button);
        removeBtn.setTextSize(12);
        removeBtn.setOnClickListener(v -> {
            config.removeRssFeed(feed.url);
            refreshFeedsList();
        });
        return row;
    }

    private void showAddFeedDialog() {
        List<String> items = new ArrayList<>();
        for (FeedPreset preset : FEED_PRESETS) {
            items.add(preset.label);
        }
        items.add("Custom RSS or Atom URL");

        new AlertDialog.Builder(this)
                .setTitle("Add Feed Source")
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which < FEED_PRESETS.size()) {
                        addFeed(FEED_PRESETS.get(which).url);
                    } else {
                        showCustomFeedDialog();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomFeedDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_text_input, null);
        TextInputLayout inputLayout = (TextInputLayout) dialogView;
        TextInputEditText input = dialogView.findViewById(R.id.dialog_text_input);
        inputLayout.setHint(getString(R.string.dialog_add_feed_hint));
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_add_feed_title)
                .setView(dialogView)
                .setPositiveButton(R.string.add, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) {
                        addFeed(url);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void addFeed(String url) {
        String normalizedUrl = url.trim();
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            normalizedUrl = "https://" + normalizedUrl;
        }
        boolean added = config.addRssFeed(normalizedUrl);
        refreshFeedsList();
        if (added) {
            DashboardScheduler.enqueueImmediateRefresh(this);
        }
    }

    private void showMorningRefreshTimeDialog() {
        int hour = config.getMorningRefreshHour();
        int minute = config.getMorningRefreshMinute();
        TimePickerDialog dialog = new TimePickerDialog(
                this,
                (view, selectedHour, selectedMinute) -> {
                    config.setMorningRefreshHour(selectedHour);
                    config.setMorningRefreshMinute(selectedMinute);
                    updateMorningRefreshTimeText();
                    DashboardScheduler.scheduleMorningRefresh(this);
                },
                hour,
                minute,
                android.text.format.DateFormat.is24HourFormat(this)
        );
        dialog.show();
    }

    private void updateMorningRefreshTimeText() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, config.getMorningRefreshHour());
        calendar.set(Calendar.MINUTE, config.getMorningRefreshMinute());
        String label = android.text.format.DateFormat.getTimeFormat(this).format(calendar.getTime());
        morningRefreshTimeText.setText("Scheduled refresh: " + label);
    }

    private void updateRefreshButtonSummary() {
        if (config.isRefreshButtonEnabled()) {
            refreshButtonModeText.setText("Show a refresh button in the toolbar so you can manually reload the dashboard whenever you want.");
        } else {
            refreshButtonModeText.setText("Hide the toolbar refresh button. Breakfast will still refresh on its normal schedule in the background.");
        }
    }

    private void updateBriefingTtsSummary() {
        if (config.isBriefingUseOpenAiTtsEnabled()) {
            briefingOpenAiTtsText.setText("Breakfast will try the OpenAI speech endpoint for the play button and fall back to Android voice if it fails.");
        } else {
            briefingOpenAiTtsText.setText("Breakfast reads the briefing with Android's on-device voice.");
        }
    }

    private void updateOnDeviceModelStatus() {
        String variant = config.getOnDeviceModelVariant();
        String modelPath = OnDeviceLlmClient.getModelPath(this, variant);

        if (new java.io.File(modelPath).exists()) {
            long sizeMb = new java.io.File(modelPath).length() / (1024 * 1024);
            textOnDeviceLlmStatus.setText(String.format(getString(R.string.on_device_model_ready), sizeMb + " MB"));
            btnDownloadModel.setText("Re-download model");
        } else {
            textOnDeviceLlmStatus.setText(R.string.on_device_model_not_downloaded);
            String label = AppConfig.MODEL_VARIANT_GEMMA_E2B.equals(variant) ? "Gemma 3n E2B (~800 MB)" : "Gemma 1B (~557 MB)";
            btnDownloadModel.setText("Download " + label);
        }
    }

    private void handleModelVariantChange(String newVariant) {
        String oldVariant = config.getOnDeviceModelVariant();
        if (newVariant.equals(oldVariant)) {
            return; // No change
        }

        config.setOnDeviceModelVariant(newVariant);
        config.setOnDeviceModelPath(""); // Clear the saved path — new model needs to be downloaded
        updateOnDeviceModelStatus();

        // Delete the old model file to save space
        String oldModelPath = OnDeviceLlmClient.getModelPath(this, oldVariant);
        java.io.File oldModelFile = new java.io.File(oldModelPath);
        if (oldModelFile.exists()) {
            if (oldModelFile.delete()) {
                android.widget.Toast.makeText(this, "Previous model deleted to save space", android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startModelDownload() {
        String variant = config.getOnDeviceModelVariant();
        String modelPath = OnDeviceLlmClient.getModelPath(this, variant);
        config.setOnDeviceModelPath(modelPath);
        btnDownloadModel.setEnabled(false);
        textOnDeviceLlmStatus.setText(String.format(getString(R.string.on_device_model_downloading), 0));

        new Thread(() -> {
            try {
                java.io.File outputFile = new java.io.File(modelPath);
                java.io.File tempFile = new java.io.File(modelPath + ".tmp");
                String url;
                if (AppConfig.MODEL_VARIANT_GEMMA_E2B.equals(variant)) {
                    url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/"
                            + "gemma-3n-E2B-it-int4.litertlm";
                } else {
                    url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/"
                            + "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm";
                }

                okhttp3.OkHttpClient downloadClient = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                        .build();
                okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder().url(url);
                String token = config.getHuggingFaceToken();
                if (token != null && !token.isEmpty()) {
                    requestBuilder.header("Authorization", "Bearer " + token);
                }
                okhttp3.Request request = requestBuilder.build();
                okhttp3.Response response = downloadClient.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Download failed: HTTP " + response.code());
                }

                long contentLength = response.body().contentLength();
                try (java.io.InputStream in = response.body().byteStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    long downloaded = 0;
                    int lastPercent = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        downloaded += read;
                        if (contentLength > 0) {
                            int percent = (int) (downloaded * 100 / contentLength);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final int p = percent;
                                runOnUiThread(() -> textOnDeviceLlmStatus.setText(
                                        String.format(getString(R.string.on_device_model_downloading), p)));
                            }
                        }
                    }
                }

                if (!tempFile.renameTo(outputFile)) {
                    throw new IOException("Failed to move downloaded model into place");
                }

                runOnUiThread(() -> {
                    btnDownloadModel.setEnabled(true);
                    updateOnDeviceModelStatus();
                    Toast.makeText(this, "Model downloaded successfully", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                final String message = e.getMessage();
                runOnUiThread(() -> {
                    btnDownloadModel.setEnabled(true);
                    textOnDeviceLlmStatus.setText(String.format(
                            getString(R.string.on_device_model_download_failed), message));
                    Toast.makeText(this, "Model download failed", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private static List<FeedPreset> buildFeedPresets() {
        List<FeedPreset> presets = new ArrayList<>();
        presets.add(new FeedPreset(
                "Financial Times - International",
                "https://www.ft.com/rss/home/international"
        ));
        presets.add(new FeedPreset(
                "BBC News - Front Page",
                "https://feeds.bbci.co.uk/news/rss.xml"
        ));
        presets.add(new FeedPreset(
                "BBC News - World",
                "https://feeds.bbci.co.uk/news/world/rss.xml"
        ));
        presets.add(new FeedPreset(
                "BBC News - Business",
                "https://feeds.bbci.co.uk/news/business/rss.xml"
        ));
        return presets;
    }

    private static final class FeedPreset {
        final String label;
        final String url;

        FeedPreset(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    // --- Social Settings Lock ---

    private void refreshSocialSettingsLock() {
        boolean locked = config.isFrictionEnabled() && !socialSettingsUnlocked;
        setSocialSettingsLocked(locked);
        boolean canEditSocialSettings = !config.isFrictionEnabled() || socialSettingsUnlocked;
        MaterialSwitch socialModuleSwitch = moduleSwitches.get(AppConfig.MODULE_SOCIAL);
        boolean socialModuleEnabled = socialModuleSwitch != null
                ? socialModuleSwitch.isChecked()
                : config.isSocialModuleEnabled();
        boolean canToggleSocialModule = canEditSocialSettings || !socialModuleEnabled;
        boolean showUnlockHint = socialModuleEnabled && !canEditSocialSettings;
        if (socialModuleSwitch != null) {
            updatingSocialModuleSwitch = true;
            socialModuleSwitch.setEnabled(canToggleSocialModule);
            socialModuleSwitch.setAlpha(canToggleSocialModule ? 1.0f : 0.5f);
            socialModuleSwitch.setChecked(config.isSocialModuleEnabled());
            updatingSocialModuleSwitch = false;
        }
        checkboxSocialInstagram.setEnabled(canEditSocialSettings);
        checkboxSocialInstagram.setAlpha(canEditSocialSettings ? 1.0f : 0.5f);
        checkboxSocialLinkedin.setEnabled(canEditSocialSettings);
        checkboxSocialLinkedin.setAlpha(canEditSocialSettings ? 1.0f : 0.5f);
        checkboxSocialPressHome.setEnabled(canEditSocialSettings);
        checkboxSocialPressHome.setAlpha(canEditSocialSettings ? 1.0f : 0.5f);
        socialModuleLockHint.setVisibility(showUnlockHint ? View.VISIBLE : View.GONE);
        btnUnlockSocial.setVisibility(config.isFrictionEnabled() && !canEditSocialSettings ? View.VISIBLE : View.GONE);
        btnUnlockSocial.setText(config.isFrictionEnabled() ? "Unlock settings" : "Unlock social app settings");
    }

    private void setSocialSettingsLocked(boolean locked) {
        inputTimerDuration.setEnabled(!locked);
        inputFrictionWords.setEnabled(!locked);
    }

    private void launchSocialUnlock() {
        Intent intent = new Intent(this, FrictionGateActivity.class);
        intent.putExtra("WORD_COUNT", config.getFrictionWordCount());
        intent.putExtra("CONTEXT_TITLE", "Unlock social app settings");
        frictionGateLauncher.launch(intent);
    }

    private void refreshSocialBlockingState() {
        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.refreshBlockState();
        }
    }

    private String buildSettingsExportFileName() {
        Calendar now = Calendar.getInstance();
        return String.format(
                Locale.US,
                "breakfast-settings-%1$tY%1$tm%1$td.json",
                now
        );
    }

    private void exportSettingsToUri(Uri uri) {
        if (uri == null) {
            return;
        }

        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            if (outputStream == null) {
                throw new IOException("Unable to open export destination");
            }
            outputStream.write(config.exportSettingsJson().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            Toast.makeText(this, "Settings exported.", Toast.LENGTH_SHORT).show();
        } catch (IOException exception) {
            Toast.makeText(this, "Could not export settings.", Toast.LENGTH_LONG).show();
        }
    }

    private void showImportSettingsConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_import_title)
                .setMessage("Importing replaces your current Breakfast settings with the contents of a backup file.")
                .setPositiveButton("Import", (dialog, which) ->
                        importSettingsLauncher.launch(new String[]{"application/json", "text/plain", "*/*"}))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void importSettingsFromUri(Uri uri) {
        if (uri == null) {
            return;
        }

        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                throw new IOException("Unable to open import source");
            }

            config.importSettingsJson(readAllText(inputStream));
            loadSettings();
            refreshSocialBlockingState();
            DashboardScheduler.scheduleMorningRefresh(this);
            Toast.makeText(this, "Settings imported.", Toast.LENGTH_SHORT).show();
        } catch (IOException | IllegalArgumentException exception) {
            Toast.makeText(this, "Could not import that settings file.", Toast.LENGTH_LONG).show();
        }
    }

    private String readAllText(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    // --- Utilities ---

    private void setupAutoSave(TextInputEditText field, Runnable saveAction) {
        field.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveAction.run();
        });
    }

    private void setupWindowInsets() {
        View rootLayout = findViewById(R.id.main);
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
            Insets navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), statusBarInsets.top, v.getPaddingRight(), navBarInsets.bottom);
            return insets;
        });
    }

    private void setupNavigationBarColor() {
        int backgroundColor = getResources().getColor(R.color.background_main, getTheme());
        getWindow().setNavigationBarColor(backgroundColor);

        boolean isLightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int appearance = isLightMode ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0;
                controller.setSystemBarsAppearance(appearance, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            View decorView = getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (isLightMode) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
