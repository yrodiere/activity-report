package activityreport.util;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for extracting URLs from text content to enable activity grouping.
 * Providers register their URL patterns based on configured instances.
 */
public class UrlExtractor {

    private final List<UrlPattern> urlPatterns = new ArrayList<>();
    private final List<IssueKeyPattern> issueKeyPatterns = new ArrayList<>();

    private record UrlPattern(Pattern pattern, String description) {}
    private record IssueKeyPattern(Pattern keyPattern, String baseUrl, String description) {}

    private static final Pattern JIRA_KEY_PATTERN = Pattern.compile(
        "\\b([A-Z][A-Z0-9]+-\\d+)\\b"
    );

    /**
     * Register a GitHub instance for PR URL extraction.
     * Supports both GitHub.com and GitHub Enterprise.
     */
    public void registerGitHubInstance(String apiUrl, String name) {
        String baseUrl = convertGitHubApiUrlToBaseUrl(apiUrl);
        String escapedHost = Pattern.quote(extractHost(baseUrl));
        Pattern pattern = Pattern.compile(
            "https://" + escapedHost + "/[\\w.-]+/[\\w.-]+/pull/\\d+"
        );
        urlPatterns.add(new UrlPattern(pattern, "GitHub PR - " + name));
    }

    /**
     * Register a GitLab instance for MR URL extraction.
     */
    public void registerGitLabInstance(String baseUrl, String name) {
        String escapedHost = Pattern.quote(extractHost(baseUrl));
        Pattern pattern = Pattern.compile(
            "https://" + escapedHost + "/[\\w.-]+/[\\w.-]+/-/merge_requests/\\d+"
        );
        urlPatterns.add(new UrlPattern(pattern, "GitLab MR - " + name));
    }

    /**
     * Register a JIRA instance for issue URL extraction and issue key resolution.
     */
    public void registerJiraInstance(String baseUrl, String name) {
        // Register URL pattern for full JIRA issue URLs
        String escapedHost = Pattern.quote(extractHost(baseUrl));
        Pattern urlPattern = Pattern.compile(
            "https://" + escapedHost + "/browse/([A-Z][A-Z0-9]+-\\d+)"
        );
        urlPatterns.add(new UrlPattern(urlPattern, "JIRA Issue URL - " + name));

        // Register issue key pattern for resolving references like "PROJ-123"
        issueKeyPatterns.add(new IssueKeyPattern(JIRA_KEY_PATTERN, baseUrl, "JIRA Issue Key - " + name));
    }

    /**
     * Extract all external URLs from text using registered patterns.
     */
    public void extractExternalUrls(String text, Set<String> urls) {
        if (text == null || text.isEmpty()) {
            return;
        }

        // Extract explicit URLs using registered patterns
        Set<String> extractedIssueKeys = new HashSet<>();
        for (UrlPattern urlPattern : urlPatterns) {
            Matcher matcher = urlPattern.pattern.matcher(text);
            while (matcher.find()) {
                String url = matcher.group();
                urls.add(url);
                // If this is a JIRA issue URL, track the issue key to avoid duplication
                if (url.contains("/browse/")) {
                    String[] parts = url.split("/browse/");
                    if (parts.length == 2) {
                        extractedIssueKeys.add(parts[1]);
                    }
                }
            }
        }

        // Resolve issue keys (e.g., "PROJ-123") to full URLs
        // Skip keys that were already found as full URLs
        for (IssueKeyPattern keyPattern : issueKeyPatterns) {
            Matcher matcher = keyPattern.keyPattern.matcher(text);
            while (matcher.find()) {
                String issueKey = matcher.group(1);
                // Skip if we already extracted this as a full URL
                if (!extractedIssueKeys.contains(issueKey)) {
                    String issueUrl = keyPattern.baseUrl + "/browse/" + issueKey;
                    urls.add(issueUrl);
                }
            }
        }
    }

    /**
     * Convert GitHub API URL to base URL.
     * Examples:
     * - https://api.github.com → https://github.com
     * - https://github.company.com/api/v3 → https://github.company.com
     */
    private String convertGitHubApiUrlToBaseUrl(String apiUrl) {
        if (apiUrl.equals("https://api.github.com")) {
            return "https://github.com";
        }
        // For GitHub Enterprise, remove /api/v3 suffix if present
        return apiUrl.replaceAll("/api(/v3)?$", "");
    }

    /**
     * Extract host from URL (e.g., "https://example.com/path" → "example.com").
     */
    private String extractHost(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            return uri.getHost();
        } catch (Exception e) {
            // Fallback: simple extraction
            return url.replaceAll("^https?://", "").replaceAll("/.*$", "");
        }
    }

}
