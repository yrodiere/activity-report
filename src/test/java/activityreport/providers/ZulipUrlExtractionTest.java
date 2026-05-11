package activityreport.providers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test URL extraction from Zulip messages to enable grouping with GitHub activities
 */
class ZulipUrlExtractionTest {

    /**
     * Extract GitHub/GitLab PR URLs from message content.
     * This should match the same URLs that JIRA extracts to ensure consistent grouping.
     */
    private void extractPullRequestUrls(String messageContent, List<String> contentUrls) {
        // Same regex pattern as JiraProvider uses
        Pattern pattern = Pattern.compile(
            "https://(?:github\\.com|gitlab\\.com)/[\\w.-]+/[\\w.-]+/(?:pull|merge_requests|-/merge_requests)/\\d+"
        );
        Matcher matcher = pattern.matcher(messageContent);
        while (matcher.find()) {
            contentUrls.add(matcher.group());
        }
    }

    @Test
    void testExtractsGitHubPRUrl() {
        String message = "I reviewed https://github.com/owner/repo/pull/123 and left some comments";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        assertEquals(1, urls.size());
        assertEquals("https://github.com/owner/repo/pull/123", urls.get(0));
    }

    @Test
    void testExtractsGitHubPRUrlWithFragment() {
        String message = "See my comment at https://github.com/owner/repo/pull/123#issuecomment-456";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        // Regex should extract base URL without fragment
        assertEquals(1, urls.size());
        assertEquals("https://github.com/owner/repo/pull/123", urls.get(0));
    }

    @Test
    void testExtractsMultiplePRUrls() {
        String message = "I reviewed https://github.com/owner/repo/pull/123 and https://github.com/owner/repo/pull/456";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://github.com/owner/repo/pull/123"));
        assertTrue(urls.contains("https://github.com/owner/repo/pull/456"));
    }

    @Test
    void testExtractsGitLabMergeRequest() {
        String message = "Check out https://gitlab.com/owner/repo/-/merge_requests/123";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        assertEquals(1, urls.size());
        assertEquals("https://gitlab.com/owner/repo/-/merge_requests/123", urls.get(0));
    }

    @Test
    void testIgnoresIssueUrls() {
        String message = "See https://github.com/owner/repo/issues/123 for details";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        assertEquals(0, urls.size(), "Should not extract issue URLs, only PR URLs");
    }

    @Test
    void testIgnoresOtherUrls() {
        String message = "Check https://google.com and https://stackoverflow.com/questions/123";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        assertEquals(0, urls.size(), "Should only extract GitHub/GitLab PR URLs");
    }

    @Test
    void testHandlesMarkdownLinks() {
        String message = "I reviewed [this PR](https://github.com/owner/repo/pull/123) yesterday";
        List<String> urls = new ArrayList<>();
        extractPullRequestUrls(message, urls);

        assertEquals(1, urls.size());
        assertEquals("https://github.com/owner/repo/pull/123", urls.get(0));
    }
}
