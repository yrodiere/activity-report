package activityreport.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UrlExtractorTest {

    @Test
    void testExtractGitHubComPRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        String text = "I reviewed https://github.com/owner/repo/pull/123 and https://github.com/other/proj/pull/456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactlyInAnyOrder(
            "https://github.com/owner/repo/pull/123",
            "https://github.com/other/proj/pull/789");
    }

    @Test
    void testExtractGitHubEnterprisePRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://github.company.com/api/v3", "Company GHE");

        String text = "See https://github.company.com/team/project/pull/789";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactly("https://github.company.com/team/project/pull/789");
    }

    @Test
    void testExtractGitHubPRsStripsFragments() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        String text = "See https://github.com/owner/repo/pull/123#issuecomment-456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactly("https://github.com/owner/repo/pull/123");
    }

    @Test
    void testExtractGitLabComMRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitLabInstance("https://gitlab.com", "GitLab.com");

        String text = "Check https://gitlab.com/owner/repo/-/merge_requests/123";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactly("https://gitlab.com/owner/repo/-/merge_requests/123");
    }

    @Test
    void testExtractSelfHostedGitLabMRs() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitLabInstance("https://gitlab.company.com", "Company GitLab");

        String text = "See https://gitlab.company.com/team/project/-/merge_requests/456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactly("https://gitlab.company.com/team/project/-/merge_requests/456");
    }

    @Test
    void testExtractJiraIssueUrls() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA", List.of());
        extractor.registerJiraInstance("https://issues.corp.com", "Corp Issues", List.of());

        String text = "Fixed https://jira.example.com/browse/PROJ-123 and https://issues.corp.com/browse/BUG-456";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactlyInAnyOrder(
            "https://jira.example.com/browse/PROJ-123",
            "https://issues.corp.com/browse/BUG-456");
    }

    @Test
    void testExtractJiraIssueKeysWithConfiguredProjects() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA", List.of("PROJ", "TASK"));

        String text = "Working on PROJ-123 and PROJ-456 today";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactlyInAnyOrder(
            "https://jira.example.com/browse/PROJ-123",
            "https://jira.example.com/browse/PROJ-456");
    }

    @Test
    void testExtractJiraIssueKeysIgnoresUnconfiguredProjects() {
        UrlExtractor extractor = new UrlExtractor();
        // Only configured to recognize PROJ keys, not LICENSE keys
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA", List.of("PROJ"));

        String text = "Working on PROJ-123 but LICENSE-2 should be ignored";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactly("https://jira.example.com/browse/PROJ-123")
            .doesNotContain("https://jira.example.com/browse/LICENSE-2");
    }

    @Test
    void testExtractAllTypes() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");
        extractor.registerGitLabInstance("https://gitlab.com", "GitLab.com");
        extractor.registerJiraInstance("https://jira.example.com", "Example JIRA", List.of("PROJ", "BUG"));

        String text = "Fixed PROJ-123 via https://github.com/owner/repo/pull/456 " +
                     "and https://gitlab.com/other/repo/-/merge_requests/789. " +
                     "See also https://jira.example.com/browse/BUG-999";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactlyInAnyOrder(
            "https://jira.example.com/browse/PROJ-123",
            "https://github.com/owner/repo/pull/456",
            "https://gitlab.com/other/repo/-/merge_requests/789",
            "https://jira.example.com/browse/BUG-999");
    }

    @Test
    void testHandlesNullAndEmptyText() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");

        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(null, urls);
        assertThat(urls).isEmpty();

        extractor.extractExternalUrls("", urls);
        assertThat(urls).isEmpty();
    }

    @Test
    void testMultipleInstancesOfSameType() {
        UrlExtractor extractor = new UrlExtractor();
        extractor.registerGitHubInstance("https://api.github.com", "GitHub.com");
        extractor.registerGitHubInstance("https://github.company.com/api/v3", "Company GHE");

        String text = "https://github.com/public/repo/pull/1 and https://github.company.com/private/repo/pull/2";
        Set<String> urls = new HashSet<>();
        extractor.extractExternalUrls(text, urls);

        assertThat(urls).containsExactlyInAnyOrder(
            "https://github.com/public/repo/pull/1",
            "https://github.company.com/private/repo/pull/2");
    }
}
