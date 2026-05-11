package activityreport.providers;

import activityreport.client.BasicAuthRequestFilter;
import activityreport.client.JiraRestClient;
import activityreport.client.TraceClientLogger;
import activityreport.util.UrlExtractor;
import org.jboss.resteasy.reactive.client.api.LoggingScope;
import activityreport.config.AppConfig;
import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * JIRA Activity Provider supporting multiple instances
 */
public class JiraProvider implements ActivityProvider {
    private final List<JiraInstance> instances;

    private record JiraInstance(String name, String url, String email, String token, String defaultProject) {}

    public JiraProvider(AppConfig config, UrlExtractor urlExtractor) {
        this.instances = new ArrayList<>();

        config.providers().jira().ifPresent(jira -> {
            if (jira.enabled() && jira.instances() != null) {
                for (var instance : jira.instances()) {
                    instances.add(new JiraInstance(
                        instance.name(),
                        instance.url(),
                        instance.email(),
                        instance.token(),
                        instance.defaultProject().orElse(null)
                    ));

                    // Register this instance for URL extraction
                    urlExtractor.registerJiraInstance(instance.url(), instance.name());
                }
            }
        });
    }

    @Override
    public String getName() {
        return "JIRA (all instances)";
    }

    @Override
    public boolean isConfigured() {
        return !instances.isEmpty();
    }

    @Override
    public List<Activity> fetchActivities(Instant startDate, Instant endDate, UrlExtractor urlExtractor) throws Exception {
        List<Activity> allActivities = new ArrayList<>();

        for (JiraInstance instance : instances) {
            try {
                allActivities.addAll(fetchFromInstance(instance, startDate, endDate, urlExtractor));
            } catch (Exception e) {
                Log.warnf("Error fetching from JIRA instance %s: %s", instance.name, e.getMessage());
            }
        }

        return allActivities;
    }

    private List<Activity> fetchFromInstance(JiraInstance instance, Instant startDate, Instant endDate, UrlExtractor urlExtractor) throws Exception {
        List<Activity> activities = new ArrayList<>();

        Log.infof("Fetching activities from JIRA instance: %s", instance.name);

        // Build REST client for this instance
        var client = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url))
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .register(new BasicAuthRequestFilter(instance.email, instance.token))
            .loggingScope(LoggingScope.REQUEST_RESPONSE)
            .clientLogger(new TraceClientLogger())
            .build(JiraRestClient.class);

        // Build JQL query - find all issues the user participated in (created, commented, assigned)
        long daysAgo = Duration.between(startDate, Instant.now()).toDays();
        var jql = String.format("participant = currentUser() AND updated >= -%dd ORDER BY updated DESC", daysAgo + 1);

        // Build request body - expand changelog and renderedFields
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode request = mapper.createObjectNode();
        request.put("jql", jql);
        request.putArray("fields").add("key").add("summary").add("status").add("updated").add("issuetype");
        request.put("maxResults", 100);
        request.put("expand", "changelog,renderedFields");

        // Make API call
        var root = client.search(request);
        var issues = root.get("issues");

        if (issues != null && issues.isArray()) {
            for (JsonNode issue : issues) {
                String key = issue.get("key").asText();

                try {
                    String summary = issue.get("fields").get("summary").asText();
                    String issueType = issue.get("fields").get("issuetype").get("name").asText();
                    String status = issue.get("fields").get("status").get("name").asText();
                    String issueUrl = instance.url + "/browse/" + key;

                    // Collect content URLs from user's actions in the time period
                    List<String> contentUrls = new ArrayList<>();
                    Instant latestUserActivity = null;

                    // Parse changelog to find user's actions during the time period
                    var changelog = issue.get("changelog");
                    if (changelog != null && changelog.get("histories") != null) {
                        for (JsonNode history : changelog.get("histories")) {
                            String createdStr = history.get("created").asText();
                            Instant changeDate = Instant.parse(createdStr);

                            // Skip if outside date range
                            if (changeDate.isBefore(startDate) || changeDate.isAfter(endDate)) {
                                continue;
                            }

                            // Check if this change was made by the current user (by email)
                            var author = history.get("author");
                            if (author != null && author.get("emailAddress") != null) {
                                String authorEmail = author.get("emailAddress").asText();
                                if (instance.email.equals(authorEmail)) {
                                    // Update latest activity timestamp
                                    if (latestUserActivity == null || changeDate.isAfter(latestUserActivity)) {
                                        latestUserActivity = changeDate;
                                    }

                                    // Check if this history entry contains a comment
                                    var items = history.get("items");
                                    if (items != null && items.isArray()) {
                                        for (JsonNode item : items) {
                                            String field = item.get("field").asText();
                                            if ("comment".equals(field)) {
                                                // Comment was added - extract comment ID
                                                String commentId = item.get("to").asText();
                                                if (commentId != null && !commentId.isEmpty()) {
                                                    String commentUrl = instance.url + "/browse/" + key + "?focusedCommentId=" + commentId + "#comment-" + commentId;
                                                    contentUrls.add(commentUrl);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Extract pull request URLs from rendered description
                    Set<String> externalUrls = new java.util.LinkedHashSet<>();
                    extractExternalUrls(issue, externalUrls, urlExtractor);
                    contentUrls.addAll(externalUrls);
                    boolean hasPrUrls = !externalUrls.isEmpty();

                    // Only create activity if user had activity during the time period
                    if (latestUserActivity != null) {
                        // Determine action category: if there are PR URLs, it's code work, otherwise discuss
                        ActionCategory actionCategory = hasPrUrls ? ActionCategory.CODE : ActionCategory.DISCUSS;

                        Activity activity = new Activity(
                            "JIRA - " + instance.name,
                            "issue",
                            actionCategory,
                            key + ": " + summary,
                            "Type: " + issueType + ", Status: " + status,
                            issueUrl,
                            latestUserActivity,
                            contentUrls
                        );

                        activity.addMetadata("issueType", issueType);
                        activity.addMetadata("status", status);

                        // Add default project if configured
                        if (instance.defaultProject != null) {
                            activity.addMetadata("defaultProject", instance.defaultProject);
                        }

                        activities.add(activity);
                    }
                } catch (Exception e) {
                    Log.tracef("Failed to fetch details for issue %s: %s", key, e.getMessage());
                }
            }
        }

        Log.infof("Found %d activities from JIRA instance: %s", activities.size(), instance.name);

        return activities;
    }

    /**
     * Extract external URLs (GitHub PRs, GitLab MRs) from JIRA issue.
     * Uses shared UrlExtractor for consistency with other providers.
     */
    private void extractExternalUrls(JsonNode issue, Set<String> urls, UrlExtractor urlExtractor) {
        try {
            // Check rendered fields for external links in description
            var renderedFields = issue.get("renderedFields");
            if (renderedFields != null && renderedFields.get("description") != null) {
                String description = renderedFields.get("description").asText();
                urlExtractor.extractExternalUrls(description, urls);
            }
        } catch (Exception e) {
            Log.tracef("Failed to extract external URLs from JIRA issue: %s", e.getMessage());
        }
    }
}
