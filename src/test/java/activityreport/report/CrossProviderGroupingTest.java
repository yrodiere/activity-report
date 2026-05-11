package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests demonstrating cross-provider grouping via URL extraction
 */
class CrossProviderGroupingTest {

    @Test
    void testZulipJiraAndGitHubGroupTogether() {
        // Scenario: Discussion in Zulip mentions JIRA issue, which references GitHub PR
        // All three should group together via transitive URL matching

        Activity githubPR = new Activity(
            "GitHub - github.com",
            "pull_request",
            ActionCategory.CODE,
            "owner/repo#123: Implement feature",
            "",
            "https://github.com/owner/repo/pull/123",
            Instant.now()
        );

        // JIRA issue that references the GitHub PR
        Activity jiraIssue = new Activity(
            "JIRA - company",
            "issue",
            ActionCategory.CODE,
            "PROJ-456: Feature implementation",
            "",
            "https://jira.example.com/browse/PROJ-456",
            Instant.now(),
            List.of("https://github.com/owner/repo/pull/123")
        );

        // Zulip discussion that mentions the JIRA issue key
        // The UrlExtractor would have converted "PROJ-456" to the full JIRA URL
        Activity zulipTopic = new Activity(
            "Zulip - example.zulipchat.com",
            "topic",
            ActionCategory.DISCUSS,
            "development / Sprint planning",
            "",
            "https://example.zulipchat.com/#narrow/stream/development/topic/Sprint.20planning",
            Instant.now(),
            List.of(
                "https://example.zulipchat.com/#narrow/stream/development/topic/Sprint.20planning/near/12345",
                "https://jira.example.com/browse/PROJ-456" // Extracted from "Working on PROJ-456"
            )
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(
            List.of(githubPR, jiraIssue, zulipTopic)
        );

        // All three should group together:
        // - Zulip → JIRA (both reference PROJ-456 URL)
        // - JIRA → GitHub (JIRA references GitHub PR)
        // - Transitive closure groups all three
        assertEquals(1, groups.size(), "All three activities should group together");
        ActivityGroup group = groups.get(0);
        assertEquals(2, group.secondary().size());
    }

    @Test
    void testZulipReferencesMultipleExternalSystems() {
        Activity githubPR = new Activity(
            "GitHub - github.com",
            "pull_request",
            ActionCategory.CODE,
            "owner/repo#123: Fix bug",
            "",
            "https://github.com/owner/repo/pull/123",
            Instant.now()
        );

        Activity jiraIssue = new Activity(
            "JIRA - company",
            "issue",
            ActionCategory.DISCUSS,
            "BUG-789: Fix production issue",
            "",
            "https://jira.example.com/browse/BUG-789",
            Instant.now()
        );

        // Zulip topic that references both GitHub and JIRA
        Activity zulipTopic = new Activity(
            "Zulip - example.zulipchat.com",
            "topic",
            ActionCategory.DISCUSS,
            "incidents / Production hotfix",
            "",
            "https://example.zulipchat.com/#narrow/stream/incidents/topic/Production.20hotfix",
            Instant.now(),
            List.of(
                "https://github.com/owner/repo/pull/123",
                "https://jira.example.com/browse/BUG-789"
            )
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(
            List.of(githubPR, jiraIssue, zulipTopic)
        );

        // All three should group - Zulip links them together
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).secondary().size());
    }

    @Test
    void testGitLabAndJiraGrouping() {
        Activity gitlabMR = new Activity(
            "GitLab - gitlab.com",
            "merge_request",
            ActionCategory.CODE,
            "owner/repo!456: Add feature",
            "",
            "https://gitlab.com/owner/repo/-/merge_requests/456",
            Instant.now()
        );

        Activity jiraIssue = new Activity(
            "JIRA - company",
            "issue",
            ActionCategory.CODE,
            "FEAT-123: New feature",
            "",
            "https://jira.example.com/browse/FEAT-123",
            Instant.now(),
            List.of("https://gitlab.com/owner/repo/-/merge_requests/456")
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(gitlabMR, jiraIssue));

        assertEquals(1, groups.size());
        assertEquals(1, groups.get(0).secondary().size());
    }
}
