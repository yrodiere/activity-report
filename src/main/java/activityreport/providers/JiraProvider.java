package activityreport.providers;

import activityreport.client.BasicAuthRequestFilter;
import activityreport.client.JiraRestClient;
import activityreport.client.JiraTenantClient;
import activityreport.client.TraceClientLogger;
import activityreport.util.UrlExtractor;
import org.jboss.resteasy.reactive.client.api.LoggingScope;
import activityreport.config.AppConfig;
import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import activityreport.util.ProgressLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

public class JiraProvider implements ActivityProvider {
    private final List<JiraInstance> instances;

    private static final String ATLASSIAN_API_GATEWAY = "https://api.atlassian.com/ex/jira/";

    private static final DateTimeFormatter JIRA_TIMESTAMP = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            .appendOffset("+HHmm", "Z")
            .toFormatter();

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

                    List<String> projectKeys = instance.projectKeys().orElse(List.of());
                    urlExtractor.registerJiraInstance(instance.url(), instance.name(), projectKeys);
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

    private Optional<String> resolveCloudId(String instanceUrl) {
        try {
            var tenantClient = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(instanceUrl))
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .loggingScope(LoggingScope.REQUEST_RESPONSE)
                    .clientLogger(new TraceClientLogger())
                    .build(JiraTenantClient.class);
            var tenantInfo = tenantClient.tenantInfo();
            var cloudIdNode = tenantInfo.get("cloudId");
            if (cloudIdNode != null) {
                return Optional.of(cloudIdNode.asText());
            }
        } catch (Exception e) {
            Log.debugf("Could not resolve cloudId for %s: %s", instanceUrl, e.getMessage());
        }
        return Optional.empty();
    }

    private List<Activity> fetchFromInstance(JiraInstance instance, Instant startDate, Instant endDate, UrlExtractor urlExtractor) throws Exception {
        List<Activity> activities = new ArrayList<>();

        ProgressLog.section("%s", instance.name);

        ProgressLog.indented(() -> {
            URI apiBaseUri = resolveApiBaseUri(instance.url);

            var client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(apiBaseUri)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .register(new BasicAuthRequestFilter(instance.email, instance.token))
                .loggingScope(LoggingScope.REQUEST_RESPONSE)
                .clientLogger(new TraceClientLogger())
                .build(JiraRestClient.class);

            String currentAccountId = client.myself().get("accountId").asText();

            var issues = searchIssues(client, currentAccountId, startDate);

            for (JsonNode issue : issues) {
                try {
                    var activity = toActivity(issue, instance, currentAccountId, startDate, endDate, urlExtractor);
                    if (activity != null) {
                        activities.add(activity);
                    }
                } catch (Exception e) {
                    Log.tracef("Failed to process issue %s: %s", issue.path("key").asText(), e.getMessage());
                }
            }
        });

        ProgressLog.result("Found %d activities", activities.size());

        return activities;
    }

    private URI resolveApiBaseUri(String instanceUrl) {
        var cloudId = resolveCloudId(instanceUrl);
        if (cloudId.isPresent()) {
            ProgressLog.detail("Using Atlassian Cloud API gateway");
            return URI.create(ATLASSIAN_API_GATEWAY + cloudId.get());
        }
        ProgressLog.detail("Using direct instance URL");
        return URI.create(instanceUrl);
    }

    private List<JsonNode> searchIssues(JiraRestClient client, String currentAccountId, Instant startDate) {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());
        var jql = String.format(
                "issue in updatedBy(\"%s\", \"%s\") ORDER BY updated DESC",
                currentAccountId, dateFormatter.format(startDate));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode request = mapper.createObjectNode();
        request.put("jql", jql);
        request.putArray("fields")
                .add("key").add("summary").add("status").add("updated")
                .add("issuetype").add("comment").add("assignee");
        request.put("maxResults", 100);
        request.put("expand", "changelog,renderedFields");

        var root = client.search(request);
        var issuesNode = root.get("issues");

        List<JsonNode> issues = new ArrayList<>();
        if (issuesNode != null && issuesNode.isArray()) {
            for (JsonNode issue : issuesNode) {
                issues.add(issue);
            }
        }
        return issues;
    }

    private Activity toActivity(JsonNode issue, JiraInstance instance, String currentAccountId,
                                Instant startDate, Instant endDate, UrlExtractor urlExtractor) {
        String key = issue.get("key").asText();
        var fields = issue.get("fields");
        String summary = fields.get("summary").asText();
        String issueType = fields.get("issuetype").get("name").asText();
        String status = fields.get("status").get("name").asText();
        String issueUrl = instance.url + "/browse/" + key;

        Instant latestUserActivity = findLatestUserActivity(issue, currentAccountId, startDate, endDate);
        if (latestUserActivity == null) {
            return null;
        }

        boolean isAssignee = isAssignedTo(fields, currentAccountId);
        boolean isInProgress = "indeterminate".equals(
                fields.get("status").path("statusCategory").path("key").asText(""));

        ActionCategory actionCategory =
                isAssignee && isInProgress
                        ? ActionCategory.CODE : ActionCategory.DISCUSS;

        List<String> contentUrls = extractContentUrls(issue, currentAccountId, startDate, endDate, urlExtractor);

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
        if (instance.defaultProject != null) {
            activity.addMetadata("defaultProject", instance.defaultProject);
        }

        return activity;
    }

    private List<String> extractContentUrls(JsonNode issue, String currentAccountId,
                                             Instant startDate, Instant endDate,
                                             UrlExtractor urlExtractor) {
        Set<String> urls = new LinkedHashSet<>();
        var fields = issue.get("fields");
        var renderedFields = issue.get("renderedFields");

        // Extract from title
        urlExtractor.extractExternalUrls(fields.get("summary").asText(), urls);

        // Extract from rendered description (HTML)
        if (renderedFields != null && renderedFields.has("description") && !renderedFields.get("description").isNull()) {
            urlExtractor.extractExternalUrls(renderedFields.get("description").asText(), urls);
        }

        // Extract from rendered comments by the current user in the time range
        // (raw comment bodies are ADF JSON; rendered ones are HTML)
        var renderedComments = renderedFields != null
                ? renderedFields.path("comment").path("comments") : null;
        var rawComments = fields.path("comment").path("comments");
        if (renderedComments != null && renderedComments.isArray() && rawComments.isArray()) {
            for (int i = 0; i < rawComments.size() && i < renderedComments.size(); i++) {
                var rawComment = rawComments.get(i);
                Instant commentDate = parseJiraTimestamp(rawComment.get("created").asText());
                if (commentDate.isBefore(startDate) || commentDate.isAfter(endDate)) {
                    continue;
                }
                var author = rawComment.get("author");
                if (author != null && currentAccountId.equals(author.path("accountId").asText(null))) {
                    String renderedBody = renderedComments.get(i).path("body").asText(null);
                    if (renderedBody != null) {
                        urlExtractor.extractExternalUrls(renderedBody, urls);
                    }
                }
            }
        }

        return new ArrayList<>(urls);
    }

    private Instant findLatestUserActivity(JsonNode issue, String currentAccountId,
                                           Instant startDate, Instant endDate) {
        Instant latest = null;
        latest = findLatestInChangelog(issue.get("changelog"), currentAccountId, startDate, endDate, latest);
        latest = findLatestInComments(issue.get("fields").get("comment"), currentAccountId, startDate, endDate, latest);
        return latest;
    }

    private Instant findLatestInChangelog(JsonNode changelog, String currentAccountId,
                                          Instant startDate, Instant endDate, Instant latest) {
        if (changelog == null || changelog.get("histories") == null) {
            return latest;
        }
        for (JsonNode history : changelog.get("histories")) {
            Instant changeDate = parseJiraTimestamp(history.get("created").asText());
            if (changeDate.isBefore(startDate) || changeDate.isAfter(endDate)) {
                continue;
            }
            var author = history.get("author");
            if (author != null && currentAccountId.equals(author.path("accountId").asText(null))) {
                if (latest == null || changeDate.isAfter(latest)) {
                    latest = changeDate;
                }
            }
        }
        return latest;
    }

    private Instant findLatestInComments(JsonNode commentField, String currentAccountId,
                                          Instant startDate, Instant endDate, Instant latest) {
        if (commentField == null || commentField.get("comments") == null) {
            return latest;
        }
        for (JsonNode comment : commentField.get("comments")) {
            Instant commentDate = parseJiraTimestamp(comment.get("created").asText());
            if (commentDate.isBefore(startDate) || commentDate.isAfter(endDate)) {
                continue;
            }
            var author = comment.get("author");
            if (author != null && currentAccountId.equals(author.path("accountId").asText(null))) {
                if (latest == null || commentDate.isAfter(latest)) {
                    latest = commentDate;
                }
            }
        }
        return latest;
    }

    private static boolean isAssignedTo(JsonNode fields, String accountId) {
        var assignee = fields.get("assignee");
        return assignee != null && !assignee.isNull()
                && accountId.equals(assignee.path("accountId").asText(null));
    }

    private static Instant parseJiraTimestamp(String timestamp) {
        return OffsetDateTime.parse(timestamp, JIRA_TIMESTAMP).toInstant();
    }
}
