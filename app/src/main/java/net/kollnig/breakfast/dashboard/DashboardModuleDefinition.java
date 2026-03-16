package net.kollnig.breakfast.dashboard;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

public interface DashboardModuleDefinition {
    String getId();
    int getTitleResId();
    boolean isEnabled(AppConfig config);
    void setEnabled(AppConfig config, boolean enabled);
}
