package net.kollnig.breakfast;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import net.kollnig.distractionlib.BaseDistractionControlService;
import net.kollnig.distractionlib.FilterRule;
import net.kollnig.distractionlib.FilterRuleParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class DistractionControlService extends BaseDistractionControlService {
    private static final String TAG = "DistractionBlockerService";
    private static final String INSTAGRAM_PACKAGE = "com.instagram.android";
    private static final String LINKEDIN_PACKAGE = "com.linkedin.android";

    private static DistractionControlService instance;

    private AppConfig config;

    public static DistractionControlService getInstance() {
        return instance;
    }

    @Override
    protected List<FilterRule> loadRules() {
        List<FilterRule> rules = new ArrayList<>();
        try {
            InputStream is = getAssets().open("distraction_rules.txt");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            reader.close();

            FilterRuleParser parser = new FilterRuleParser();
            for (FilterRule rule : parser.parseRules(lines.toArray(new String[0]))) {
                if (shouldApplyRulesToPackage(rule.packageName)) {
                    rules.add(rule);
                }
            }
            Log.i(TAG, "Loaded " + rules.size() + " blocking rules");
        } catch (Exception e) {
            Log.e(TAG, "Error loading blocking rules", e);
        }
        return rules;
    }

    @Override
    protected boolean shouldProcessRules() {
        if (config == null) {
            config = new AppConfig(this);
        }
        return config.areSocialBlocksEnabled() && !config.isSocialTimerRunning();
    }

    @Override
    protected void onServiceReady() {
        instance = this;
        config = new AppConfig(this);
    }

    @Override
    protected void onServiceTeardown() {
        instance = null;
    }

    public void refreshBlockState() {
        Log.i(TAG, "Block state refreshed, blocked=" + shouldProcessRules());
        reloadRulesFromSource();
        reevaluateBlockingState();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (shouldBounceBlockedSocialAppToHome(event)) {
            Log.i(TAG, "Blocked social app opened after timer expiry, returning to launcher");
            forceClearCurrentOverlays();
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }

        super.onAccessibilityEvent(event);
    }

    private boolean shouldApplyRulesToPackage(String packageName) {
        if (config == null) {
            config = new AppConfig(this);
        }
        if (INSTAGRAM_PACKAGE.equals(packageName)) {
            return config.isInstagramSocialEnabled();
        }
        if (LINKEDIN_PACKAGE.equals(packageName)) {
            return config.isLinkedinSocialEnabled();
        }
        return false;
    }

    private boolean shouldBounceBlockedSocialAppToHome(AccessibilityEvent event) {
        if (event == null) {
            return false;
        }
        if (config == null) {
            config = new AppConfig(this);
        }
        if (!config.shouldPressHomeWhenSocialTimeIsUp() || !shouldProcessRules()) {
            return false;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return false;
        }
        CharSequence packageName = event.getPackageName();
        return packageName != null && shouldApplyRulesToPackage(packageName.toString());
    }
}
