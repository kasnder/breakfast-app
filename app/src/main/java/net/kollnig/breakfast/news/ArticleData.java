package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

/**
 * Represents a single news article from an RSS feed, with optional LLM summary.
 */
public class ArticleData {
    public String title;
    public String originalDescription;
    public String llmSummary;
    public String link;
    public String imageUrl;
    public long pubDate;
    public String sourceFeedUrl;
    public float interestScore;

    public ArticleData() {}

    public ArticleData(String title, String originalDescription, String link,
                       long pubDate, String sourceFeedUrl) {
        this(title, originalDescription, link, null, pubDate, sourceFeedUrl);
    }

    public ArticleData(String title, String originalDescription, String link,
                       String imageUrl, long pubDate, String sourceFeedUrl) {
        this.title = title;
        this.originalDescription = originalDescription;
        this.link = link;
        this.imageUrl = imageUrl;
        this.pubDate = pubDate;
        this.sourceFeedUrl = sourceFeedUrl;
        this.llmSummary = "";
        this.interestScore = 0f;
    }
}
