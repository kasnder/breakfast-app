package net.kollnig.breakfast.calendar;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

public class CalendarInfo {
    public final long id;
    public final String displayName;
    public final String accountName;
    public final int color;

    public CalendarInfo(long id, String displayName, String accountName, int color) {
        this.id = id;
        this.displayName = displayName;
        this.accountName = accountName;
        this.color = color;
    }
}
