package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating GitHub-Zulip grouping via URL extraction
 */
class GitHubZulipGroupingTest {

    @Test
    void testGitHubAndZulipGroupTogetherWhenZulipReferencesGitHubPR() {
        // Simulate a GitHub PR activity
        Activity githubPR = new Activity(
            "GitHub - github.com",
            "pull_request",
            ActionCategory.CODE,
            "owner/repo#123: Add new feature",
            "",
            "https://github.com/owner/repo/pull/123",
            Instant.now(),
            List.of(
                "https://github.com/owner/repo/pull/123#issuecomment-456",
                "https://github.com/owner/repo/pull/123#discussion_r789"
            )
        );

        // Simulate a Zulip topic activity that references the GitHub PR
        // This mimics what ZulipProvider does after the fix:
        // - contentUrls includes individual message URLs
        // - contentUrls also includes extracted PR URLs from message content
        Activity zulipTopic = new Activity(
            "Zulip - example.zulipchat.com",
            "topic",
            ActionCategory.DISCUSS,
            "development / Feature implementation",
            "",
            "https://example.zulipchat.com/#narrow/stream/development/topic/Feature.20implementation",
            Instant.now(),
            List.of(
                "https://example.zulipchat.com/#narrow/stream/development/topic/Feature.20implementation/near/12345",
                "https://example.zulipchat.com/#narrow/stream/development/topic/Feature.20implementation/near/12346",
                // This URL was extracted from message content by ZulipProvider
                "https://github.com/owner/repo/pull/123"
            )
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(githubPR, zulipTopic));

        // Should create 1 group because both activities share the GitHub PR URL
        long groupsWithSecondary = groups.stream()
            .filter(g -> !g.secondary().isEmpty())
            .count();

        assertEquals(1, groupsWithSecondary,
            "GitHub PR and Zulip topic should group together when Zulip references the PR");

        // Verify the group structure
        ActivityGroup group = groups.stream()
            .filter(g -> !g.secondary().isEmpty())
            .findFirst()
            .orElseThrow();

        // Primary should be the CODE activity (GitHub PR)
        assertEquals(ActionCategory.CODE, group.primary().actionCategory());
        assertEquals("https://github.com/owner/repo/pull/123", group.primary().url());

        // Secondary should be the DISCUSS activity (Zulip)
        assertEquals(1, group.secondary().size());
        assertEquals(ActionCategory.DISCUSS, group.secondary().get(0).actionCategory());
        assertTrue(group.secondary().get(0).source().contains("Zulip"));
    }

    @Test
    void testGitHubAndZulipDontGroupWhenZulipDoesntReferenceGitHub() {
        Activity githubPR = new Activity(
            "GitHub - github.com",
            "pull_request",
            ActionCategory.CODE,
            "owner/repo#123: Add new feature",
            "",
            "https://github.com/owner/repo/pull/123",
            Instant.now()
        );

        // Zulip topic without any GitHub PR references
        Activity zulipTopic = new Activity(
            "Zulip - example.zulipchat.com",
            "topic",
            ActionCategory.DISCUSS,
            "development / Random discussion",
            "",
            "https://example.zulipchat.com/#narrow/stream/development/topic/Random.20discussion",
            Instant.now(),
            List.of(
                "https://example.zulipchat.com/#narrow/stream/development/topic/Random.20discussion/near/99999"
            )
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(List.of(githubPR, zulipTopic));

        // Should create 2 separate groups
        assertEquals(2, groups.size());
        assertTrue(groups.stream().allMatch(g -> g.secondary().isEmpty()),
            "No grouping should occur when URLs don't overlap");
    }

    @Test
    void testMultipleZulipTopicsGroupWithSameGitHubPR() {
        Activity githubPR = new Activity(
            "GitHub - github.com",
            "pull_request",
            ActionCategory.CODE,
            "owner/repo#123: Add new feature",
            "",
            "https://github.com/owner/repo/pull/123",
            Instant.now()
        );

        // Two different Zulip topics both referencing the same PR
        Activity zulipTopic1 = new Activity(
            "Zulip - example.zulipchat.com",
            "topic",
            ActionCategory.DISCUSS,
            "development / PR Review",
            "",
            "https://example.zulipchat.com/#narrow/stream/development/topic/PR.20Review",
            Instant.now(),
            List.of("https://github.com/owner/repo/pull/123")
        );

        Activity zulipTopic2 = new Activity(
            "Zulip - example.zulipchat.com",
            "topic",
            ActionCategory.DISCUSS,
            "engineering / Implementation",
            "",
            "https://example.zulipchat.com/#narrow/stream/engineering/topic/Implementation",
            Instant.now(),
            List.of("https://github.com/owner/repo/pull/123")
        );

        List<ActivityGroup> groups = SimpleGrouper.groupActivities(
            List.of(githubPR, zulipTopic1, zulipTopic2)
        );

        // Should create 1 group with all 3 activities
        assertEquals(1, groups.size());
        ActivityGroup group = groups.get(0);
        assertEquals(2, group.secondary().size(),
            "Both Zulip topics should be grouped with the GitHub PR");
    }
}
