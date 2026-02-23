///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS io.quarkus.platform:quarkus-bom:3.17.7@pom
//DEPS io.quarkus:quarkus-picocli
//DEPS io.quarkus:quarkus-config-yaml
//DEPS org.kohsuke:github-api:1.321
//DEPS com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2
//DEPS com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2
//JAVAC_OPTIONS -parameters
//JAVA_OPTIONS -Djava.util.logging.manager=org.jboss.logmanager.LogManager

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.kohsuke.github.*;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@QuarkusMain
public class ActivityReport implements QuarkusApplication {

    @Override
    public int run(String... args) {
        return new CommandLine(new ActivityReportCommand()).execute(args);
    }

    public static void main(String... args) {
        Quarkus.run(ActivityReport.class, args);
    }

    // ============================================================================
    // CORE DATA MODELS
    // ============================================================================

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
        Map<String, Object> metadata
    ) implements Comparable<Activity> {

        public Activity(String source, String type, String title, String description, String url, Instant timestamp) {
            this(source, type, title, description, url, timestamp, new HashMap<>());
        }

        public void addMetadata(String key, Object value) {
            this.metadata.put(key, value);
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

    /**
     * Interface for activity providers
     */
    public interface ActivityProvider {
        String getName();
        List<Activity> fetchActivities(Instant startDate, Instant endDate) throws Exception;
        boolean isConfigured();
    }

    // ============================================================================
    // CONFIGURATION MODELS
    // ============================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Config {
        @JsonProperty("providers")
        private Providers providers;

        @JsonProperty("ai")
        private AIConfig ai;

        public Providers getProviders() { return providers; }
        public void setProviders(Providers providers) { this.providers = providers; }

        public AIConfig getAi() { return ai; }
        public void setAi(AIConfig ai) { this.ai = ai; }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Providers {
            @JsonProperty("github")
            private GitHubConfig github;

            @JsonProperty("jira")
            private JiraConfig jira;

            @JsonProperty("zulip")
            private ZulipConfig zulip;

            public GitHubConfig getGithub() { return github; }
            public void setGithub(GitHubConfig github) { this.github = github; }

            public JiraConfig getJira() { return jira; }
            public void setJira(JiraConfig jira) { this.jira = jira; }

            public ZulipConfig getZulip() { return zulip; }
            public void setZulip(ZulipConfig zulip) { this.zulip = zulip; }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class GitHubConfig {
            @JsonProperty("enabled")
            private boolean enabled;

            @JsonProperty("instances")
            private List<GitHubInstance> instances;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public List<GitHubInstance> getInstances() { return instances; }
            public void setInstances(List<GitHubInstance> instances) { this.instances = instances; }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record GitHubInstance(
            @JsonProperty("name") String name,
            @JsonProperty("url") String url,
            @JsonProperty("username") String username,
            @JsonProperty("token") String token
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class JiraConfig {
            @JsonProperty("enabled")
            private boolean enabled;

            @JsonProperty("instances")
            private List<JiraInstance> instances;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public List<JiraInstance> getInstances() { return instances; }
            public void setInstances(List<JiraInstance> instances) { this.instances = instances; }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record JiraInstance(
            @JsonProperty("name") String name,
            @JsonProperty("url") String url,
            @JsonProperty("email") String email,
            @JsonProperty("token") String token
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class ZulipConfig {
            @JsonProperty("enabled")
            private boolean enabled;

            @JsonProperty("instances")
            private List<ZulipInstance> instances;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }

            public List<ZulipInstance> getInstances() { return instances; }
            public void setInstances(List<ZulipInstance> instances) { this.instances = instances; }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record ZulipInstance(
            @JsonProperty("url") String url,
            @JsonProperty("email") String email,
            @JsonProperty("api_key") String apiKey
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record AIConfig(
            @JsonProperty("url") String url,
            @JsonProperty("model") String model
        ) {}
    }

    // ============================================================================
    // CONFIGURATION LOADER
    // ============================================================================

    @ApplicationScoped
    public static class ConfigLoader {
        private static final String DEFAULT_CONFIG_PATH = System.getProperty("user.home") + "/.activity-report/config.yaml";

        public Config loadConfig() throws IOException {
            return loadConfig(DEFAULT_CONFIG_PATH);
        }

        public Config loadConfig(String configPath) throws IOException {
            java.nio.file.Path path = Paths.get(configPath);

            if (!Files.exists(path)) {
                throw new IOException("Configuration file not found: " + configPath +
                    "\nPlease create a configuration file. See config.yaml.example for reference.");
            }

            String content = Files.readString(path);

            // Expand environment variables
            content = expandEnvironmentVariables(content);

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            mapper.registerModule(new JavaTimeModule());

            Config config = mapper.readValue(content, Config.class);

            // Validate configuration
            validateConfig(config);

            return config;
        }

        private String expandEnvironmentVariables(String content) {
            // Replace ${ENV_VAR} with actual environment variable values
            var pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
            var matcher = pattern.matcher(content);

            var result = new StringBuffer();
            while (matcher.find()) {
                String envVar = matcher.group(1);
                String value = System.getenv(envVar);
                if (value == null) {
                    System.err.println("Warning: Environment variable not set: " + envVar);
                    value = "";
                }
                matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(value));
            }
            matcher.appendTail(result);

            return result.toString();
        }

        private void validateConfig(Config config) throws IOException {
            if (config == null) {
                throw new IOException("Configuration is empty");
            }

            if (config.getProviders() == null) {
                throw new IOException("No providers configured");
            }

            // Check if at least one provider is enabled
            boolean hasEnabledProvider = false;

            if (config.getProviders().getGithub() != null && config.getProviders().getGithub().isEnabled()) {
                hasEnabledProvider = true;
                if (config.getProviders().getGithub().getInstances() == null ||
                    config.getProviders().getGithub().getInstances().isEmpty()) {
                    throw new IOException("GitHub is enabled but no instances are configured");
                }
            }

            if (config.getProviders().getJira() != null && config.getProviders().getJira().isEnabled()) {
                hasEnabledProvider = true;
                if (config.getProviders().getJira().getInstances() == null ||
                    config.getProviders().getJira().getInstances().isEmpty()) {
                    throw new IOException("JIRA is enabled but no instances are configured");
                }
            }

            if (config.getProviders().getZulip() != null && config.getProviders().getZulip().isEnabled()) {
                hasEnabledProvider = true;
                if (config.getProviders().getZulip().getInstances() == null ||
                    config.getProviders().getZulip().getInstances().isEmpty()) {
                    throw new IOException("Zulip is enabled but no instances are configured");
                }
            }

            if (!hasEnabledProvider) {
                throw new IOException("No providers are enabled. Please enable at least one provider in the configuration.");
            }
        }
    }

    // ============================================================================
    // PROVIDER IMPLEMENTATIONS
    // ============================================================================

    /**
     * GitHub Activity Provider supporting multiple instances (GitHub.com and GitHub Enterprise)
     */
    public static class GitHubProvider implements ActivityProvider {
        private final List<GitHub> githubClients;
        private final List<String> instanceNames;

        public GitHubProvider(Config config) {
            this.githubClients = new ArrayList<>();
            this.instanceNames = new ArrayList<>();

            if (config.getProviders().getGithub() != null &&
                config.getProviders().getGithub().isEnabled() &&
                config.getProviders().getGithub().getInstances() != null) {

                for (Config.GitHubInstance instance : config.getProviders().getGithub().getInstances()) {
                    try {
                        GitHub client;
                        if (instance.url() != null && !instance.url().equals("https://api.github.com")) {
                            // GitHub Enterprise
                            client = new GitHubBuilder()
                                .withEndpoint(instance.url())
                                .withOAuthToken(instance.token())
                                .build();
                        } else {
                            // GitHub.com
                            client = new GitHubBuilder()
                                .withOAuthToken(instance.token())
                                .build();
                        }
                        githubClients.add(client);
                        instanceNames.add(instance.name());
                    } catch (IOException e) {
                        System.err.println("Warning: Failed to initialize GitHub instance " + instance.name() + ": " + e.getMessage());
                    }
                }
            }
        }

        @Override
        public String getName() {
            return "GitHub (all instances)";
        }

        @Override
        public boolean isConfigured() {
            return !githubClients.isEmpty();
        }

        @Override
        public List<Activity> fetchActivities(Instant startDate, Instant endDate) throws Exception {
            List<Activity> allActivities = new ArrayList<>();

            for (int i = 0; i < githubClients.size(); i++) {
                GitHub github = githubClients.get(i);
                String instanceName = instanceNames.get(i);

                try {
                    GHUser currentUser = github.getMyself();
                    PagedIterable<GHEventInfo> events = currentUser.listEvents();

                    for (GHEventInfo event : events) {
                        Date eventDate = event.getCreatedAt();
                        Instant eventTimestamp = eventDate.toInstant();

                        // Stop if event is before start date (events are in reverse chronological order)
                        if (eventTimestamp.isBefore(startDate)) {
                            break;
                        }

                        // Skip if event is after end date
                        if (eventTimestamp.isAfter(endDate)) {
                            continue;
                        }

                        // Parse different event types
                        Activity activity = parseGitHubEvent(instanceName, event);
                        if (activity != null) {
                            allActivities.add(activity);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Error fetching from " + instanceName + ": " + e.getMessage());
                }
            }

            return allActivities;
        }

        private Activity parseGitHubEvent(String instanceName, GHEventInfo event) throws IOException {
            String source = "GitHub - " + instanceName;
            Instant timestamp = event.getCreatedAt().toInstant();

            return switch (event.getType()) {
                case PUSH -> {
                    var pushPayload = event.getPayload(GHEventPayload.Push.class);
                    if (pushPayload != null && pushPayload.getCommits() != null) {
                        int commitCount = pushPayload.getCommits().size();
                        String ref = pushPayload.getRef();
                        String branch = ref != null && ref.startsWith("refs/heads/") ?
                            ref.substring("refs/heads/".length()) : ref;

                        String repoUrl = "";
                        try {
                            var repo = pushPayload.getRepository();
                            if (repo != null) {
                                repoUrl = repo.getHtmlUrl().toString();
                            }
                        } catch (Exception e) {
                            // Ignore if repo URL not available
                        }

                        yield new Activity(
                            source,
                            "push",
                            "Pushed " + commitCount + " commit" + (commitCount > 1 ? "s" : "") + " to " + branch,
                            pushPayload.getCommits().stream()
                                .map(c -> c.getMessage())
                                .collect(Collectors.joining("; ")),
                            repoUrl,
                            timestamp
                        );
                    }
                    yield null;
                }

                case PULL_REQUEST -> {
                    var prPayload = event.getPayload(GHEventPayload.PullRequest.class);
                    if (prPayload != null && prPayload.getPullRequest() != null) {
                        var pr = prPayload.getPullRequest();
                        String action = prPayload.getAction();

                        yield new Activity(
                            source,
                            "pull_request",
                            action.substring(0, 1).toUpperCase() + action.substring(1) + " PR #" + pr.getNumber() + ": " + pr.getTitle(),
                            pr.getBody() != null ? pr.getBody() : "",
                            pr.getHtmlUrl().toString(),
                            timestamp
                        );
                    }
                    yield null;
                }

                case ISSUES -> {
                    var issuePayload = event.getPayload(GHEventPayload.Issue.class);
                    if (issuePayload != null && issuePayload.getIssue() != null) {
                        var issue = issuePayload.getIssue();
                        String action = issuePayload.getAction();

                        yield new Activity(
                            source,
                            "issue",
                            action.substring(0, 1).toUpperCase() + action.substring(1) + " issue #" + issue.getNumber() + ": " + issue.getTitle(),
                            issue.getBody() != null ? issue.getBody() : "",
                            issue.getHtmlUrl().toString(),
                            timestamp
                        );
                    }
                    yield null;
                }

                case ISSUE_COMMENT -> {
                    var commentPayload = event.getPayload(GHEventPayload.IssueComment.class);
                    if (commentPayload != null && commentPayload.getComment() != null) {
                        var comment = commentPayload.getComment();
                        var commentIssue = commentPayload.getIssue();

                        yield new Activity(
                            source,
                            "comment",
                            "Commented on " + (commentIssue.isPullRequest() ? "PR" : "issue") + " #" + commentIssue.getNumber(),
                            comment.getBody() != null ? comment.getBody() : "",
                            comment.getHtmlUrl().toString(),
                            timestamp
                        );
                    }
                    yield null;
                }

                case PULL_REQUEST_REVIEW -> {
                    var reviewPayload = event.getPayload(GHEventPayload.PullRequestReview.class);
                    if (reviewPayload != null && reviewPayload.getReview() != null) {
                        var review = reviewPayload.getReview();
                        var reviewPR = reviewPayload.getPullRequest();

                        yield new Activity(
                            source,
                            "review",
                            "Reviewed PR #" + reviewPR.getNumber() + ": " + reviewPR.getTitle(),
                            review.getBody() != null ? review.getBody() : "",
                            review.getHtmlUrl().toString(),
                            timestamp
                        );
                    }
                    yield null;
                }

                case PULL_REQUEST_REVIEW_COMMENT -> {
                    var reviewCommentPayload = event.getPayload(GHEventPayload.PullRequestReviewComment.class);
                    if (reviewCommentPayload != null && reviewCommentPayload.getComment() != null) {
                        var reviewComment = reviewCommentPayload.getComment();
                        var commentPR = reviewCommentPayload.getPullRequest();

                        yield new Activity(
                            source,
                            "review_comment",
                            "Commented on PR #" + commentPR.getNumber() + " review",
                            reviewComment.getBody() != null ? reviewComment.getBody() : "",
                            reviewComment.getHtmlUrl().toString(),
                            timestamp
                        );
                    }
                    yield null;
                }

                case RELEASE -> {
                    var releasePayload = event.getPayload(GHEventPayload.Release.class);
                    if (releasePayload != null && releasePayload.getRelease() != null) {
                        var release = releasePayload.getRelease();

                        yield new Activity(
                            source,
                            "release",
                            "Published release " + release.getTagName(),
                            release.getBody() != null ? release.getBody() : "",
                            release.getHtmlUrl().toString(),
                            timestamp
                        );
                    }
                    yield null;
                }

                default -> null;
            };
        }
    }

    /**
     * JIRA Activity Provider supporting multiple instances
     */
    public static class JiraProvider implements ActivityProvider {
        private final List<JiraInstance> instances;

        private record JiraInstance(String name, String url, String email, String token) {}

        public JiraProvider(Config config) {
            this.instances = new ArrayList<>();

            if (config.getProviders().getJira() != null &&
                config.getProviders().getJira().isEnabled() &&
                config.getProviders().getJira().getInstances() != null) {

                for (Config.JiraInstance instance : config.getProviders().getJira().getInstances()) {
                    instances.add(new JiraInstance(
                        instance.name(),
                        instance.url(),
                        instance.email(),
                        instance.token()
                    ));
                }
            }
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
                    System.err.println("Warning: Error fetching from JIRA instance " + instance.name + ": " + e.getMessage());
                }
            }

            return allActivities;
        }

        private List<Activity> fetchFromInstance(JiraInstance instance, Instant startDate, Instant endDate) throws Exception {
            List<Activity> activities = new ArrayList<>();

            // Create Basic Auth header
            var auth = Base64.getEncoder().encodeToString(
                (instance.email + ":" + instance.token).getBytes()
            );

            // Build JQL query
            long daysAgo = Duration.between(startDate, Instant.now()).toDays();
            var jql = String.format("assignee = currentUser() AND updated >= -%dd ORDER BY updated DESC", daysAgo + 1);

            // Make HTTP request using java.net.http
            var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

            var url = instance.url + "/rest/api/3/search?jql=" +
                java.net.URLEncoder.encode(jql, java.nio.charset.StandardCharsets.UTF_8) +
                "&fields=key,summary,status,created,updated,issuetype&maxResults=100";

            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Authorization", "Basic " + auth)
                .header("Accept", "application/json")
                .GET()
                .build();

            var response = httpClient.send(
                request,
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IOException("JIRA API returned status " + response.statusCode() + ": " + response.body());
            }

            // Parse JSON response
            var mapper = new ObjectMapper();
            var root = mapper.readTree(response.body());
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

    /**
     * Zulip Activity Provider supporting multiple instances
     */
    public static class ZulipProvider implements ActivityProvider {
        private final List<ZulipInstance> instances;

        private record ZulipInstance(String url, String email, String apiKey) {}

        public ZulipProvider(Config config) {
            this.instances = new ArrayList<>();

            if (config.getProviders().getZulip() != null &&
                config.getProviders().getZulip().isEnabled() &&
                config.getProviders().getZulip().getInstances() != null) {

                for (Config.ZulipInstance instance : config.getProviders().getZulip().getInstances()) {
                    instances.add(new ZulipInstance(
                        instance.url(),
                        instance.email(),
                        instance.apiKey()
                    ));
                }
            }
        }

        @Override
        public String getName() {
            return "Zulip (all instances)";
        }

        @Override
        public boolean isConfigured() {
            return !instances.isEmpty();
        }

        @Override
        public List<Activity> fetchActivities(Instant startDate, Instant endDate) throws Exception {
            List<Activity> allActivities = new ArrayList<>();

            for (ZulipInstance instance : instances) {
                try {
                    allActivities.addAll(fetchFromInstance(instance, startDate, endDate));
                } catch (Exception e) {
                    System.err.println("Warning: Error fetching from Zulip instance " + instance.url + ": " + e.getMessage());
                }
            }

            return allActivities;
        }

        private List<Activity> fetchFromInstance(ZulipInstance instance, Instant startDate, Instant endDate) throws Exception {
            List<Activity> activities = new ArrayList<>();

            // Get current user ID first
            var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

            var auth = Base64.getEncoder().encodeToString(
                (instance.email + ":" + instance.apiKey).getBytes()
            );

            // Get current user
            var userRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(instance.url + "/api/v1/users/me"))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();

            var userResponse = httpClient.send(
                userRequest,
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );

            if (userResponse.statusCode() != 200) {
                throw new IOException("Zulip API returned status " + userResponse.statusCode());
            }

            var mapper = new ObjectMapper();
            var userRoot = mapper.readTree(userResponse.body());
            int userId = userRoot.get("user_id").asInt();

            // Fetch messages sent by this user
            var narrow = String.format("[{\"operator\":\"sender\",\"operand\":%d}]", userId);
            var messagesUrl = instance.url + "/api/v1/messages?anchor=newest&num_before=1000&num_after=0&narrow=" +
                java.net.URLEncoder.encode(narrow, java.nio.charset.StandardCharsets.UTF_8);

            var messagesRequest = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(messagesUrl))
                .header("Authorization", "Basic " + auth)
                .GET()
                .build();

            var messagesResponse = httpClient.send(
                messagesRequest,
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );

            if (messagesResponse.statusCode() != 200) {
                throw new IOException("Zulip messages API returned status " + messagesResponse.statusCode());
            }

            var messagesRoot = mapper.readTree(messagesResponse.body());
            var messages = messagesRoot.get("messages");

            if (messages != null && messages.isArray()) {
                for (JsonNode message : messages) {
                    long timestamp = message.get("timestamp").asLong();
                    Instant messageTime = Instant.ofEpochSecond(timestamp);

                    // Skip if outside date range
                    if (messageTime.isBefore(startDate) || messageTime.isAfter(endDate)) {
                        continue;
                    }

                    String subject = message.get("subject").asText();
                    String content = message.get("content").asText();
                    String messageType = message.get("type").asText();
                    int messageId = message.get("id").asInt();

                    // Build message URL
                    String streamName = messageType.equals("stream") ?
                        message.get("display_recipient").asText() : "private";
                    String messageUrl = instance.url + "/#narrow/stream/" + streamName + "/topic/" + subject + "/near/" + messageId;

                    Activity activity = new Activity(
                        "Zulip - " + instance.url.replace("https://", "").replace("http://", ""),
                        "message",
                        "Message in " + streamName + ": " + subject,
                        content.length() > 200 ? content.substring(0, 200) + "..." : content,
                        messageUrl,
                        messageTime
                    );

                    activity.addMetadata("messageType", messageType);
                    activities.add(activity);
                }
            }

            return activities;
        }
    }

    // ============================================================================
    // REPORT GENERATION
    // ============================================================================

    /**
     * Simple markdown report generator (fallback when AI is disabled)
     */
    public static class MarkdownReportGenerator {

        public static String generateSimple(List<Activity> activities, Instant startDate, Instant endDate) {
            StringBuilder report = new StringBuilder();

            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.systemDefault());
            DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());

            // Header
            report.append("# Activity Report\n\n");
            report.append(String.format("**Period:** %s to %s\n\n",
                dateFormatter.format(startDate),
                dateFormatter.format(endDate)));
            report.append(String.format("**Total Activities:** %d\n\n", activities.size()));

            // Sort activities by timestamp (most recent first)
            List<Activity> sortedActivities = new ArrayList<>(activities);
            sortedActivities.sort(Comparator.comparing(Activity::timestamp).reversed());

            // Group by source
            Map<String, List<Activity>> bySource = sortedActivities.stream()
                .collect(Collectors.groupingBy(Activity::source, LinkedHashMap::new, Collectors.toList()));

            // Generate report sections by source
            for (Map.Entry<String, List<Activity>> entry : bySource.entrySet()) {
                String source = entry.getKey();
                List<Activity> sourceActivities = entry.getValue();

                report.append(String.format("## %s (%d activities)\n\n", source, sourceActivities.size()));

                // Further group by type within source
                Map<String, List<Activity>> byType = sourceActivities.stream()
                    .collect(Collectors.groupingBy(Activity::type, LinkedHashMap::new, Collectors.toList()));

                for (Map.Entry<String, List<Activity>> typeEntry : byType.entrySet()) {
                    String type = typeEntry.getKey();
                    List<Activity> typeActivities = typeEntry.getValue();

                    report.append(String.format("### %s (%d)\n\n", capitalizeFirst(type), typeActivities.size()));

                    for (Activity activity : typeActivities) {
                        report.append(String.format("- **[%s]** ",
                            dateTimeFormatter.format(activity.timestamp())));

                        if (activity.url() != null && !activity.url().isEmpty()) {
                            report.append(String.format("[%s](%s)",
                                activity.title(), activity.url()));
                        } else {
                            report.append(activity.title());
                        }

                        if (activity.description() != null && !activity.description().isEmpty()) {
                            String desc = activity.description();
                            // Truncate long descriptions
                            if (desc.length() > 150) {
                                desc = desc.substring(0, 150) + "...";
                            }
                            // Escape newlines
                            desc = desc.replace("\n", " ");
                            report.append("\n  - ").append(desc);
                        }

                        report.append("\n");
                    }

                    report.append("\n");
                }
            }

            report.append("---\n\n");
            report.append("*Generated by activity-report*\n");

            return report.toString();
        }

        private static String capitalizeFirst(String str) {
            if (str == null || str.isEmpty()) {
                return str;
            }
            return str.substring(0, 1).toUpperCase() + str.substring(1).replace("_", " ");
        }
    }

    /**
     * AI Processor for generating intelligent, grouped activity reports
     */
    public static class AIProcessor {
        private final String aiUrl;
        private final String modelName;
        private final ObjectMapper mapper;

        public AIProcessor(Config config) {
            this.aiUrl = config.getAi() != null && config.getAi().url() != null ?
                config.getAi().url() : "http://localhost:8000/v1";
            this.mapper = new ObjectMapper();
            this.mapper.registerModule(new JavaTimeModule());

            // Auto-detect model if not specified
            String configuredModel = config.getAi() != null ? config.getAi().model() : null;
            if (configuredModel == null || configuredModel.equals("auto")) {
                this.modelName = detectModel();
            } else {
                this.modelName = configuredModel;
            }

            if (this.modelName != null) {
                System.err.println("Using AI model: " + this.modelName);
            }
        }

        private String detectModel() {
            try {
                var httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

                var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(aiUrl + "/models"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

                var response = httpClient.send(
                    request,
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() == 200) {
                    var root = mapper.readTree(response.body());
                    var data = root.get("data");
                    if (data != null && data.isArray() && !data.isEmpty()) {
                        var model = data.get(0).get("id").asText();
                        System.err.println("Auto-detected AI model: " + model);
                        return model;
                    }
                }
            } catch (Exception e) {
                System.err.println("Warning: Could not auto-detect AI model: " + e.getMessage());
            }
            return null;
        }

        public String generateGroupedReport(List<Activity> activities, Instant startDate, Instant endDate) {
            if (modelName == null) {
                System.err.println("AI model not available, falling back to simple markdown");
                return MarkdownReportGenerator.generateSimple(activities, startDate, endDate);
            }

            try {
                // Prepare activities as JSON
                String activitiesJson = serializeActivities(activities);

                // Build prompt
                DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")
                    .withZone(ZoneId.systemDefault());

                String prompt = """
                    You are analyzing a developer's activity from %s to %s.

                    Your task is to create a concise, achievement-oriented activity report. \
                    Group related activities into coherent achievements. For example, an issue, pull request, \
                    and commits related to the same feature should be grouped together as one achievement.

                    Guidelines:
                    - Focus on WHAT was accomplished, not just listing actions
                    - Group related items (e.g., issue + PR + commits = one achievement)
                    - Use clear, professional language
                    - Include relevant links from the activities
                    - Be concise but informative

                    Activities data (JSON):
                    %s

                    Generate a markdown report with:
                    1. A brief summary paragraph (2-3 sentences)
                    2. Main achievements grouped by theme (use ## headers)
                    3. Include links to relevant issues/PRs where available
                    4. Keep it professional and concise

                    Return ONLY the markdown report, nothing else.
                    """.formatted(
                        dateFormatter.format(startDate),
                        dateFormatter.format(endDate),
                        activitiesJson
                    );

                // Make API call
                return callAIModel(prompt);

            } catch (Exception e) {
                System.err.println("Warning: AI processing failed: " + e.getMessage());
                System.err.println("Falling back to simple markdown generation");
                return MarkdownReportGenerator.generateSimple(activities, startDate, endDate);
            }
        }

        private String serializeActivities(List<Activity> activities) throws Exception {
            // Create simplified JSON representation of activities
            List<Map<String, Object>> simplified = activities.stream()
                .sorted(Comparator.comparing(Activity::timestamp).reversed())
                .map(activity -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("source", activity.source());
                    map.put("type", activity.type());
                    map.put("title", activity.title());
                    if (activity.description() != null && !activity.description().isEmpty()) {
                        // Truncate very long descriptions
                        String desc = activity.description();
                        if (desc.length() > 500) {
                            desc = desc.substring(0, 500) + "...";
                        }
                        map.put("description", desc);
                    }
                    if (activity.url() != null && !activity.url().isEmpty()) {
                        map.put("url", activity.url());
                    }
                    map.put("timestamp", activity.timestamp().toString());
                    return map;
                })
                .collect(Collectors.toList());

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(simplified);
        }

        private String callAIModel(String prompt) throws Exception {
            var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

            // Build request body
            var requestBody = new LinkedHashMap<String, Object>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", prompt)
            ));
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);

            var requestJson = mapper.writeValueAsString(requestBody);

            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(aiUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestJson))
                .timeout(Duration.ofMinutes(2))
                .build();

            var response = httpClient.send(
                request,
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                throw new IOException("AI API returned status " + response.statusCode() + ": " + response.body());
            }

            // Parse response
            var root = mapper.readTree(response.body());
            var choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                var message = choices.get(0).get("message");
                if (message != null) {
                    return message.get("content").asText();
                }
            }

            throw new IOException("Unexpected AI API response format");
        }
    }

    // ============================================================================
    // MAIN CLI COMMAND
    // ============================================================================

    @Command(
        name = "report",
        mixinStandardHelpOptions = true,
        version = "report 1.0",
        description = "Generate activity reports from GitHub, JIRA, Zulip, and other sources"
    )
    public static class ActivityReportCommand implements Runnable {

        @Option(names = {"-c", "--config"}, description = "Path to configuration file (default: ~/.activity-report/config.yaml)")
        private String configPath;

        @Option(names = {"-d", "--days"}, description = "Number of days to look back (default: 7)")
        private int days = 7;

        @Option(names = {"--start-date"}, description = "Start date (ISO format: YYYY-MM-DD)")
        private String startDateStr;

        @Option(names = {"--end-date"}, description = "End date (ISO format: YYYY-MM-DD)")
        private String endDateStr;

        @Option(names = {"--no-ai"}, description = "Disable AI processing and use simple markdown generation")
        private boolean noAi = false;

        @Override
        public void run() {
            ConfigLoader configLoader = new ConfigLoader();
            try {
                System.err.println("Activity Report Generator");
                System.err.println("=========================\n");

                // Load configuration
                System.err.println("Loading configuration...");
                Config config = configPath != null ?
                    configLoader.loadConfig(configPath) :
                    configLoader.loadConfig();
                System.err.println("Configuration loaded successfully.\n");

                // Determine date range
                Instant startDate, endDate;
                if (startDateStr != null && endDateStr != null) {
                    startDate = LocalDate.parse(startDateStr).atStartOfDay(ZoneId.systemDefault()).toInstant();
                    endDate = LocalDate.parse(endDateStr).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
                } else {
                    endDate = Instant.now();
                    startDate = endDate.minus(Duration.ofDays(days));
                }

                DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());
                System.err.println("Fetching activities from " + formatter.format(startDate) +
                                 " to " + formatter.format(endDate) + "\n");

                // Initialize providers
                List<ActivityProvider> providers = new ArrayList<>();

                if (config.getProviders().getGithub() != null && config.getProviders().getGithub().isEnabled()) {
                    GitHubProvider githubProvider = new GitHubProvider(config);
                    if (githubProvider.isConfigured()) {
                        providers.add(githubProvider);
                    }
                }

                if (config.getProviders().getJira() != null && config.getProviders().getJira().isEnabled()) {
                    JiraProvider jiraProvider = new JiraProvider(config);
                    if (jiraProvider.isConfigured()) {
                        providers.add(jiraProvider);
                    }
                }

                if (config.getProviders().getZulip() != null && config.getProviders().getZulip().isEnabled()) {
                    ZulipProvider zulipProvider = new ZulipProvider(config);
                    if (zulipProvider.isConfigured()) {
                        providers.add(zulipProvider);
                    }
                }

                if (providers.isEmpty()) {
                    System.err.println("Error: No providers are configured and enabled.");
                    System.exit(1);
                    return;
                }

                // Fetch activities from all providers
                List<Activity> allActivities = new ArrayList<>();
                List<String> errors = new ArrayList<>();

                for (ActivityProvider provider : providers) {
                    System.err.println("Fetching from " + provider.getName() + "...");
                    try {
                        List<Activity> activities = provider.fetchActivities(startDate, endDate);
                        allActivities.addAll(activities);
                        System.err.println("  Found " + activities.size() + " activities");
                    } catch (Exception e) {
                        String errorMsg = "  Error: " + e.getMessage();
                        System.err.println(errorMsg);
                        errors.add(provider.getName() + ": " + e.getMessage());
                    }
                }

                System.err.println("\nTotal activities found: " + allActivities.size());

                if (allActivities.isEmpty()) {
                    System.out.println("# Activity Report\n");
                    System.out.println("No activities found for the specified date range.\n");
                    if (!errors.isEmpty()) {
                        System.out.println("## Errors\n");
                        for (String error : errors) {
                            System.out.println("- " + error + "\n");
                        }
                    }
                    return;
                }

                // Generate report
                System.err.println("Generating report...\n");

                String report;
                if (noAi) {
                    report = MarkdownReportGenerator.generateSimple(allActivities, startDate, endDate);
                } else {
                    AIProcessor aiProcessor = new AIProcessor(config);
                    report = aiProcessor.generateGroupedReport(allActivities, startDate, endDate);
                }

                // Output the report
                System.out.println(report);

                // Report any errors at the end
                if (!errors.isEmpty()) {
                    System.err.println("\nWarning: Some providers encountered errors:");
                    for (String error : errors) {
                        System.err.println("  - " + error);
                    }
                }

            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                e.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }
}
