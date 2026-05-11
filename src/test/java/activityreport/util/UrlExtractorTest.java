package activityreport.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class UrlExtractorTest {

    @Test
    void testExtractGitHubComPRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        String text = "I reviewed https://github.com/owner/repo/pull/123 and https://github.com/other/proj/pull/456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://github.com/owner/repo/pull/123"));
        assertTrue(urls.contains("https://github.com/other/proj/pull/456"));
    }

    @Test
    void testExtractGitHubEnterprisePRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://github.company.com/api/v3", "Company GHE");

        String text = "See https://github.company.com/team/project/pull/789";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://github.company.com/team/project/pull/789"));
    }

    @Test
    void testExtractGitHubPRsStripsFragments() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        String text = "See https://github.com/owner/repo/pull/123#issuecomment-456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://github.com/owner/repo/pull/123"));
    }

    @Test
    void testExtractGitLabComMRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitLabInstance("https://gitlab.com", "GitLab.com");

        String text = "Check https://gitlab.com/owner/repo/-/merge_requests/123";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://gitlab.com/owner/repo/-/merge_requests/123"));
    }

    @Test
    void testExtractSelfHostedGitLabMRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitLabInstance("https://gitlab.company.com", "Company GitLab");

        String text = "See https://gitlab.company.com/team/project/-/merge_requests/456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://gitlab.company.com/team/project/-/merge_requests/456"));
    }

    @Test
    void testExtractJiraIssueUrls() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");
        extractor.registerJiraInstance("https://issues.corp.com", "Corp Issues");

        String text = "Fixed https://jira.example.com/browse/PROJ-123 and https://issues.corp.com/browse/BUG-456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
        assertTrue(urls.contains("https://issues.corp.com/browse/BUG-456"));
    }

    @Test
    void testExtractJiraIssueKeys() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String text = "Working on PROJ-123 and PROJ-456 today";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-456"));
    }

    @Test
    void testExtractJiraIssueKeysIgnoresLowercase() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String text = "The proj-123 is not a valid issue key but PROJ-123 is";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(1, urls.size());
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
    }

    @Test
    void testExtractAllTypes() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");
        extractor.registerGitLabInstance("https://gitlab.com", "GitLab.com");
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA");

        String text = "Fixed PROJ-123 via https://github.com/owner/repo/pull/456 " +
                     "and https://gitlab.com/other/repo/-/merge_requests/789. " +
                     "See also https://jira.example.com/browse/BUG-999";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(4, urls.size());
        assertTrue(urls.contains("https://jira.example.com/browse/PROJ-123"));
        assertTrue(urls.contains("https://github.com/owner/repo/pull/456"));
        assertTrue(urls.contains("https://gitlab.com/other/repo/-/merge_requests/789"));
        assertTrue(urls.contains("https://jira.example.com/browse/BUG-999"));
    }

    @Test
    void testHandlesNullAndEmptyText() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(null, urls);
        assertEquals(0, urls.size());

        extractor.extractExternalUrls("", urls);
        assertEquals(0, urls.size());
    }

    @Test
    void testMultipleInstancesOfSameType() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");
        extractor.registerGitHubInstance("https://github.company.com/api/v3", "Company GHE");

        String text = "https://github.com/public/repo/pull/1 and https://github.company.com/private/repo/pull/2";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertEquals(2, urls.size());
        assertTrue(urls.contains("https://github.com/public/repo/pull/1"));
        assertTrue(urls.contains("https://github.company.com/private/repo/pull/2"));
    }
}
