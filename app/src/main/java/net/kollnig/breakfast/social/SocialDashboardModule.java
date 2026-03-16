package net.kollnig.breakfast.social;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.app.Activity;
import android.content.Intent;
import android.os.CountDownTimer;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import net.kollnig.distractionlib.FrictionGateActivity;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class SocialDashboardModule {

    public interface FrictionGateLauncher {
        void launchFrictionGate(Intent intent);
    }

    public interface AccessibilitySettingsOpener {
        void openAccessibilitySettings();
    }

    public interface AccessibilityChecker {
        boolean isAccessibilityServiceEnabled();
    }

    private final Activity activity;
    private final AppConfig config;

    private final FrictionGateLauncher frictionGateLauncher;
    private final AccessibilitySettingsOpener accessibilitySettingsOpener;
    private final AccessibilityChecker accessibilityChecker;

    // UI
    private final TextView socialStatus;
    private final TextView socialTimer;
    private final MaterialButton btnInstagram;
    private final MaterialButton btnLinkedin;
    private final MaterialButton btnResetTimer;
    private final TextView socialResetHint;

    // State
    private CountDownTimer countDownTimer;
    private final TimerNotification timerNotification;
    private boolean resetRequested;
    private boolean accessibilityDialogShown;

    public SocialDashboardModule(
            Activity activity,
            View cardRoot,
            AppConfig config,
            FrictionGateLauncher frictionGateLauncher,
            AccessibilitySettingsOpener accessibilitySettingsOpener,
            AccessibilityChecker accessibilityChecker) {
        this.activity = activity;
        this.config = config;
        this.frictionGateLauncher = frictionGateLauncher;
        this.accessibilitySettingsOpener = accessibilitySettingsOpener;
        this.accessibilityChecker = accessibilityChecker;

        socialStatus = cardRoot.findViewById(R.id.social_status);
        socialTimer = cardRoot.findViewById(R.id.social_timer);
        btnInstagram = cardRoot.findViewById(R.id.btn_instagram);
        btnLinkedin = cardRoot.findViewById(R.id.btn_linkedin);
        btnResetTimer = cardRoot.findViewById(R.id.btn_reset_timer);
        socialResetHint = cardRoot.findViewById(R.id.social_reset_hint);

        timerNotification = new TimerNotification(activity);

        btnInstagram.setOnClickListener(v -> handleSocialClick("com.instagram.android"));
        btnLinkedin.setOnClickListener(v -> handleSocialClick("com.linkedin.android"));
        btnResetTimer.setOnClickListener(v -> requestResetWithFriction());
    }

    public void checkAccessibilityOnResume() {
        if (config.areSocialBlocksEnabled() && !accessibilityChecker.isAccessibilityServiceEnabled()) {
            showAccessibilityRequiredDialog();
        }
    }

    public void refresh() {
        updateSocialButtonVisibility();

        if (!config.isAnySocialAppEnabled()) {
            socialStatus.setText("No social apps are enabled in Settings.");
            socialTimer.setVisibility(View.GONE);
            btnResetTimer.setVisibility(View.GONE);
            socialResetHint.setVisibility(View.GONE);
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
            return;
        }

        if (config.isSocialTimerRunning()) {
            socialStatus.setText(getPostTimerStatusText());
            setEnabledIfVisible(btnInstagram, true);
            setEnabledIfVisible(btnLinkedin, true);
            btnResetTimer.setVisibility(View.GONE);
            socialResetHint.setText(buildSocialResetHint());
            socialResetHint.setVisibility(View.VISIBLE);
            startCountdown();
        } else if (config.isSocialBlockedToday()) {
            socialStatus.setText("Full-access window used up.");
            socialTimer.setVisibility(View.GONE);
            setEnabledIfVisible(btnInstagram, false);
            setEnabledIfVisible(btnLinkedin, false);
            btnResetTimer.setVisibility(View.VISIBLE);
            btnResetTimer.setText("Reset for another window");
            socialResetHint.setText(getPostTimerHintPrefix() + buildSocialResetHint());
            socialResetHint.setVisibility(View.VISIBLE);
            if (countDownTimer != null) {
                countDownTimer.cancel();
            }
        } else {
            socialStatus.setText("Tap an enabled app to start a "
                    + config.getTimerDurationMins() + "-minute full-access window.");
            setEnabledIfVisible(btnInstagram, true);
            setEnabledIfVisible(btnLinkedin, true);
            socialTimer.setVisibility(View.GONE);
            btnResetTimer.setVisibility(View.GONE);
            socialResetHint.setText(getPostTimerHintPrefix()
                    + buildSocialResetHint());
            socialResetHint.setVisibility(View.VISIBLE);
        }
    }

    public void onPause() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
    }

    /**
     * Called by the Activity when the friction gate launcher returns a result.
     */
    public void onFrictionGateResult(int resultCode) {
        if (!resetRequested) return;
        resetRequested = false;

        if (resultCode == Activity.RESULT_OK) {
            resetSocialTimer();
        }
    }

    public String buildSocialSummary() {
        StringBuilder summary = new StringBuilder(getTextValue(socialStatus));
        String timerText = getTextValue(socialTimer);
        if (!timerText.isEmpty() && socialTimer.getVisibility() == View.VISIBLE) {
            if (summary.length() > 0) {
                summary.append(" ");
            }
            summary.append(timerText);
        }
        return summary.toString().trim();
    }

    // ==================== PRIVATE ====================

    private void requestResetWithFriction() {
        if (!config.isSocialTimerRunning() && !config.isSocialBlockedToday()) {
            return;
        }

        resetRequested = true;
        Intent intent = new Intent(activity, FrictionGateActivity.class);
        intent.putExtra("WORD_COUNT", config.getFrictionWordCount());
        String title = config.isSocialBlockedToday() ? "Reset for another window" : "Reset social window";
        intent.putExtra("CONTEXT_TITLE", title);
        frictionGateLauncher.launchFrictionGate(intent);
    }

    private void resetSocialTimer() {
        config.clearSocialTimer();
        config.startSocialTimer();
        long remaining = config.getSocialTimeRemaining();
        timerNotification.start(remaining, this::onSocialTimerExpired);
        refresh();

        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.refreshBlockState();
        }
    }

    private void handleSocialClick(String packageName) {
        if (!isSocialAppEnabled(packageName)) {
            return;
        }

        Intent launchIntent = activity.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            String appName = packageName.contains("instagram") ? "Instagram" : "LinkedIn";
            Toast.makeText(activity, appName + " is not installed", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!accessibilityChecker.isAccessibilityServiceEnabled()) {
            showAccessibilityConsentDialog();
            return;
        }

        if (!config.isSocialTimerRunning() && !config.isSocialBlockedToday()) {
            config.startSocialTimer();
            long remaining = config.getSocialTimeRemaining();
            timerNotification.start(remaining, this::onSocialTimerExpired);

            DistractionControlService service = DistractionControlService.getInstance();
            if (service != null) {
                service.refreshBlockState();
            }
        }

        activity.startActivity(launchIntent);
        refresh();
    }

    private void showAccessibilityConsentDialog() {
        new android.app.AlertDialog.Builder(activity)
                .setTitle("Enable Accessibility Service")
                .setMessage("Breakfast uses Accessibility to limit Instagram and LinkedIn after your social window ends, while keeping messages reachable. Enable it in Settings to continue.")
                .setPositiveButton("Open Settings", (dialog, which) ->
                        accessibilitySettingsOpener.openAccessibilitySettings())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAccessibilityRequiredDialog() {
        if (accessibilityDialogShown) return;
        accessibilityDialogShown = true;

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle("Accessibility Service Required")
                .setMessage("Breakfast needs its accessibility service to enforce your social app limits. Please enable it in Settings to keep using this feature.")
                .setPositiveButton("Open Settings", (d, which) -> {
                    accessibilityDialogShown = false;
                    accessibilitySettingsOpener.openAccessibilitySettings();
                })
                .setCancelable(false)
                .create();
        dialog.show();
    }

    private void startCountdown() {
        long remaining = config.getSocialTimeRemaining();
        if (remaining <= 0) {
            onSocialTimerExpired();
            return;
        }

        socialTimer.setVisibility(View.VISIBLE);

        timerNotification.start(remaining, this::onSocialTimerExpired);

        if (countDownTimer != null)
            countDownTimer.cancel();
        countDownTimer = new CountDownTimer(remaining, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int minutes = (int) (millisUntilFinished / 60000);
                int seconds = (int) ((millisUntilFinished % 60000) / 1000);
                socialTimer.setText(String.format(Locale.getDefault(), "%d:%02d remaining", minutes, seconds));
            }

            @Override
            public void onFinish() {
                // Notification's onExpired callback handles the actual expiry logic
            }
        };
        countDownTimer.start();
    }

    private void onSocialTimerExpired() {
        config.setSocialBlockedToday();
        refresh();

        DistractionControlService service = DistractionControlService.getInstance();
        if (service != null) {
            service.refreshBlockState();
        }
    }

    private void updateSocialButtonVisibility() {
        btnInstagram.setVisibility(config.isInstagramSocialEnabled() ? View.VISIBLE : View.GONE);
        btnLinkedin.setVisibility(config.isLinkedinSocialEnabled() ? View.VISIBLE : View.GONE);
    }

    private void setEnabledIfVisible(MaterialButton button, boolean enabled) {
        if (button.getVisibility() != View.VISIBLE) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.4f);
    }

    private boolean isSocialAppEnabled(String packageName) {
        if ("com.instagram.android".equals(packageName)) {
            return config.isInstagramSocialEnabled();
        }
        if ("com.linkedin.android".equals(packageName)) {
            return config.isLinkedinSocialEnabled();
        }
        return false;
    }

    private String getTextValue(TextView textView) {
        CharSequence text = textView.getText();
        return text == null ? "" : text.toString().trim();
    }

    private String buildSocialResetHint() {
        long nextResetMs = config.getNextDeliveryWindowStartMs();
        String timeLabel = DateFormat.getTimeFormat(activity).format(new Date(nextResetMs));

        Calendar now = Calendar.getInstance();
        Calendar nextReset = Calendar.getInstance();
        nextReset.setTimeInMillis(nextResetMs);
        boolean sameDay = now.get(Calendar.YEAR) == nextReset.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == nextReset.get(Calendar.DAY_OF_YEAR);

        return sameDay
                ? "Daily limit resets today at " + timeLabel
                : "Daily limit resets tomorrow at " + timeLabel;
    }

    private String getPostTimerStatusText() {
        return config.shouldPressHomeWhenSocialTimeIsUp()
                ? "When the timer ends, LinkedIn and Instagram send you back Home."
                : "When the timer ends, only messages stay available.";
    }

    private String getPostTimerHintPrefix() {
        return config.shouldPressHomeWhenSocialTimeIsUp()
                ? "After it ends, LinkedIn and Instagram return to Home. "
                : "After it ends, only messages stay available. ";
    }
}
