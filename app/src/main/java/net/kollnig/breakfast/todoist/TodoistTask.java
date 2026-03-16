package net.kollnig.breakfast.todoist;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

public class TodoistTask {
    public String id;
    public String content;
    public String description;
    public int priority;
    public String url;
    public Due due;
    public String sectionId;
    public String sectionName;
    public int sectionOrder = Integer.MAX_VALUE;
    public int taskOrder = Integer.MAX_VALUE;

    public static class Due {
        public String date;
        public String datetime;
        public String string;
        public boolean isRecurring;
    }
}
