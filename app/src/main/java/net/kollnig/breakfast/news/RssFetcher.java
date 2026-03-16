package net.kollnig.breakfast.news;

import net.kollnig.breakfast.*;
import net.kollnig.breakfast.calendar.*;
import net.kollnig.breakfast.dashboard.*;
import net.kollnig.breakfast.news.*;
import net.kollnig.breakfast.social.*;
import net.kollnig.breakfast.todoist.*;
import net.kollnig.breakfast.weather.*;

import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches and parses RSS/Atom feeds. Filters to articles from the last 24 hours.
 * Runs synchronously — call from a background thread.
 */
public class RssFetcher {
    private static final String TAG = "RssFetcher";

    private final OkHttpClient client;
    private int failedFeedCount;

    // Common RSS date formats
    private static final String[] DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
            "dd MMM yyyy HH:mm:ss Z",
    };

    public RssFetcher() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /**
     * Returns the number of feeds that failed during the last {@link #fetchAllFeeds} call.
     */
    public int getFailedFeedCount() {
        return failedFeedCount;
    }

    /**
     * Fetch all articles from the given feed URL published in the last 24 hours.
     * Returns null if the fetch itself failed (network error, HTTP error), as
     * opposed to an empty list which means the feed loaded but had no recent articles.
     */
    public List<ArticleData> fetchFeed(String feedUrl) {
        try {
            Request request = new Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", "Breakfast/1.0")
                    .build();
            Response response = client.newCall(request).execute();

            if (!response.isSuccessful() || response.body() == null) {
                Log.e(TAG, "Feed fetch failed: " + feedUrl + " code=" + response.code());
                return null;
            }

            String xml = response.body().string();
            List<ArticleData> articles = parseRss(xml, feedUrl);

            // Filter to last 24 hours
            long cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            List<ArticleData> recent = new ArrayList<>();
            for (ArticleData article : articles) {
                // If we couldn't parse the date, include it anyway
                if (article.pubDate == 0 || article.pubDate >= cutoff) {
                    recent.add(article);
                }
            }
            return recent;

        } catch (Exception e) {
            Log.e(TAG, "Error fetching feed: " + feedUrl, e);
            return null;
        }
    }

    /**
     * Fetch articles from multiple feed URLs. Tracks how many feeds failed
     * (accessible via {@link #getFailedFeedCount()}).
     */
    public List<ArticleData> fetchAllFeeds(List<String> feedUrls) {
        failedFeedCount = 0;
        List<ArticleData> allArticles = new ArrayList<>();
        for (String url : feedUrls) {
            List<ArticleData> articles = fetchFeed(url);
            if (articles == null) {
                failedFeedCount++;
            } else {
                allArticles.addAll(articles);
            }
        }
        return allArticles;
    }

    private List<ArticleData> parseRss(String xml, String feedUrl) {
        List<ArticleData> articles = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            boolean inItem = false;
            boolean inEntry = false; // Atom feeds use <entry>
            String title = null;
            String description = null;
            String link = null;
            String imageUrl = null;
            String pubDate = null;

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();

                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if ("item".equalsIgnoreCase(tag)) {
                            inItem = true;
                            title = description = link = imageUrl = pubDate = null;
                        } else if ("entry".equalsIgnoreCase(tag)) {
                            inEntry = true;
                            title = description = link = imageUrl = pubDate = null;
                        } else if (inItem || inEntry) {
                            if ("title".equalsIgnoreCase(tag)) {
                                title = safeNextText(parser);
                            } else if ("description".equalsIgnoreCase(tag) ||
                                       "summary".equalsIgnoreCase(tag) ||
                                       "content".equalsIgnoreCase(tag)) {
                                String rawText = safeNextText(parser);
                                if (description == null) {
                                    description = stripHtml(rawText);
                                }
                                if (imageUrl == null) {
                                    imageUrl = extractImageUrl(rawText, link, feedUrl);
                                }
                            } else if ("link".equalsIgnoreCase(tag)) {
                                // Atom: link is in href attribute
                                String href = parser.getAttributeValue(null, "href");
                                if (href != null && !href.isEmpty()) {
                                    if (link == null) link = href;
                                } else {
                                    String text = safeNextText(parser);
                                    if (text != null && !text.isEmpty() && link == null) {
                                        link = text;
                                    }
                                }
                            } else if ("enclosure".equalsIgnoreCase(tag)) {
                                if (imageUrl == null) {
                                    imageUrl = extractImageAttribute(parser, link, feedUrl);
                                }
                            } else if (isMediaImageTag(tag)) {
                                if (imageUrl == null) {
                                    imageUrl = extractImageAttribute(parser, link, feedUrl);
                                }
                            } else if ("pubDate".equalsIgnoreCase(tag) ||
                                       "published".equalsIgnoreCase(tag) ||
                                       "updated".equalsIgnoreCase(tag) ||
                                       "dc:date".equalsIgnoreCase(tag)) {
                                if (pubDate == null) {
                                    pubDate = safeNextText(parser);
                                }
                            }
                        }
                        break;

                    case XmlPullParser.END_TAG:
                        if (("item".equalsIgnoreCase(tag) && inItem) ||
                            ("entry".equalsIgnoreCase(tag) && inEntry)) {
                            if (title != null && !title.isEmpty()) {
                                ArticleData article = new ArticleData(
                                        title,
                                        description != null ? description : "",
                                        link != null ? link : "",
                                        imageUrl,
                                        parseDate(pubDate),
                                        feedUrl
                                );
                                articles.add(article);
                            }
                            inItem = false;
                            inEntry = false;
                        }
                        break;
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing RSS", e);
        }
        return articles;
    }

    private String safeNextText(XmlPullParser parser) {
        try {
            return parser.nextText();
        } catch (Exception e) {
            return "";
        }
    }

    private long parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return 0;
        dateStr = dateStr.trim();
        for (String format : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.ENGLISH);
                Date date = sdf.parse(dateStr);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) {}
        }
        Log.w(TAG, "Could not parse date: " + dateStr);
        return 0;
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        // Basic HTML stripping
        return html.replaceAll("<[^>]*>", "")
                   .replaceAll("&amp;", "&")
                   .replaceAll("&lt;", "<")
                   .replaceAll("&gt;", ">")
                   .replaceAll("&quot;", "\"")
                   .replaceAll("&#39;", "'")
                   .replaceAll("&nbsp;", " ")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    private String extractImageAttribute(XmlPullParser parser, String articleLink, String feedUrl) {
        String url = parser.getAttributeValue(null, "url");
        if (url == null || url.isEmpty()) {
            url = parser.getAttributeValue(null, "href");
        }
        String type = parser.getAttributeValue(null, "type");
        if (url == null || url.isEmpty()) {
            return null;
        }
        if (type != null && !type.isEmpty() && !type.startsWith("image/")) {
            return null;
        }
        return normalizeUrl(url, articleLink, feedUrl);
    }

    private boolean isMediaImageTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        String lowerTag = tag.toLowerCase(Locale.US);
        return lowerTag.startsWith("media:")
                || lowerTag.endsWith("thumbnail")
                || lowerTag.endsWith("content")
                || lowerTag.endsWith("image");
    }

    private String extractImageUrl(String rawText, String articleLink, String feedUrl) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }

        String[] patterns = {
                "(?i)<img[^>]+src=[\"']([^\"']+)[\"']",
                "(?i)<media:content[^>]+url=[\"']([^\"']+)[\"']",
                "(?i)<media:thumbnail[^>]+url=[\"']([^\"']+)[\"']"
        };
        for (String pattern : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(pattern).matcher(rawText);
            if (matcher.find()) {
                return normalizeUrl(matcher.group(1), articleLink, feedUrl);
            }
        }
        return null;
    }

    private String normalizeUrl(String candidateUrl, String articleLink, String feedUrl) {
        if (candidateUrl == null || candidateUrl.isEmpty()) {
            return null;
        }
        try {
            URI uri = new URI(candidateUrl.trim());
            if (uri.isAbsolute()) {
                return uri.toString();
            }
            if (articleLink != null && !articleLink.isEmpty()) {
                return new URI(articleLink).resolve(uri).toString();
            }
            if (feedUrl != null && !feedUrl.isEmpty()) {
                return new URI(feedUrl).resolve(uri).toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
