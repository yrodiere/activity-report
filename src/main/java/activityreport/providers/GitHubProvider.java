package activityreport.providers;

import activityreport.config.AppConfig;
import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import activityreport.util.UrlExtractor;
import io.quarkus.logging.Log;
import org.kohsuke.github.*;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * GitHub Activity Provider supporting multiple instances (GitHub.com and GitHub Enterprise)
 */
public class GitHubProvider implements ActivityProvider {
    private final List<GitHub> githubClients;
    private final List<GitHub> publicGithubClients;
    private final List<InstanceInfo> instanceInfos;

    private record InstanceInfo(String name, String defaultProject, CategoryFilterFunction categoryFilters) {}

    private static GitHub createGitHubClient(String url, String token) throws IOException {
        GitHubBuilder builder = new GitHubBuilder();

        // Set endpoint for GitHub Enterprise
        if (url != null && !url.equals("https://api.github.com")) {
            builder = builder.withEndpoint(url);
        }

        // Use token if provided, otherwise fall back to ~/.github property file
        if (token != null) {
            builder = builder.withOAuthToken(token);
        } else {
            builder = builder.fromPropertyFile();
        }

        return builder.build();
    }

    public GitHubProvider(AppConfig config, UrlExtractor urlExtractor) {
        this.githubClients = new ArrayList<>();
        this.publicGithubClients = new ArrayList<>();
        this.instanceInfos = new ArrayList<>();

        config.providers().github().ifPresent(github -> {
            if (github.enabled() && github.instances() != null) {
                for (var instance : github.instances()) {
                    try {
                        String apiUrl = instance.url().orElse("https://api.github.com");

                        GitHub client = createGitHubClient(
                            apiUrl,
                            instance.token().orElse(null)
                        );
                        githubClients.add(client);

                        // Create public events client if token is configured
                        GitHub publicClient = null;
                        if (instance.publicEventsToken().isPresent()) {
                            try {
                                publicClient = createGitHubClient(
                                    apiUrl,
                                    instance.publicEventsToken().get()
                                );
                            } catch (IOException e) {
                                Log.warnf("Failed to initialize public events client for GitHub instance %s: %s",
                                    instance.name(), e.getMessage());
                            }
                        }
                        publicGithubClients.add(publicClient);

                        instanceInfos.add(new InstanceInfo(
                            instance.name(),
                            instance.defaultProject().orElse(null),
                            new CategoryFilterFunction(instance.categoryFilters().orElse(List.of()))
                        ));

                        // Register this instance for URL extraction
                        urlExtractor.registerGitHubInstance(apiUrl, instance.name());
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
    public List<Activity> fetchActivities(Instant startDate, Instant endDate, UrlExtractor urlExtractor) throws Exception {
        List<Activity> allActivities = new ArrayList<>();

        for (int i = 0; i < githubClients.size(); i++) {
            GitHub github = githubClients.get(i);
            GitHub publicGitHub = publicGithubClients.get(i);
            InstanceInfo instanceInfo = instanceInfos.get(i);
            String instanceName = instanceInfo.name;

            try {
                Log.infof("Fetching activities from GitHub instance: %s", instanceName);

                GHUser currentUser = github.getMyself();
                String currentLogin = currentUser.getLogin();

                // Step 1: Use events API to discover issues/PRs
                Map<IssueRef, IssueRefWithClient> issueRefs = new HashMap<>();
                Map<IssueRef, IssueRefWithClient> prRefs = new HashMap<>();

                // Process authenticated events (filtered by token scope)
                Log.tracef("Fetching authenticated events for user %s", currentLogin);
                PagedIterable<GHEventInfo> authenticatedEvents = currentUser.listEvents();
                processEvents("authenticated", github, authenticatedEvents, issueRefs, prRefs, startDate, endDate);

                // Process public events (unfiltered, to work around fine-grained token limitations)
                // Only if publicEventsToken is configured to avoid rate limiting issues
                if (publicGitHub != null) {
                    try {
                        Log.tracef("Fetching public events for user %s (using publicEventsToken)", currentLogin);
                        List<GHEventInfo> publicEventsList = publicGitHub.getUserPublicEvents(currentLogin);
                        processEvents("public", publicGitHub, publicEventsList, issueRefs, prRefs, startDate, endDate);
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
                allActivities.addAll(fetchIssueDetails(instanceInfo, issueRefs, startDate, endDate, urlExtractor));
                allActivities.addAll(fetchPullRequestDetails(instanceInfo, currentLogin, prRefs, startDate, endDate, urlExtractor));
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

    private record IssueRefWithClient(IssueRef ref, Instant timestamp, GitHub client) {}

    /**
     * Extract external URLs from a body text and add it to the set.
     */
    private void extractFromBody(String body, Set<String> externalUrls, UrlExtractor urlExtractor) {
        if (body != null) {
            urlExtractor.extractExternalUrls(body, externalUrls);
        }
    }

    /**
     * Add comment URLs within date range to contentUrls and extract external URLs from comment bodies.
     */
    private void extractFromComments(Iterable<GHIssueComment> comments, List<String> contentUrls,
                                     Set<String> externalUrls, Instant startDate, Instant endDate,
                                     UrlExtractor urlExtractor) throws IOException {
        for (GHIssueComment comment : comments) {
            Instant commentDate = comment.getCreatedAt().toInstant();
            if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                contentUrls.add(comment.getHtmlUrl().toString());
                extractFromBody(comment.getBody(), externalUrls, urlExtractor);
            }
        }
    }

    private void processEvents(String eventSource, GitHub github, Iterable<GHEventInfo> events,
                               Map<IssueRef, IssueRefWithClient> issueRefs, Map<IssueRef, IssueRefWithClient> prRefs,
                               Instant startDate, Instant endDate) {
        int eventCount = 0;
        int beforeIssues = issueRefs.size();
        int beforePRs = prRefs.size();
        Instant earliestEventTimestamp = null;
        int consecutiveIgnoredEvents = 0;
        final int MAX_CONSECUTIVE_IGNORED = 5;

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

                // Workaround for GitHub API bug: event.created_at doesn't always reflect the actual event timestamp.
                // For example, a "closed" event for a pull request may return the PR's opened date instead of when it was closed.
                // This contradicts the documentation at https://docs.github.com/en/rest/using-the-rest-api/github-event-types?apiVersion=2026-03-10#event-object-common-properties
                // No specific bug report found in GitHub community discussions as of 2026-04-20.
                // Reported as https://support.github.com/ticket/personal/0/4302866
                // To handle this, we ignore events before startDate but only stop after 5 consecutive ignored events.
                if (eventTimestamp.isBefore(startDate)) {
                    consecutiveIgnoredEvents++;
                    Log.tracef("[%s] Ignoring event #%d (timestamp %s before start date %s) - consecutive ignored: %d",
                        eventSource, eventCount, eventTimestamp, startDate, consecutiveIgnoredEvents);
                    if (consecutiveIgnoredEvents >= MAX_CONSECUTIVE_IGNORED) {
                        Log.tracef("[%s] Breaking after %d consecutive ignored events", eventSource, consecutiveIgnoredEvents);
                        break;
                    }
                    continue;
                }

                // Reset consecutive ignored counter when we find a valid event
                consecutiveIgnoredEvents = 0;

                // Skip if event is after end date
                if (eventTimestamp.isAfter(endDate)) {
                    continue;
                }

                // Extract issue/PR references from events
                extractReferences(github, event, eventTimestamp, issueRefs, prRefs);
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

    private void extractReferences(GitHub github, GHEventInfo event, Instant eventTimestamp,
                                   Map<IssueRef, IssueRefWithClient> issueRefs, Map<IssueRef, IssueRefWithClient> prRefs) throws IOException {
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
                    IssueRefWithClient existing = issueRefs.get(ref);
                    IssueRefWithClient newRefWithClient = new IssueRefWithClient(ref, eventTimestamp, github);
                    issueRefs.merge(ref, newRefWithClient, (existingRef, newRef) ->
                        newRef.timestamp.isAfter(existingRef.timestamp) ? newRef : existingRef);
                    if (existing == null) {
                        Log.tracef("  -> Found issue: %s#%d", ref.repoFullName, ref.number);
                    } else if (eventTimestamp.isAfter(existing.timestamp)) {
                        Log.tracef("  -> Updated issue timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, existing.timestamp, eventTimestamp);
                    }
                }
            }
            case ISSUE_COMMENT -> {
                var payload = event.getPayload(GHEventPayload.IssueComment.class);
                if (payload != null && payload.getIssue() != null) {
                    var issue = payload.getIssue();
                    IssueRef ref = new IssueRef(repoFullName, issue.getNumber());
                    if (issue.isPullRequest()) {
                        IssueRefWithClient existing = prRefs.get(ref);
                        IssueRefWithClient newRefWithClient = new IssueRefWithClient(ref, eventTimestamp, github);
                        prRefs.merge(ref, newRefWithClient, (existingRef, newRef) ->
                            newRef.timestamp.isAfter(existingRef.timestamp) ? newRef : existingRef);
                        if (existing == null) {
                            Log.tracef("  -> Found PR (from comment): %s#%d", ref.repoFullName, ref.number);
                        } else if (eventTimestamp.isAfter(existing.timestamp)) {
                            Log.tracef("  -> Updated PR timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, existing.timestamp, eventTimestamp);
                        }
                    } else {
                        IssueRefWithClient existing = issueRefs.get(ref);
                        IssueRefWithClient newRefWithClient = new IssueRefWithClient(ref, eventTimestamp, github);
                        issueRefs.merge(ref, newRefWithClient, (existingRef, newRef) ->
                            newRef.timestamp.isAfter(existingRef.timestamp) ? newRef : existingRef);
                        if (existing == null) {
                            Log.tracef("  -> Found issue (from comment): %s#%d", ref.repoFullName, ref.number);
                        } else if (eventTimestamp.isAfter(existing.timestamp)) {
                            Log.tracef("  -> Updated issue timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, existing.timestamp, eventTimestamp);
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
                    IssueRefWithClient existing = prRefs.get(ref);
                    IssueRefWithClient newRefWithClient = new IssueRefWithClient(ref, eventTimestamp, github);
                    prRefs.merge(ref, newRefWithClient, (existingRef, newRef) ->
                        newRef.timestamp.isAfter(existingRef.timestamp) ? newRef : existingRef);
                    if (existing == null) {
                        Log.tracef("  -> Found PR: %s#%d", ref.repoFullName, ref.number);
                    } else if (eventTimestamp.isAfter(existing.timestamp)) {
                        Log.tracef("  -> Updated PR timestamp: %s#%d (was %s, now %s)", ref.repoFullName, ref.number, existing.timestamp, eventTimestamp);
                    }
                }
            }
        }
    }

    private List<Activity> fetchIssueDetails(InstanceInfo instanceInfo, Map<IssueRef, IssueRefWithClient> issueRefs, Instant startDate, Instant endDate, UrlExtractor urlExtractor) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (IssueRefWithClient refWithClient : issueRefs.values()) {
            IssueRef ref = refWithClient.ref;
            Instant eventTimestamp = refWithClient.timestamp;
            GitHub github = refWithClient.client;
            try {
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHIssue issue = repo.getIssue(ref.number);

                // Collect all relevant links within date range
                List<String> contentUrls = new ArrayList<>();
                Set<String> externalUrls = new LinkedHashSet<>();

                // Extract external URLs from issue body
                extractFromBody(issue.getBody(), externalUrls, urlExtractor);

                // Add comment links and extract external URLs from comments
                extractFromComments(issue.getComments(), contentUrls, externalUrls, startDate, endDate, urlExtractor);

                // Add extracted external URLs to contentUrls
                contentUrls.addAll(externalUrls);

                if (!externalUrls.isEmpty()) {
                    Log.tracef("  Issue %s#%d: Extracted %d external URLs", ref.repoFullName, ref.number, externalUrls.size());
                }

                // Determine action category: check filters first, otherwise DISCUSS
                ActionCategory actionCategory = instanceInfo.categoryFilters.matchIssue(issue)
                    .orElse(ActionCategory.DISCUSS);
                if (actionCategory != ActionCategory.DISCUSS) {
                    Log.tracef("  Issue %s#%d: Categorized as %s based on filters", ref.repoFullName, ref.number, actionCategory);
                }

                // Create activity - use event timestamp that discovered this issue
                Activity activity = new Activity(
                    source,
                    "issue",
                    actionCategory,
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

    private List<Activity> fetchPullRequestDetails(InstanceInfo instanceInfo, String currentLogin, Map<IssueRef, IssueRefWithClient> prRefs, Instant startDate, Instant endDate, UrlExtractor urlExtractor) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (IssueRefWithClient refWithClient : prRefs.values()) {
            IssueRef ref = refWithClient.ref;
            Instant eventTimestamp = refWithClient.timestamp;
            GitHub github = refWithClient.client;
            try {
                Log.tracef("Fetching PR details: %s#%d", ref.repoFullName, ref.number);
                GHRepository repo = github.getRepository(ref.repoFullName);
                GHPullRequest pr = repo.getPullRequest(ref.number);

                Log.tracef("  PR %s#%d: eventTimestamp=%s", ref.repoFullName, ref.number, eventTimestamp);

                // Determine action category: check filters first, then based on PR authorship
                ActionCategory actionCategory = instanceInfo.categoryFilters.matchPullRequest(pr)
                    .orElseGet(() -> {
                        try {
                            boolean isAuthor = pr.getUser().getLogin().equals(currentLogin);
                            return isAuthor ? ActionCategory.CODE : ActionCategory.REVIEW;
                        } catch (Exception e) {
                            Log.tracef("Failed to determine PR author for %s#%d, defaulting to review: %s", ref.repoFullName, ref.number, e.getMessage());
                            return ActionCategory.REVIEW;
                        }
                    });
                if (instanceInfo.categoryFilters.matchPullRequest(pr).isPresent()) {
                    Log.tracef("  PR %s#%d: Categorized as %s based on filters", ref.repoFullName, ref.number, actionCategory);
                }

                // Collect all relevant links within date range
                List<String> contentUrls = new ArrayList<>();
                Set<String> externalUrls = new LinkedHashSet<>();

                // Extract external URLs from PR body
                extractFromBody(pr.getBody(), externalUrls, urlExtractor);

                // Add comment links and extract external URLs from comments
                extractFromComments(pr.getComments(), contentUrls, externalUrls, startDate, endDate, urlExtractor);

                // Add review links and extract external URLs from review bodies
                for (GHPullRequestReview review : pr.listReviews()) {
                    Instant reviewDate = review.getSubmittedAt().toInstant();
                    if (!reviewDate.isBefore(startDate) && !reviewDate.isAfter(endDate)) {
                        contentUrls.add(review.getHtmlUrl().toString());

                        // Extract external URLs from review body
                        String reviewBody = review.getBody();
                        if (reviewBody != null) {
                            urlExtractor.extractExternalUrls(reviewBody, externalUrls);
                        }
                    }
                }

                // Add review comment links and extract external URLs
                for (GHPullRequestReviewComment reviewComment : pr.listReviewComments()) {
                    Instant commentDate = reviewComment.getCreatedAt().toInstant();
                    if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                        contentUrls.add(reviewComment.getHtmlUrl().toString());

                        // Extract external URLs from review comment body
                        String commentBody = reviewComment.getBody();
                        if (commentBody != null) {
                            urlExtractor.extractExternalUrls(commentBody, externalUrls);
                        }
                    }
                }

                // Add extracted external URLs to contentUrls
                contentUrls.addAll(externalUrls);

                if (!externalUrls.isEmpty()) {
                    Log.tracef("  PR %s#%d: Extracted %d external URLs", ref.repoFullName, ref.number, externalUrls.size());
                }

                Log.tracef("  PR %s#%d: found %d total content URLs in date range", ref.repoFullName, ref.number, contentUrls.size());

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
