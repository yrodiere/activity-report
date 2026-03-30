package activityreport.providers;

import activityreport.config.AppConfig;
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
    private final List<String> instanceNames;

    public GitHubProvider(AppConfig config) {
        this.githubClients = new ArrayList<>();
        this.instanceNames = new ArrayList<>();

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
                        instanceNames.add(instance.name());
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
            String instanceName = instanceNames.get(i);

            try {
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
                allActivities.addAll(fetchIssueDetails(github, instanceName, issueRefs, startDate, endDate));
                allActivities.addAll(fetchPullRequestDetails(github, instanceName, prRefs, startDate, endDate));

            } catch (Exception e) {
                Log.warnf("Error fetching from %s: %s", instanceName, e.getMessage());
            }
        }

        return allActivities;
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

    private List<Activity> fetchIssueDetails(GitHub github, String instanceName, Set<IssueRef> issueRefs, Instant startDate, Instant endDate) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceName;

        for (IssueRef ref : issueRefs) {
            try {
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHIssue issue = repo.getIssue(ref.number);

                Instant updatedAt = issue.getUpdatedAt().toInstant();

                // Collect all relevant links within date range
                List<String> links = new ArrayList<>();
                links.add("Issue: " + issue.getHtmlUrl());

                // Add comment links
                for (GHIssueComment comment : issue.getComments()) {
                    Instant commentDate = comment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        links.add("Comment: " + comment.getHtmlUrl());
                    }
                }

                // Only create activity if there were interactions in the date range
                if (links.size() > 1 || (!updatedAt.isBefore(startDate) && !updatedAt.isAfter(endDate))) {
                    String description = String.join("\n", links);

                    Activity activity = new Activity(
                        source,
                        "issue",
                        ref.repoFullName + " #" + ref.number + ": " + issue.getTitle(),
                        description,
                        issue.getHtmlUrl().toString(),
                        updatedAt
                    );

                    activities.add(activity);
                }
            } catch (Exception e) {
                Log.tracef("Failed to fetch issue %s#%d: %s", ref.repoFullName, ref.number, e.getMessage());
            }
        }

        return activities;
    }

    private List<Activity> fetchPullRequestDetails(GitHub github, String instanceName, Set<IssueRef> prRefs, Instant startDate, Instant endDate) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceName;

        for (IssueRef ref : prRefs) {
            try {
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHPullRequest pr = repo.getPullRequest(ref.number);

                Instant updatedAt = pr.getUpdatedAt().toInstant();

                // Collect all relevant links within date range
                List<String> links = new ArrayList<>();
                links.add("Pull Request: " + pr.getHtmlUrl());

                // Add comment links
                for (GHIssueComment comment : pr.getComments()) {
                    Instant commentDate = comment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        links.add("Comment: " + comment.getHtmlUrl());
                    }
                }

                // Add review links
                for (GHPullRequestReview review : pr.listReviews()) {
                    Instant reviewDate = review.getSubmittedAt().toInstant();
                    if (!reviewDate.isBefore(startDate) && !reviewDate.isAfter(endDate)) {
                        links.add("Review: " + review.getHtmlUrl());
                    }
                }

                // Add review comment links
                for (GHPullRequestReviewComment reviewComment : pr.listReviewComments()) {
                    Instant commentDate = reviewComment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        links.add("Review Comment: " + reviewComment.getHtmlUrl());
                    }
                }

                // Only create activity if there were interactions in the date range
                if (links.size() > 1 || (!updatedAt.isBefore(startDate) && !updatedAt.isAfter(endDate))) {
                    String description = String.join("\n", links);

                    Activity activity = new Activity(
                        source,
                        "pull_request",
                        ref.repoFullName + " #" + ref.number + ": " + pr.getTitle(),
                        description,
                        pr.getHtmlUrl().toString(),
                        updatedAt
                    );

                    activities.add(activity);
                }
            } catch (Exception e) {
                Log.tracef("Failed to fetch pull request %s#%d: %s", ref.repoFullName, ref.number, e.getMessage());
            }
        }

        return activities;
    }
}
