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

    private record InstanceInfo(String name, String publicEventsToken, String defaultProject) {}

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
                            instance.publicEventsToken().orElse(null),
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
                String currentLogin = currentUser.getLogin();

                // Step 1: Use events API to discover issues/PRs
                Map<IssueRef, Instant> issueRefs = new HashMap<>();
                Map<IssueRef, Instant> prRefs = new HashMap<>();

                // Process authenticated events (filtered by token scope)
                Log.tracef("Fetching authenticated events for user %s", currentLogin);
                PagedIterable<GHEventInfo> authenticatedEvents = currentUser.listEvents();
                processEvents("authenticated", authenticatedEvents, issueRefs, prRefs, startDate, endDate);

                // Process public events (unfiltered, to work around fine-grained token limitations)
                // Only if publicEventsToken is configured to avoid rate limiting issues
                if (instanceInfo.publicEventsToken != null) {
                    try {
                        Log.tracef("Fetching public events for user %s (using publicEventsToken)", currentLogin);
                        GitHub publicGitHub = new GitHubBuilder().withOAuthToken(instanceInfo.publicEventsToken).build();
                        List<GHEventInfo> publicEventsList = publicGitHub.getUserPublicEvents(currentLogin);
                        processEvents("public", publicEventsList, issueRefs, prRefs, startDate, endDate);
                    } catch (org.kohsuke.github.HttpException e) {
                        // Check for rate limit error (403 or 429 status codes)
                        int responseCode = e.getResponseCode();
                        String message = e.getMessage();
                        if (responseCode == 403 || responseCode == 429 ||
                            (message != null && message.toLowerCase().contains("rate limit"))) {
                            Log.warnf("Cannot use public events API with configured publicEventsToken due to rate limits. " +
                                "Some activities from organizations not accessible by your main token may be missing.");
                        } else {
                            Log.warnf("Failed to fetch public events: %s", message);
                        }
                    } catch (IOException e) {
                        Log.warnf("Failed to fetch public events: %s", e.getMessage());
                    }
                } else {
                    Log.debugf("Skipping public events API (no publicEventsToken configured). " +
                        "If using a fine-grained token, some activities from organizations not accessible by your token may be missing.");
                }

                Log.tracef("After processing all events: Found %d issues, %d PRs", issueRefs.size(), prRefs.size());

                // Step 2: Fetch full details for each unique issue/PR
                int beforeCount = allActivities.size();
                allActivities.addAll(fetchIssueDetails(github, instanceInfo, issueRefs, startDate, endDate));
                allActivities.addAll(fetchPullRequestDetails(github, instanceInfo, currentLogin, prRefs, startDate, endDate));
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

    private void processEvents(String eventSource, Iterable<GHEventInfo> events,
                               Map<IssueRef, Instant> issueRefs, Map<IssueRef, Instant> prRefs,
                               Instant startDate, Instant endDate) {
        int eventCount = 0;
        int beforeIssues = issueRefs.size();
        int beforePRs = prRefs.size();
        Instant earliestEventTimestamp = null;

        for (GHEventInfo event : events) {
            try {
                Date eventDate = event.getCreatedAt();
                Instant eventTimestamp = eventDate.toInstant();
                eventCount++;

                // Track earliest (oldest) timestamp
                if (earliestEventTimestamp == null || eventTimestamp.isBefore(earliestEventTimestamp)) {
                    earliestEventTimestamp = eventTimestamp;
                }

                Log.tracef("[%s] Event #%d: id=%s, type=%s, timestamp=%s",
                    eventSource, eventCount, event.getId(), event.getType(), eventTimestamp);

                // Stop if event is before start date (events are in reverse chronological order)
                if (eventTimestamp.isBefore(startDate)) {
                    Log.tracef("[%s] Breaking at event #%d (timestamp %s before start date %s)",
                        eventSource, eventCount, eventTimestamp, startDate);
                    break;
                }

                // Skip if event is after end date
                if (eventTimestamp.isAfter(endDate)) {
                    continue;
                }

                // Extract issue/PR references from events
                extractReferences(event, eventTimestamp, issueRefs, prRefs);
            } catch (Exception e) {
                Log.tracef("Failed to process event: %s", e.getMessage());
            }
        }

        int newIssues = issueRefs.size() - beforeIssues;
        int newPRs = prRefs.size() - beforePRs;
        String earliestEventInfo = earliestEventTimestamp != null ? ", earliest event: " + earliestEventTimestamp : "";
        Log.infof("[%s] Processed %d events%s. Found %d new issues, %d new PRs",
            eventSource, eventCount, earliestEventInfo, newIssues, newPRs);
    }

    private void extractReferences(GHEventInfo event, Instant eventTimestamp, Map<IssueRef, Instant> issueRefs, Map<IssueRef, Instant> prRefs) throws IOException {
        // Get repository name from event, not from issue/PR objects
        // This avoids triggering API calls with the wrong token
        GHRepository eventRepo = event.getRepository();
        if (eventRepo == null) {
            return;
        }
        String repoFullName = eventRepo.getFullName();

        switch (event.getType()) {
            case ISSUES -> {
                var payload = event.getPayload(GHEventPayload.Issue.class);
                if (payload != null && payload.getIssue() != null) {
                    var issue = payload.getIssue();
                    IssueRef ref = new IssueRef(repoFullName, issue.getNumber());
                    Instant previous = issueRefs.get(ref);
                    issueRefs.merge(ref, eventTimestamp, (existing, newTime) ->
                        newTime.isAfter(existing) ? newTime : existing);
                    if (previous == null) {
                        Log.tracef("  -> Found issue: %s#%d", ref.repoFullName, ref.number);
                    } else if (eventTimestamp.isAfter(previous)) {
                        Log.tracef("  -> Updated issue timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, previous, eventTimestamp);
                    }
                }
            }
            case ISSUE_COMMENT -> {
                var payload = event.getPayload(GHEventPayload.IssueComment.class);
                if (payload != null && payload.getIssue() != null) {
                    var issue = payload.getIssue();
                    IssueRef ref = new IssueRef(repoFullName, issue.getNumber());
                    if (issue.isPullRequest()) {
                        Instant previous = prRefs.get(ref);
                        prRefs.merge(ref, eventTimestamp, (existing, newTime) ->
                            newTime.isAfter(existing) ? newTime : existing);
                        if (previous == null) {
                            Log.tracef("  -> Found PR (from comment): %s#%d", ref.repoFullName, ref.number);
                        } else if (eventTimestamp.isAfter(previous)) {
                            Log.tracef("  -> Updated PR timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, previous, eventTimestamp);
                        }
                    } else {
                        Instant previous = issueRefs.get(ref);
                        issueRefs.merge(ref, eventTimestamp, (existing, newTime) ->
                            newTime.isAfter(existing) ? newTime : existing);
                        if (previous == null) {
                            Log.tracef("  -> Found issue (from comment): %s#%d", ref.repoFullName, ref.number);
                        } else if (eventTimestamp.isAfter(previous)) {
                            Log.tracef("  -> Updated issue timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, previous, eventTimestamp);
                        }
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
                    IssueRef ref = new IssueRef(repoFullName, pr.getNumber());
                    Instant previous = prRefs.get(ref);
                    prRefs.merge(ref, eventTimestamp, (existing, newTime) ->
                        newTime.isAfter(existing) ? newTime : existing);
                    if (previous == null) {
                        Log.tracef("  -> Found PR: %s#%d", ref.repoFullName, ref.number);
                    } else if (eventTimestamp.isAfter(previous)) {
                        Log.tracef("  -> Updated PR timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, previous, eventTimestamp);
                    }
                }
            }
        }
    }

    private List<Activity> fetchIssueDetails(GitHub github, InstanceInfo instanceInfo, Map<IssueRef, Instant> issueRefs, Instant startDate, Instant endDate) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (Map.Entry<IssueRef, Instant> entry : issueRefs.entrySet()) {
            IssueRef ref = entry.getKey();
            Instant eventTimestamp = entry.getValue();
            try {
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHIssue issue = repo.getIssue(ref.number);

                // Collect all relevant links within date range
                List<String> contentUrls = new ArrayList<>();

                // Add comment links
                for (GHIssueComment comment : issue.getComments()) {
                    Instant commentDate = comment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        contentUrls.add(comment.getHtmlUrl().toString());
                    }
                }

                // Create activity - use event timestamp that discovered this issue
                // Issues are always DISCUSS
                Activity activity = new Activity(
                    source,
                    "issue",
                    ActionCategory.DISCUSS,
                    ref.repoFullName + "#" + ref.number + ": " + issue.getTitle(),
                    "", // description
                    issue.getHtmlUrl().toString(),
                    eventTimestamp,
                    contentUrls
                );

                // Add default project if configured
                if (instanceInfo.defaultProject != null) {
                    activity.addMetadata("defaultProject", instanceInfo.defaultProject);
                }

                activities.add(activity);
            } catch (Exception e) {
                Log.tracef("Failed to fetch issue %s#%d: %s", ref.repoFullName, ref.number, e.getMessage());
            }
        }

        return activities;
    }

    private List<Activity> fetchPullRequestDetails(GitHub github, InstanceInfo instanceInfo, String currentLogin, Map<IssueRef, Instant> prRefs, Instant startDate, Instant endDate) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (Map.Entry<IssueRef, Instant> entry : prRefs.entrySet()) {
            IssueRef ref = entry.getKey();
            Instant eventTimestamp = entry.getValue();
            try {
                Log.tracef("Fetching PR details: %s#%d", ref.repoFullName, ref.number);
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHPullRequest pr = repo.getPullRequest(ref.number);

                Log.tracef("  PR %s#%d: eventTimestamp=%s", ref.repoFullName, ref.number, eventTimestamp);

                // Determine action category based on PR authorship
                ActionCategory actionCategory;
                try {
                    boolean isAuthor = pr.getUser().getLogin().equals(currentLogin);
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

                Log.tracef("  PR %s#%d: found %d content URLs in date range", ref.repoFullName, ref.number, contentUrls.size());

                // Create activity - use event timestamp that discovered this PR
                Log.tracef("  PR %s#%d: Creating activity (contentUrls=%d)", ref.repoFullName, ref.number, contentUrls.size());
                Activity activity = new Activity(
                    source,
                    "pull_request",
                    actionCategory,
                    ref.repoFullName + "#" + ref.number + ": " + pr.getTitle(),
                    "", // description
                    pr.getHtmlUrl().toString(),
                    eventTimestamp,
                    contentUrls
                );

                // Add default project if configured
                if (instanceInfo.defaultProject != null) {
                    activity.addMetadata("defaultProject", instanceInfo.defaultProject);
                }

                activities.add(activity);
            } catch (Exception e) {
                Log.tracef("Failed to fetch pull request %s#%d: %s", ref.repoFullName, ref.number, e.getMessage());
            }
        }

        return activities;
    }
}
