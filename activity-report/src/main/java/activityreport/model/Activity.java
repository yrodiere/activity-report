package activityreport.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core activity data model representing a single activity from any provider
 */
public record Activity(
    String source,        // e.g., "GitHub.com", "JIRA - Hibernate"
    String type,          // e.g., "commit", "issue", "pr", "message"
    String title,
    String description,
    String url,
    Instant timestamp,
    List<String> contentUrls,  // Additional URLs (comments, reviews, messages, etc.)
    Map<String, Object> metadata
) implements Comparable<Activity> {

    public Activity(String source, String type, String title, String description, String url, Instant timestamp) {
        this(source, type, title, description, url, timestamp, new ArrayList<>(), new HashMap<>());
    }

    public Activity(String source, String type, String title, String description, String url, Instant timestamp, List<String> contentUrls) {
        this(source, type, title, description, url, timestamp, contentUrls, new HashMap<>());
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }

    public void addContentUrl(String url) {
        this.contentUrls.add(url);
    }

    @Override
    public int compareTo(Activity other) {
        return this.timestamp.compareTo(other.timestamp);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", source, type, title);
    }
}
