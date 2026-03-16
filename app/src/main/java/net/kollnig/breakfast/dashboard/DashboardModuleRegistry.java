package net.kollnig.breakfast.dashboard;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DashboardModuleRegistry {
    private static final List<DashboardModuleDefinition> MODULES = buildModules();

    private DashboardModuleRegistry() {
    }

    public static List<DashboardModuleDefinition> getModules() {
        return MODULES;
    }

    public static DashboardModuleDefinition findById(String moduleId) {
        for (DashboardModuleDefinition module : MODULES) {
            if (module.getId().equals(moduleId)) {
                return module;
            }
        }
        return null;
    }

    public static List<String> getModuleIds() {
        List<String> ids = new ArrayList<>();
        for (DashboardModuleDefinition module : MODULES) {
            ids.add(module.getId());
        }
        return ids;
    }

    private static List<DashboardModuleDefinition> buildModules() {
        List<DashboardModuleDefinition> modules = new ArrayList<>();

        // Register built-in dashboard modules here. New developer-added modules should be added once
        // in this list so ordering, settings, and host layout logic can discover them consistently.
        modules.add(new BuiltInDashboardModule(
                AppConfig.MODULE_WEATHER,
                R.string.module_weather,
                AppConfig::isWeatherModuleEnabled,
                AppConfig::setWeatherModuleEnabled));
        modules.add(new BuiltInDashboardModule(
                AppConfig.MODULE_SOCIAL,
                R.string.module_social,
                AppConfig::isSocialModuleEnabled,
                AppConfig::setSocialModuleEnabled));
        modules.add(new BuiltInDashboardModule(
                AppConfig.MODULE_CALENDAR,
                R.string.module_calendar,
                AppConfig::isCalendarModuleEnabled,
                AppConfig::setCalendarModuleEnabled));
        modules.add(new BuiltInDashboardModule(
                AppConfig.MODULE_TODOIST,
                R.string.module_todoist,
                AppConfig::isTodoistModuleEnabled,
                AppConfig::setTodoistModuleEnabled));
        modules.add(new BuiltInDashboardModule(
                AppConfig.MODULE_NEWS,
                R.string.module_news,
                AppConfig::isNewsModuleEnabled,
                AppConfig::setNewsModuleEnabled));
        modules.add(new BuiltInDashboardModule(
                AppConfig.MODULE_EMAIL,
                R.string.module_email,
                AppConfig::isEmailModuleEnabled,
                AppConfig::setEmailModuleEnabled));

        return Collections.unmodifiableList(modules);
    }
}
