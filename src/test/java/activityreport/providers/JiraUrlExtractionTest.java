package activityreport.providers;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify JIRA's URL extraction logic
 */
class JiraUrlExtractionTest {

    private void extractUrlsFromHtml(String html, List<String> contentUrls) {
        // Same regex from JiraProvider
        Pattern pattern = Pattern.compile(
            "https://(?:github\\.com|gitlab\\.com)/[\\w.-]+/[\\w.-]+/(?:pull|merge_requests|-/merge_requests)/\\d+"
        );
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            contentUrls.add(matcher.group());
        }
    }

    @Test
    void testExtractsBasePRUrl() {
        String html = "<a href='https://github.com/owner/repo/pull/123'>PR</a>";
        List<String> urls = new ArrayList<>();
        extractUrlsFromHtml(html, urls);

        assertEquals(1, urls.size());
        assertEquals("https://github.com/owner/repo/pull/123", urls.get(0));
    }

    @Test
    void testExtractsPRUrlWithFragment() {
        // When JIRA descriptions contain comment links
        String html = "<a href='https://github.com/owner/repo/pull/123#issuecomment-456'>Comment</a>";
        List<String> urls = new ArrayList<>();
        extractUrlsFromHtml(html, urls);

        // The regex matches but doesn't include the fragment!
        assertEquals(1, urls.size());
        assertEquals("https://github.com/owner/repo/pull/123", urls.get(0),
            "Fragment should be excluded by the regex");
    }

    @Test
    void testExtractsPRUrlWithQueryParams() {
        String html = "<a href='https://github.com/owner/repo/pull/123?foo=bar#comment'>PR</a>";
        List<String> urls = new ArrayList<>();
        extractUrlsFromHtml(html, urls);

        // Regex doesn't match query params
        assertEquals(1, urls.size());
        assertEquals("https://github.com/owner/repo/pull/123", urls.get(0));
    }

    @Test
    void testExtractsMultiplePRUrls() {
        String html = "See <a href='https://github.com/owner/repo/pull/123'>PR 123</a> and " +
                     "<a href='https://github.com/owner/repo/pull/456'>PR 456</a>";
        List<String> urls = new ArrayList<>();
        extractUrlsFromHtml(html, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://github.com/owner/repo/pull/123"));
        assertTrue(urls.contains("https://github.com/owner/repo/pull/456"));
    }

    @Test
    void testDoesNotExtractIssueUrls() {
        String html = "<a href='https://github.com/owner/repo/issues/123'>Issue</a>";
        List<String> urls = new ArrayList<>();
        extractUrlsFromHtml(html, urls);

        assertEquals(0, urls.size(), "Should not extract issue URLs, only PR URLs");
    }

    @Test
    void testExtractsGitLabMergeRequests() {
        String html = "<a href='https://gitlab.com/owner/repo/-/merge_requests/123'>MR</a>";
        List<String> urls = new ArrayList<>();
        extractUrlsFromHtml(html, urls);

        assertEquals(1, urls.size());
        assertEquals("https://gitlab.com/owner/repo/-/merge_requests/123", urls.get(0));
    }
}
