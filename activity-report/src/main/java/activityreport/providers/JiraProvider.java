package activityreport.providers;

import activityreport.client.BasicAuthRequestFilter;
import activityreport.client.JiraRestClient;
import activityreport.config.AppConfig;
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
import java.util.ArrayList;
import java.util.List;

/**
 * JIRA Activity Provider supporting multiple instances
 */
public class JiraProvider implements ActivityProvider {
    private final List<JiraInstance> instances;

    private record JiraInstance(String name, String url, String email, String token) {}

    public JiraProvider(AppConfig config) {
        this.instances = new ArrayList<>();

        config.providers().jira().ifPresent(jira -> {
            if (jira.enabled() && jira.instances() != null) {
                for (var instance : jira.instances()) {
                    instances.add(new JiraInstance(
                        instance.name(),
                        instance.url(),
                        instance.email(),
                        instance.token()
                    ));
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
    public List<Activity> fetchActivities(Instant startDate, Instant endDate) throws Exception {
        List<Activity> allActivities = new ArrayList<>();

        for (JiraInstance instance : instances) {
            try {
                allActivities.addAll(fetchFromInstance(instance, startDate, endDate));
            } catch (Exception e) {
                Log.warnf("Error fetching from JIRA instance %s: %s", instance.name, e.getMessage());
            }
        }

        return allActivities;
    }

    private List<Activity> fetchFromInstance(JiraInstance instance, Instant startDate, Instant endDate) throws Exception {
        List<Activity> activities = new ArrayList<>();

        // Build REST client for this instance
        var client = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url))
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .register(new BasicAuthRequestFilter(instance.email, instance.token))
            .build(JiraRestClient.class);

        // Build JQL query
        long daysAgo = Duration.between(startDate, Instant.now()).toDays();
        var jql = String.format("assignee = currentUser() AND updated >= -%dd ORDER BY updated DESC", daysAgo + 1);

        // Build request body for new POST /search/jql endpoint
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode request = mapper.createObjectNode();
        request.put("jql", jql);
        request.putArray("fields").add("key").add("summary").add("status").add("created").add("updated").add("issuetype");
        request.put("maxResults", 100);

        // Make API call
        var root = client.search(request);
        var issues = root.get("issues");

        if (issues != null && issues.isArray()) {
            for (JsonNode issue : issues) {
                Instant updatedTime = Instant.parse(issue.get("fields").get("updated").asText());

                // Skip if outside date range
                if (updatedTime.isBefore(startDate) || updatedTime.isAfter(endDate)) {
                    continue;
                }

                String key = issue.get("key").asText();
                String summary = issue.get("fields").get("summary").asText();
                String issueType = issue.get("fields").get("issuetype").get("name").asText();
                String status = issue.get("fields").get("status").get("name").asText();
                String issueUrl = instance.url + "/browse/" + key;

                Activity activity = new Activity(
                    "JIRA - " + instance.name,
                    "issue",
                    key + ": " + summary,
                    "Type: " + issueType + ", Status: " + status,
                    issueUrl,
                    updatedTime
                );

                activity.addMetadata("issueType", issueType);
                activity.addMetadata("status", status);
                activities.add(activity);
            }
        }

        return activities;
    }
}
