package activityreport.providers;

import activityreport.util.UrlExtractor;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test URL extraction from GitHub PR/issue descriptions and comments
 */
class GitHubUrlExtractionTest {

    @Test
    void testExtractJiraIssueFromPRDescription() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String prBody = "This PR fixes PROJ-123 by implementing the new validation logic.";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
    }

    @Test
    void testExtractJiraUrlFromPRDescription() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String prBody = "Implements https://jira.example.com/browse/FEAT-456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertTrue(urls.contains("https://jira.example.com/browse/FEAT-456"));
    }

    @Test
    void testExtractOtherGitHubPRFromDescription() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        String prBody = "This is a follow-up to https://github.com/other/repo/pull/789";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertTrue(urls.contains("https://github.com/other/repo/pull/789"));
    }

    @Test
    void testExtractGitLabMRFromPRDescription() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitLabInstance("https://gitlab.com", "GitLab.com");

        String prBody = "This is a mirror of https://gitlab.com/owner/repo/-/merge_requests/123";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertTrue(urls.contains("https://gitlab.com/owner/repo/-/merge_requests/123"));
    }

    @Test
    void testExtractMultipleReferencesFromPRBody() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String prBody = """
            Fixes PROJ-123 and PROJ-456

            Related to https://github.com/other/repo/pull/789
            See also https://jira.example.com/browse/BUG-999
            """;
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-456"));
        assertTrue(urls.contains("https://github.com/other/repo/pull/789"));
        assertTrue(urls.contains("https://jira.example.com/browse/BUG-999"));
    }

    @Test
    void testCommonPRTemplatePatterns() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String prBody = """
            ## Description
            Implements new feature

            ## Related Issues
            - Fixes PROJ-123
            - Related to https://jira.example.com/browse/PROJ-456

            ## Testing
            Manual testing performed
            """;
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-456"));
    }

    @Test
    void testGitHubEnterprisePRReferences() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://github.company.com/api/v3", "Company GHE");

        String prBody = "Depends on https://github.company.com/internal/lib/pull/42";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(prBody, urls);

        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://github.company.com/internal/lib/pull/42"));
    }
}
