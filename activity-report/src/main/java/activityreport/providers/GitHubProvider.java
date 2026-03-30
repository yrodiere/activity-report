package activityreport.providers;

import activityreport.config.AppConfig;
import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import io.quarkus.logging.Log;
import org.kohsuke.github.*;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * GitHub Activity Provider supporting multiple instances (GitHub.com and GitHub Enterprise)
 */
public class GitHubProvider implements ActivityProvider {
    private final List<GitHub> githubClients;
    private final List<InstanceInfo> instanceInfos;

    private record InstanceInfo(String name, String defaultProject) {}

    public GitHubProvider(AppConfig config) {
        this.githubClients = new ArrayList<>();
        this.instanceInfos = new ArrayList<>();

        config.providers().github().ifPresent(github -> {
            if (github.enabled() && github.instances() != null) {
                for (var instance : github.instances()) {
                    try {
                        GitHub client;
                        GitHubBuilder builder = new GitHubBuilder();

                        // Set endpoint for GitHub Enterprise
                        if (instance.url().isPresent() && !instance.url().get().equals("https://api.github.com")) {
                            builder = builder.withEndpoint(instance.url().get());
                        }

                        // Use token from config if provided, otherwise fall back to ~/.github property file
                        if (instance.token().isPresent()) {
                            builder = builder.withOAuthToken(instance.token().get());
                        } else {
                            // Read from ~/.github property file
                            builder = builder.fromPropertyFile();
                        }

                        client = builder.build();
                        githubClients.add(client);
                        instanceInfos.add(new InstanceInfo(
                            instance.name(),
                            instance.defaultProject().orElse(null)
                        ));
                    } catch (IOException e) {
                        Log.warnf("Failed to initialize GitHub instance %s: %s", instance.name(), e.getMessage());
                    }
                }
            }
        });
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
            InstanceInfo instanceInfo = instanceInfos.get(i);
            String instanceName = instanceInfo.name;

            try {
                Log.infof("Fetching activities from GitHub instance: %s", instanceName);

                GHUser currentUser = github.getMyself();

                // Step 1: Use events API to discover issues/PRs
                Set<IssueRef> issueRefs = new HashSet<>();
                Set<IssueRef> prRefs = new HashSet<>();

                PagedIterable<GHEventInfo> events = currentUser.listEvents();
                for (GHEventInfo event : events) {
                    try {
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

                        // Extract issue/PR references from events
                        extractReferences(event, issueRefs, prRefs);
                    } catch (Exception e) {
                        Log.tracef("Failed to process event: %s", e.getMessage());
                    }
                }

                // Step 2: Fetch full details for each unique issue/PR
                int beforeCount = allActivities.size();
                allActivities.addAll(fetchIssueDetails(github, instanceInfo, currentUser, issueRefs, startDate, endDate));
                allActivities.addAll(fetchPullRequestDetails(github, instanceInfo, currentUser, prRefs, startDate, endDate));
                int foundCount = allActivities.size() - beforeCount;

                Log.infof("Found %d activities from GitHub instance: %s", foundCount, instanceName);

            } catch (Exception e) {
                Log.warnf("Error fetching from %s: %s", instanceName, e.getMessage());
            }
        }

        // Deduplicate activities by URL (multiple instances may see the same issue/PR)
        allActivities = deduplicateActivities(allActivities);

