package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimpleGrouperTest {

    @Test
    void testGroupingWhenContentUrlsReferenceParent() {
        // This test shows that grouping WORKS when contentUrls include the parent URL
        Activity pr = new Activity(
            "GitHub",
            "pull_request",
            ActionCategory.CODE,
            "Add feature X",
            "Description",
            "https://github.com/repo/pull/123",
            Instant.now(),
            List.of()
        );

        Activity review = new Activity(
            "GitHub",
            "review",
            ActionCategory.REVIEW,
            "Reviewed PR",
            "Description",
            "https://github.com/repo/pull/123#review-456",
            Instant.now(),
            List.of("https://github.com/repo/pull/123") // This references the parent!
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(pr, review));

        long groupsWithSecondary = groups.stream()
            .filter(g -> !g.secondary().isEmpty())
            .count();

        assertEquals(1, groupsWithSecondary, "Expected 1 group when contentUrls reference parent");
    }

    @Test
    void testGroupingWithJiraReferencingGitHubPR() {
        // Realistic scenario: JIRA issue references a GitHub PR
        Activity pr = new Activity(
            "GitHub",
            "pull_request",
            ActionCategory.CODE,
            "Add feature X",
            "Description",
            "https://github.com/repo/pull/123",
            Instant.now(),
            List.of(
                "https://github.com/repo/pull/123#issuecomment-456",
                "https://github.com/repo/pull/123#discussion_r789"
            )
        );

        Activity jiraIssue = new Activity(
            "JIRA",
            "issue",
            ActionCategory.CODE,
            "PROJ-123: Implement feature",
            "Description",
            "https://jira.example.com/browse/PROJ-123",
            Instant.now(),
            List.of(
                "https://github.com/repo/pull/123" // JIRA references the GitHub PR
            )
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(pr, jiraIssue));

        long groupsWithSecondary = groups.stream()
            .filter(g -> !g.secondary().isEmpty())
            .count();

        // This DOES group because JIRA's contentUrls include the PR's main URL
        assertEquals(1, groupsWithSecondary, "Should group when JIRA contentUrls reference the GitHub PR");
    }

    @Test
    void testNoGroupingWhenUrlsAreRelatedButDontOverlap() {
        // Scenario that WON'T group: URLs are related (same PR) but don't overlap
        Activity pr = new Activity(
            "GitHub",
            "pull_request",
            ActionCategory.CODE,
            "Add feature X",
            "Description",
            "https://github.com/repo/pull/123",
            Instant.now(),
            List.of(
                "https://github.com/repo/pull/123#issuecomment-456"
            )
        );

        Activity jiraIssue = new Activity(
            "JIRA",
            "issue",
            ActionCategory.CODE,
            "PROJ-123: Implement feature",
            "Description",
            "https://jira.example.com/browse/PROJ-123",
            Instant.now(),
            List.of(
                // JIRA extracted a DIFFERENT comment URL, not the PR's main URL
                "https://github.com/repo/pull/123#issuecomment-789"
            )
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(pr, jiraIssue));

        long groupsWithSecondary = groups.stream()
            .filter(g -> !g.secondary().isEmpty())
            .count();

        // No grouping: URLs don't overlap (different comment IDs)
        assertEquals(0, groupsWithSecondary,
            "No grouping when URLs are related but don't exactly match");
    }

    @Test
    void testNoGroupingWithCompletelyDifferentUrls() {
        // Activities with no URL overlap shouldn't group
        Activity pr = new Activity(
            "GitHub",
            "pull_request",
            ActionCategory.CODE,
            "Add feature X",
            "Description",
            "https://github.com/repo/pull/123",
            Instant.now()
        );

        Activity issue = new Activity(
            "GitHub",
            "issue",
            ActionCategory.DISCUSS,
            "Bug report",
            "Description",
            "https://github.com/repo/issues/456",
            Instant.now()
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(pr, issue));

        assertEquals(2, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.secondary().isEmpty()));
    }
}
