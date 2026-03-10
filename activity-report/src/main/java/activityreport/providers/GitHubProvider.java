package activityreport.providers;

import activityreport.config.AppConfig;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import io.quarkus.logging.Log;
import org.kohsuke.github.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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
                Log.warnf("Error fetching from %s: %s", instanceName, e.getMessage());
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
