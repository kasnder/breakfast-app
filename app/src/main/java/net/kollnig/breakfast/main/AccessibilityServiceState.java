package net.kollnig.breakfast.main;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AccessibilityServiceState {
    private AccessibilityServiceState() {
    }

    public static boolean isEnabled(
            Context context,
            Class<? extends AccessibilityService> serviceClass) {
        ComponentName expectedComponent = new ComponentName(context, serviceClass);

        try {
            AccessibilityManager accessibilityManager =
                    context.getSystemService(AccessibilityManager.class);
            if (accessibilityManager != null) {
                List<AccessibilityServiceInfo> enabledServices =
                        accessibilityManager.getEnabledAccessibilityServiceList(
                                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                for (AccessibilityServiceInfo serviceInfo : enabledServices) {
                    if (serviceInfo == null
                            || serviceInfo.getResolveInfo() == null
                            || serviceInfo.getResolveInfo().serviceInfo == null) {
                        continue;
                    }

                    ComponentName enabledComponent = new ComponentName(
                            serviceInfo.getResolveInfo().serviceInfo.packageName,
                            serviceInfo.getResolveInfo().serviceInfo.name);
                    if (expectedComponent.equals(enabledComponent)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to checking the secure settings string below.
        }

        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null || enabledServices.isEmpty()) {
                return false;
            }

            Set<String> expectedNames = new HashSet<>();
            expectedNames.add(expectedComponent.flattenToString());
            expectedNames.add(expectedComponent.flattenToShortString());

            String[] entries = enabledServices.split(":");
            for (String entry : entries) {
                if (expectedNames.contains(entry)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }

        return false;
    }
}
