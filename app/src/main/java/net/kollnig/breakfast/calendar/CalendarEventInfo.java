package net.kollnig.breakfast.calendar;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

public class CalendarEventInfo {
    public final long id;
    public final String title;
    public final long startMillis;
    public final long endMillis;
    public final boolean allDay;
    public final String calendarName;
    public final int calendarColor;

    public CalendarEventInfo(long id, String title, long startMillis, long endMillis,
                             boolean allDay, String calendarName, int calendarColor) {
        this.id = id;
        this.title = title;
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.allDay = allDay;
        this.calendarName = calendarName;
        this.calendarColor = calendarColor;
    }
}
