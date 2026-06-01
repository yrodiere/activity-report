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
    private final List<InstanceWithClients> instances;

    private record InstanceInfo(String name, String defaultProject, CategoryFilterFunction categoryFilters) {}

    private record InstanceWithClients(InstanceInfo info, List<GitHub> clients) {}

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
        this.instances = new ArrayList<>();

        config.providers().github().ifPresent(github -> {
            if (github.enabled() && github.instances() != null) {
                for (var instance : github.instances()) {
                    try {
                        String apiUrl = instance.url().orElse("https://api.github.com");
                        List<String> tokens = instance.tokens().orElse(List.of());

                        if (tokens.isEmpty()) {
                            Log.warnf("No tokens configured for GitHub instance %s, skipping", instance.name());
                            continue;
                        }

                        List<GitHub> clients = new ArrayList<>();
                        for (String token : tokens) {
                            try {
                                GitHub client = createGitHubClient(apiUrl, token);
                                clients.add(client);
                            } catch (IOException e) {
                                Log.warnf("Failed to initialize GitHub client for instance %s with token: %s",
                                    instance.name(), e.getMessage());
                            }
                        }

                        if (clients.isEmpty()) {
                            Log.warnf("No valid clients created for GitHub instance %s, skipping", instance.name());
                            continue;
                        }

                        InstanceInfo info = new InstanceInfo(
                            instance.name(),
                            instance.defaultProject().orElse(null),
                            new CategoryFilterFunction(instance.categoryFilters().orElse(List.of()))
                        );

                        instances.add(new InstanceWithClients(info, clients));

                        // Register this instance for URL extraction
                        urlExtractor.registerGitHubInstance(apiUrl, instance.name());
                    } catch (Exception e) {
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
        return !instances.isEmpty();
    }

    @Override
    public List<Activity> fetchActivities(Instant startDate, Instant endDate, UrlExtractor urlExtractor) throws Exception {
        List<Activity> allActivities = new ArrayList<>();

        for (InstanceWithClients instanceWithClients : instances) {
            InstanceInfo instanceInfo = instanceWithClients.info;
            List<GitHub> clients = instanceWithClients.clients;
            String instanceName = instanceInfo.name;

            try {
                Log.infof("Fetching activities from GitHub instance: %s (%d token(s))", instanceName, clients.size());

                // Step 1: Use events API to discover issues/PRs across all tokens
                Map<IssueRef, IssueRefWithClients> issueRefs = new HashMap<>();
                Map<IssueRef, IssueRefWithClients> prRefs = new HashMap<>();

                for (int tokenIndex = 0; tokenIndex < clients.size(); tokenIndex++) {
                    GitHub github = clients.get(tokenIndex);
                    String tokenLabel = clients.size() > 1 ? String.format("token %d/%d", tokenIndex + 1, clients.size()) : "token";

                    try {
                        GHUser currentUser = github.getMyself();
                        String currentLogin = currentUser.getLogin();

                        // Process authenticated events (filtered by token scope)
                        Log.tracef("Fetching authenticated events for user %s (%s)", currentLogin, tokenLabel);
                        PagedIterable<GHEventInfo> authenticatedEvents = currentUser.listEvents();
                        processEvents(String.format("authenticated (%s)", tokenLabel), github, authenticatedEvents, issueRefs, prRefs, startDate, endDate);

                        // Process public events to work around fine-grained token limitations
                        try {
                            Log.tracef("Fetching public events for user %s (%s)", currentLogin, tokenLabel);
                            List<GHEventInfo> publicEventsList = github.getUserPublicEvents(currentLogin);
                            processEvents(String.format("public (%s)", tokenLabel), github, publicEventsList, issueRefs, prRefs, startDate, endDate);
                        } catch (org.kohsuke.github.HttpException e) {
                            int responseCode = e.getResponseCode();
                            String message = e.getMessage();
                            if (responseCode == 403 || responseCode == 429 ||
                                (message != null && message.toLowerCase().contains("rate limit"))) {
                                Log.debugf("Public events API rate limited for %s, continuing with authenticated events only", tokenLabel);
                            } else {
                                Log.warnf("Failed to fetch public events for %s: %s", tokenLabel, message);
                            }
                        } catch (IOException e) {
                            Log.debugf("Failed to fetch public events for %s: %s", tokenLabel, e.getMessage());
                        }
                    } catch (Exception e) {
                        Log.warnf("Error processing %s for instance %s: %s", tokenLabel, instanceName, e.getMessage());
                    }
                }

                Log.tracef("After processing all tokens: Found %d issues, %d PRs", issueRefs.size(), prRefs.size());

                // Step 2: Fetch full details for each unique issue/PR
                int beforeCount = allActivities.size();
                allActivities.addAll(fetchIssueOrPRDetails(IssueType.ISSUE, instanceInfo, issueRefs, startDate, endDate, urlExtractor));
                allActivities.addAll(fetchIssueOrPRDetails(IssueType.PULL_REQUEST, instanceInfo, prRefs, startDate, endDate, urlExtractor));
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

    private record IssueRefWithClients(IssueRef ref, Instant timestamp, List<GitHub> clients) {}

    private enum IssueType {
        ISSUE("issue"),
        PULL_REQUEST("pull_request");

        final String actionName;

        IssueType(String actionName) {
            this.actionName = actionName;
        }
    }

    /**
     * Extract external URLs from comment bodies (but don't add individual comment URLs to contentUrls).
     */
    private void extractFromComments(Iterable<GHIssueComment> comments,
                                     Set<String> externalUrls, Instant startDate, Instant endDate,
                                     UrlExtractor urlExtractor) throws IOException {
        for (GHIssueComment comment : comments) {
            Instant commentDate = comment.getCreatedAt().toInstant();
            if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                String body = comment.getBody();
                if (body != null) {
                    urlExtractor.extractExternalUrls(body, externalUrls);
                }
            }
        }
    }

    private void processEvents(String eventSource, GitHub github, Iterable<GHEventInfo> events,
                               Map<IssueRef, IssueRefWithClients> issueRefs, Map<IssueRef, IssueRefWithClients> prRefs,
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
                                   Map<IssueRef, IssueRefWithClients> issueRefs, Map<IssueRef, IssueRefWithClients> prRefs) throws IOException {
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
                    addOrUpdateRef(ref, eventTimestamp, github, issueRefs, "issue");
                }
            }
            case ISSUE_COMMENT -> {
                var payload = event.getPayload(GHEventPayload.IssueComment.class);
                if (payload != null && payload.getIssue() != null) {
                    var issue = payload.getIssue();
                    IssueRef ref = new IssueRef(repoFullName, issue.getNumber());
                    if (issue.isPullRequest()) {
                        addOrUpdateRef(ref, eventTimestamp, github, prRefs, "PR (from comment)");
                    } else {
                        addOrUpdateRef(ref, eventTimestamp, github, issueRefs, "issue (from comment)");
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
                    addOrUpdateRef(ref, eventTimestamp, github, prRefs, "PR");
                }
            }
        }
    }

    private void addOrUpdateRef(IssueRef ref, Instant eventTimestamp, GitHub github,
                                Map<IssueRef, IssueRefWithClients> refs, String typeName) {
        IssueRefWithClients existing = refs.get(ref);
        if (existing == null) {
            // New reference - create with single client
            List<GitHub> clients = new ArrayList<>();
            clients.add(github);
            refs.put(ref, new IssueRefWithClients(ref, eventTimestamp, clients));
            Log.tracef("  -> Found %s: %s#%d", typeName, ref.repoFullName, ref.number);
        } else {
            // Existing reference - update timestamp if newer and add client if not already present
            Instant newTimestamp = eventTimestamp.isAfter(existing.timestamp) ? eventTimestamp : existing.timestamp;
            List<GitHub> updatedClients = new ArrayList<>(existing.clients);
            if (!updatedClients.contains(github)) {
                updatedClients.add(github);
            }
            refs.put(ref, new IssueRefWithClients(ref, newTimestamp, updatedClients));
            if (eventTimestamp.isAfter(existing.timestamp)) {
                Log.tracef("  -> Updated %s timestamp: %s#%d (was %s, now %s)", typeName, ref.repoFullName, ref.number, existing.timestamp, eventTimestamp);
            }
            if (!existing.clients.contains(github)) {
                Log.tracef("  -> Added client for %s: %s#%d (now %d client(s))", typeName, ref.repoFullName, ref.number, updatedClients.size());
            }
        }
    }

    /**
     * Extract issue/PR references using #1234 syntax from title and body.
     * Resolves to full URLs in the context of the given repository.
     */
    private void extractHashReferences(String repoFullName, String title, String body, List<String> contentUrls) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("#(\\d+)\\b");

        if (title != null) {
            java.util.regex.Matcher matcher = pattern.matcher(title);
            while (matcher.find()) {
                int issueNumber = Integer.parseInt(matcher.group(1));
                // GitHub redirects /issues/{number} to PRs if needed
                contentUrls.add("https://github.com/" + repoFullName + "/issues/" + issueNumber);
            }
        }

        if (body != null) {
            java.util.regex.Matcher matcher = pattern.matcher(body);
            while (matcher.find()) {
                int issueNumber = Integer.parseInt(matcher.group(1));
                contentUrls.add("https://github.com/" + repoFullName + "/issues/" + issueNumber);
            }
        }
    }

    /**
     * Fetch details for issues or pull requests.
     * Unified method to avoid code duplication between issues and PRs.
     */
    private List<Activity> fetchIssueOrPRDetails(IssueType type, InstanceInfo instanceInfo,
                                                 Map<IssueRef, IssueRefWithClients> refs, Instant startDate,
                                                 Instant endDate, UrlExtractor urlExtractor) {
        List<Activity> activities = new ArrayList<>();
        String source = "GitHub - " + instanceInfo.name;

        for (IssueRefWithClients refWithClients : refs.values()) {
            IssueRef ref = refWithClients.ref;
            Instant eventTimestamp = refWithClients.timestamp;
            List<GitHub> clients = refWithClients.clients;

            // Try each client until one succeeds
            boolean fetched = false;
            Exception lastException = null;

            for (GitHub github : clients) {
                try {
                    if (type == IssueType.PULL_REQUEST) {
                        Log.tracef("Fetching PR details: %s#%d (trying client %d/%d)",
                            ref.repoFullName, ref.number, clients.indexOf(github) + 1, clients.size());
                    }

                    GHRepository repo = github.getRepository(ref.repoFullName);

                    // Fetch issue or PR (GHPullRequest extends GHIssue)
                    GHIssue issue;
                    GHPullRequest pr = null;
                    if (type == IssueType.PULL_REQUEST) {
                        pr = repo.getPullRequest(ref.number);
                        issue = pr; // GHPullRequest IS-A GHIssue
                        Log.tracef("  PR %s#%d: eventTimestamp=%s", ref.repoFullName, ref.number, eventTimestamp);
                    } else {
                        issue = repo.getIssue(ref.number);
                    }

                    // Determine action category
                    ActionCategory actionCategory;
                    if (type == IssueType.PULL_REQUEST) {
                        GHPullRequest finalPr = pr; // Capture for lambda
                        GitHub finalGithub = github; // Capture for lambda
                        actionCategory = instanceInfo.categoryFilters.matchPullRequest(finalPr)
                            .orElseGet(() -> {
                                try {
                                    String currentLogin = finalGithub.getMyself().getLogin();
                                    boolean isAuthor = finalPr.getUser().getLogin().equals(currentLogin);
                                    return isAuthor ? ActionCategory.CODE : ActionCategory.REVIEW;
                                } catch (Exception e) {
                                    Log.tracef("Failed to determine PR author for %s#%d, defaulting to review: %s",
                                        ref.repoFullName, ref.number, e.getMessage());
                                    return ActionCategory.REVIEW;
                                }
                            });
                        if (instanceInfo.categoryFilters.matchPullRequest(finalPr).isPresent()) {
                            Log.tracef("  PR %s#%d: Categorized as %s based on filters", ref.repoFullName, ref.number, actionCategory);
                        }
                    } else {
                        actionCategory = instanceInfo.categoryFilters.matchIssue(issue)
                            .orElse(ActionCategory.DISCUSS);
                        if (actionCategory != ActionCategory.DISCUSS) {
                            Log.tracef("  Issue %s#%d: Categorized as %s based on filters", ref.repoFullName, ref.number, actionCategory);
                        }
                    }

                    // Collect all relevant links within date range
                    List<String> contentUrls = new ArrayList<>();
                    Set<String> externalUrls = new LinkedHashSet<>();

                    // Extract external URLs from title
                    String title = issue.getTitle();
                    if (title != null) {
                        urlExtractor.extractExternalUrls(title, externalUrls);
                    }

                    // Extract external URLs from body
                    String body = issue.getBody();
                    if (body != null) {
                        urlExtractor.extractExternalUrls(body, externalUrls);
                    }

                    // Extract #1234 references from title and body (GitHub-instance specific)
                    extractHashReferences(ref.repoFullName, title, body, contentUrls);

                    // Extract external URLs from comments
                    extractFromComments(issue.getComments(), externalUrls, startDate, endDate, urlExtractor);

                    // For PRs, also extract from reviews and review comments
                    if (type == IssueType.PULL_REQUEST) {
                        // Extract external URLs from review bodies
                        for (GHPullRequestReview review : pr.listReviews()) {
                            Instant reviewDate = review.getSubmittedAt().toInstant();
                            if (!reviewDate.isBefore(startDate) && !reviewDate.isAfter(endDate)) {
                                String reviewBody = review.getBody();
                                if (reviewBody != null) {
                                    urlExtractor.extractExternalUrls(reviewBody, externalUrls);
                                }
                            }
                        }

                        // Extract external URLs from review comments
                        for (GHPullRequestReviewComment reviewComment : pr.listReviewComments()) {
                            Instant commentDate = reviewComment.getCreatedAt().toInstant();
                            if (!commentDate.isBefore(startDate) && !commentDate.isAfter(endDate)) {
                                String commentBody = reviewComment.getBody();
                                if (commentBody != null) {
                                    urlExtractor.extractExternalUrls(commentBody, externalUrls);
                                }
                            }
                        }
                    }

                    // Add extracted external URLs to contentUrls
                    contentUrls.addAll(externalUrls);

                    if (!externalUrls.isEmpty()) {
                        String typeStr = type == IssueType.PULL_REQUEST ? "PR" : "Issue";
                        Log.tracef("  %s %s#%d: Extracted %d external URLs", typeStr, ref.repoFullName, ref.number, externalUrls.size());
                    }

                    // Create activity - use event timestamp that discovered this issue/PR
                    if (type == IssueType.PULL_REQUEST) {
                        Log.tracef("  PR %s#%d: Creating activity (contentUrls=%d)", ref.repoFullName, ref.number, contentUrls.size());
                    }

                    Activity activity = new Activity(
                        source,
                        type.actionName,
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
                    fetched = true;
                    break; // Success - no need to try other clients

                } catch (Exception e) {
                    lastException = e;
                    // Continue to next client
                    if (clients.indexOf(github) < clients.size() - 1) {
                        Log.tracef("Failed to fetch with client %d/%d, trying next: %s",
                            clients.indexOf(github) + 1, clients.size(), e.getMessage());
                    }
                }
            }

            if (!fetched) {
                String typeStr = type == IssueType.PULL_REQUEST ? "pull request" : "issue";
                Log.tracef("Failed to fetch %s %s#%d with any client (%d tried): %s",
                    typeStr, ref.repoFullName, ref.number, clients.size(),
                    lastException != null ? lastException.getMessage() : "unknown error");
            }
        }

        return activities;
    }
}
