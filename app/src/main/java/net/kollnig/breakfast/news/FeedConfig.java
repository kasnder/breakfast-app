package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

public class FeedConfig {
    public String url;
    public boolean includeInTopStories;
    public boolean includeInHeadlines;

    public FeedConfig() {}

    public FeedConfig(String url, boolean includeInTopStories, boolean includeInHeadlines) {
        this.url = url;
        this.includeInTopStories = includeInTopStories;
        this.includeInHeadlines = includeInHeadlines;
    }
}