        return allActivities;
    }

    /**
     * Deduplicate activities by URL, merging content URLs from duplicates.
     * When multiple instances report the same issue/PR, we keep one and merge all content URLs.
     */
    private List<Activity> deduplicateActivities(List<Activity> activities) {
        Map<String, List<Activity>> byUrl = new LinkedHashMap<>();

        // Group by URL
        for (Activity activity : activities) {
            String url = activity.url();
            if (url != null && !url.isEmpty()) {
                byUrl.computeIfAbsent(url, k -> new ArrayList<>()).add(activity);
            }
        }

        List<Activity> deduplicated = new ArrayList<>();
        int duplicatesRemoved = 0;

        // Merge duplicates
        for (List<Activity> group : byUrl.values()) {
            if (group.size() == 1) {
                deduplicated.add(group.get(0));
            } else {
                // Multiple instances reported the same activity - merge them
                duplicatesRemoved += group.size() - 1;

                Activity first = group.get(0);

                // Collect all unique content URLs from all duplicates
                Set<String> mergedContentUrls = new LinkedHashSet<>();
                for (Activity activity : group) {
                    if (activity.contentUrls() != null) {
                        mergedContentUrls.addAll(activity.contentUrls());
                    }
                }

                // Create merged activity (keep first's metadata, but merge content URLs)
                Activity merged = new Activity(
                    first.source(),
                    first.action(),
                    first.actionCategory(),
                    first.title(),
                    first.description(),
                    first.url(),
                    first.timestamp(),
                    new ArrayList<>(mergedContentUrls),
                    first.project(),
                    first.metadata()
                );

                deduplicated.add(merged);
            }
        }

        if (duplicatesRemoved > 0) {
            Log.infof("Removed %d duplicate activities from multiple GitHub instances", duplicatesRemoved);
        }

        return deduplicated;
    }

    private record IssueRef(String repoFullName, int number) {}

    private void extractReferences(GHEventInfo event, Set<IssueRef> issueRefs, Set<IssueRef> prRefs) throws IOException {
        switch (event.getType()) {
            case ISSUES -> {
                var payload = event.getPayload(GHEventPayload.Issue.class);
                if (payload != null && payload.getIssue() != null) {
                    var issue = payload.getIssue();
                    issueRefs.add(new IssueRef(issue.getRepository().getFullName(), issue.getNumber()));
                }
            }
            case ISSUE_COMMENT -> {
                var payload = event.getPayload(GHEventPayload.IssueComment.class);
                if (payload != null && payload.getIssue() != null) {
                    var issue = payload.getIssue();
                    if (issue.isPullRequest()) {
                        prRefs.add(new IssueRef(issue.getRepository().getFullName(), issue.getNumber()));
                    } else {
                        issueRefs.add(new IssueRef(issue.getRepository().getFullName(), issue.getNumber()));
                    }
                }
            }
            case PULL_REQUEST, PULL_REQUEST_REVIEW, PULL_REQUEST_REVIEW_COMMENT -> {
                GHPullRequest pr = null;
                if (event.getType() == GHEvent.PULL_REQUEST) {
                    var payload = event.getPayload(GHEventPayload.PullRequest.class);
                    if (payload != null) pr = payload.getPullRequest();
                } else if (event.getType() == GHEvent.PULL_REQUEST_REVIEW) {
                    var payload = event.getPayload(GHEventPayload.PullRequestReview.class);
                    if (payload != null) pr = payload.getPullRequest();
                } else if (event.getType() == GHEvent.PULL_REQUEST_REVIEW_COMMENT) {
                    var payload = event.getPayload(GHEventPayload.PullRequestReviewComment.class);
                    if (payload != null) pr = payload.getPullRequest();
                }
                if (pr != null) {
                    prRefs.add(new IssueRef(pr.getRepository().getFullName(), pr.getNumber()));
                }
            }
        }
    }

    private List<Activity> fetchIssueDetails(GitHub github, InstanceInfo instanceInfo, GHUser currentUser, Set<IssueRef> issueRefs, Instant startDate, Instant endDate) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (IssueRef ref : issueRefs) {
            try {
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHIssue issue = repo.getIssue(ref.number);

                Instant updatedAt = issue.getUpdatedAt().toInstant();

                // Collect all relevant links within date range
                List<String> contentUrls = new ArrayList<>();

                // Add comment links
                for (GHIssueComment comment : issue.getComments()) {
                    Instant commentDate = comment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        contentUrls.add(comment.getHtmlUrl().toString());
                    }
                }

                // Only create activity if there were interactions in the date range
                if (!contentUrls.isEmpty() || (!updatedAt.isBefore(startDate) && !updatedAt.isAfter(endDate))) {
                    // Issues are always DISCUSS
                    Activity activity = new Activity(
                        source,
                        "issue",
                        ActionCategory.DISCUSS,
                        ref.repoFullName + "#" + ref.number + ": " + issue.getTitle(),
                        "", // description
                        issue.getHtmlUrl().toString(),
                        updatedAt,
                        contentUrls
                    );

                    // Add default project if configured
                    if (instanceInfo.defaultProject != null) {
                        activity.addMetadata("defaultProject", instanceInfo.defaultProject);
                    }

                    activities.add(activity);
                }
            } catch (Exception e) {
                Log.tracef("Failed to fetch issue %s#%d: %s", ref.repoFullName, ref.number, e.getMessage());
            }
        }

        return activities;
    }

    private List<Activity> fetchPullRequestDetails(GitHub github, InstanceInfo instanceInfo, GHUser currentUser, Set<IssueRef> prRefs, Instant startDate, Instant endDate) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (IssueRef ref : prRefs) {
            try {
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHPullRequest pr = repo.getPullRequest(ref.number);

                Instant updatedAt = pr.getUpdatedAt().toInstant();

                // Determine action category based on PR authorship
                ActionCategory actionCategory;
                try {
                    boolean isAuthor = pr.getUser().getLogin().equals(currentUser.getLogin());
                    actionCategory = isAuthor ? ActionCategory.CODE : ActionCategory.REVIEW;
                } catch (Exception e) {
                    Log.tracef("Failed to determine PR author for %s#%d, defaulting to review: %s", ref.repoFullName, ref.number, e.getMessage());
                    actionCategory = ActionCategory.REVIEW;
                }

                // Collect all relevant links within date range
                List<String> contentUrls = new ArrayList<>();

                // Add comment links
                for (GHIssueComment comment : pr.getComments()) {
                    Instant commentDate = comment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        contentUrls.add(comment.getHtmlUrl().toString());
                    }
                }

                // Add review links
                for (GHPullRequestReview review : pr.listReviews()) {
                    Instant reviewDate = review.getSubmittedAt().toInstant();
                    if (!reviewDate.isBefore(startDate) && !reviewDate.isAfter(endDate)) {
                        contentUrls.add(review.getHtmlUrl().toString());
                    }
                }

                // Add review comment links
                for (GHPullRequestReviewComment reviewComment : pr.listReviewComments()) {
                    Instant commentDate = reviewComment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        contentUrls.add(reviewComment.getHtmlUrl().toString());
                    }
                }

                // Only create activity if there were interactions in the date range
                if (!contentUrls.isEmpty() || (!updatedAt.isBefore(startDate) && !updatedAt.isAfter(endDate))) {
                    Activity activity = new Activity(
                        source,
                        "pull_request",
                        actionCategory,
                        ref.repoFullName + " #" + ref.number + ": " + pr.getTitle(),
                        "", // description
                        pr.getHtmlUrl().toString(),
                        updatedAt,
                        contentUrls
                    );

                    // Add default project if configured
                    if (instanceInfo.defaultProject != null) {
                        activity.addMetadata("defaultProject", instanceInfo.defaultProject);
                    }

                    activities.add(activity);
                }
            } catch (Exception e) {
                Log.tracef("Failed to fetch pull request %s#%d: %s", ref.repoFullName, ref.number, e.getMessage());
            }
        }

        return activities;
    }
}
