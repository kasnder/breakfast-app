package net.kollnig.breakfast.dashboard;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

public class BuiltInDashboardModule implements DashboardModuleDefinition {
    public interface ModuleEnabledReader {
        boolean read(AppConfig config);
    }

    public interface ModuleEnabledWriter {
        void write(AppConfig config, boolean enabled);
    }

    private final String id;
    private final int titleResId;
    private final ModuleEnabledReader enabledReader;
    private final ModuleEnabledWriter enabledWriter;

    public BuiltInDashboardModule(String id, int titleResId,
                                  ModuleEnabledReader enabledReader,
                                  ModuleEnabledWriter enabledWriter) {
        this.id = id;
        this.titleResId = titleResId;
        this.enabledReader = enabledReader;
        this.enabledWriter = enabledWriter;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public int getTitleResId() {
        return titleResId;
    }

    @Override
    public boolean isEnabled(AppConfig config) {
        return enabledReader.read(config);
    }

    @Override
    public void setEnabled(AppConfig config, boolean enabled) {
        enabledWriter.write(config, enabled);
    }
}
