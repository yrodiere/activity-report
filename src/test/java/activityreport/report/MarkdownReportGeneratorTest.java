package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReportGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");

    private static Activity codeActivity(String title, String url, String description, String project) {
        return new Activity("GitHub", "pull_request", ActionCategory.CODE,
                title, description, url, NOW, List.of(), project);
    }

    private static Activity reviewActivity(String title, String url) {
        return new Activity("GitHub", "review", ActionCategory.REVIEW,
                title, null, url, NOW, List.of());
    }

    private static Activity discussActivity(String title, String url) {
        return new Activity("GitHub", "issue", ActionCategory.DISCUSS,
                title, null, url, NOW, List.of());
    }

    @Test
    void simpleGroupsWithoutSecondaries() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR one", "https://github.com/r/pull/1", null, "Alpha"),
                        List.of()),
                new ActivityGroup(
                        codeActivity("PR two", "https://github.com/r/pull/2", null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Project: Alpha

                * [PR one](https://github.com/r/pull/1)
                * [PR two](https://github.com/r/pull/2)

                ----
                """);
    }

    @Test
    void groupsWithSecondaries_noExtraBlankLines() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR one", "https://github.com/r/pull/1", null, "Alpha"),
                        List.of(
                                reviewActivity("Reviewed PR one", "https://github.com/r/pull/1#review-10"))),
                new ActivityGroup(
                        codeActivity("PR two", "https://github.com/r/pull/2", null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Project: Alpha

                * [PR one](https://github.com/r/pull/1)
                    * [Reviewed PR one](https://github.com/r/pull/1#review-10)
                * [PR two](https://github.com/r/pull/2)

                ----
                """);
    }

    @Test
    void groupWithDescription_noExtraBlankLine() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR one", "https://github.com/r/pull/1", "Some description", "Alpha"),
                        List.of()),
                new ActivityGroup(
                        codeActivity("PR two", "https://github.com/r/pull/2", null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Project: Alpha

                * [PR one](https://github.com/r/pull/1)
                  Some description
                * [PR two](https://github.com/r/pull/2)

                ----
                """);
    }

    @Test
    void groupWithDescriptionAndSecondaries() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR one", "https://github.com/r/pull/1", "Some description", "Alpha"),
                        List.of(
                                reviewActivity("Reviewed PR one", "https://github.com/r/pull/1#review-10"))),
                new ActivityGroup(
                        codeActivity("PR two", "https://github.com/r/pull/2", null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Project: Alpha

                * [PR one](https://github.com/r/pull/1)
                  Some description
                    * [Reviewed PR one](https://github.com/r/pull/1#review-10)
                * [PR two](https://github.com/r/pull/2)

                ----
                """);
    }

    @Test
    void multipleConsecutiveGroupsWithSecondaries() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR one", "https://github.com/r/pull/1", null, "Alpha"),
                        List.of(
                                reviewActivity("Review A", "https://github.com/r/pull/1#review-10"),
                                reviewActivity("Review B", "https://github.com/r/pull/1#review-11"))),
                new ActivityGroup(
                        codeActivity("PR two", "https://github.com/r/pull/2", null, "Alpha"),
                        List.of(
                                discussActivity("Discussion", "https://github.com/r/issues/20"))));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Project: Alpha

                * [PR one](https://github.com/r/pull/1)
                    * [Review A](https://github.com/r/pull/1#review-10)
                    * [Review B](https://github.com/r/pull/1#review-11)
                * [PR two](https://github.com/r/pull/2)
                    * [Discussion](https://github.com/r/issues/20)

                ----
                """);
    }

    @Test
    void groupWithoutUrl() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("Some work", null, null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Project: Alpha

                * Some work

                ----
                """);
    }

    @Test
    void miscSection() {
        var groups = List.of(
                new ActivityGroup(
                        reviewActivity("Reviewed something", "https://github.com/r/pull/5"),
                        List.of()),
                new ActivityGroup(
                        discussActivity("Discussed issue", "https://github.com/r/issues/9"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of());

        assertThat(report).isEqualTo("""
                # General

                (To be filled manually)

                ----

                # Misc

                Reviews

                * [Reviewed something](https://github.com/r/pull/5)

                Triage, discussions

                * [Discussed issue](https://github.com/r/issues/9)
                """);
    }

    @Test
    void projectOrderFollowsConfiguration() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR in Zeta", "https://github.com/z/pull/1", null, "Zeta"),
                        List.of()),
                new ActivityGroup(
                        codeActivity("PR in Alpha", "https://github.com/a/pull/1", null, "Alpha"),
                        List.of()),
                new ActivityGroup(
                        codeActivity("PR in Beta", "https://github.com/b/pull/1", null, "Beta"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of("Alpha", "Beta", "Zeta"));

        int alphaPos = report.indexOf("# Project: Alpha");
        int betaPos = report.indexOf("# Project: Beta");
        int zetaPos = report.indexOf("# Project: Zeta");

        assertThat(alphaPos).isLessThan(betaPos);
        assertThat(betaPos).isLessThan(zetaPos);
    }

    @Test
    void unconfiguredProjectsAppearAfterConfiguredOnes() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("PR in Unknown", "https://github.com/u/pull/1", null, "Unknown"),
                        List.of()),
                new ActivityGroup(
                        codeActivity("PR in Alpha", "https://github.com/a/pull/1", null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of("Alpha"));

        int alphaPos = report.indexOf("# Project: Alpha");
        int unknownPos = report.indexOf("# Project: Unknown");

        assertThat(alphaPos).isLessThan(unknownPos);
    }

    @Test
    void prStatusPrefixRendered() {
        Activity started = new Activity("GitHub", "pull_request", ActionCategory.CODE,
                "New PR", null, "https://github.com/r/pull/1", NOW,
                List.of(), "Alpha", new HashMap<>(Map.of("prStatus", "Started")));
        Activity continued = new Activity("GitHub", "pull_request", ActionCategory.CODE,
                "Ongoing PR", null, "https://github.com/r/pull/2", NOW,
                List.of(), "Alpha", new HashMap<>(Map.of("prStatus", "Continued")));
        Activity merged = new Activity("GitHub", "pull_request", ActionCategory.CODE,
                "Done PR", null, "https://github.com/r/pull/3", NOW,
                List.of(), "Alpha", new HashMap<>(Map.of("prStatus", "Merged")));
        Activity closed = new Activity("GitHub", "pull_request", ActionCategory.CODE,
                "Rejected PR", null, "https://github.com/r/pull/4", NOW,
                List.of(), "Alpha", new HashMap<>(Map.of("prStatus", "Closed")));

        var groups = List.of(
                new ActivityGroup(started, List.of()),
                new ActivityGroup(continued, List.of()),
                new ActivityGroup(merged, List.of()),
                new ActivityGroup(closed, List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of("Alpha"));

        assertThat(report).contains("* [Started] [New PR](https://github.com/r/pull/1)");
        assertThat(report).contains("* [Continued] [Ongoing PR](https://github.com/r/pull/2)");
        assertThat(report).contains("* [Merged] [Done PR](https://github.com/r/pull/3)");
        assertThat(report).contains("* [Closed] [Rejected PR](https://github.com/r/pull/4)");
    }

    @Test
    void prStatusPrefixRenderedOnSecondaryActivities() {
        Activity primary = new Activity("GitHub", "pull_request", ActionCategory.CODE,
                "Main PR", null, "https://github.com/r/pull/1", NOW,
                List.of(), "Alpha", new HashMap<>(Map.of("prStatus", "Merged")));
        Activity secondary = new Activity("GitHub", "pull_request", ActionCategory.CODE,
                "Related PR", null, "https://github.com/r/pull/2", NOW,
                List.of(), "Alpha", new HashMap<>(Map.of("prStatus", "Continued")));
        Activity secondaryNoStatus = new Activity("GitHub", "issue", ActionCategory.CODE,
                "An issue", null, "https://github.com/r/issues/3", NOW,
                List.of(), "Alpha", new HashMap<>());

        var groups = List.of(
                new ActivityGroup(primary, List.of(secondary, secondaryNoStatus)));

        String report = MarkdownReportGenerator.generate(groups, List.of("Alpha"));

        assertThat(report).contains("* [Merged] [Main PR](https://github.com/r/pull/1)");
        assertThat(report).contains("    * [Continued] [Related PR](https://github.com/r/pull/2)");
        assertThat(report).contains("    * [An issue](https://github.com/r/issues/3)");
    }

    @Test
    void noPrefixWithoutPrStatus() {
        var groups = List.of(
                new ActivityGroup(
                        codeActivity("Some PR", "https://github.com/r/pull/1", null, "Alpha"),
                        List.of()));

        String report = MarkdownReportGenerator.generate(groups, List.of("Alpha"));

        assertThat(report).contains("* [Some PR](https://github.com/r/pull/1)");
        assertThat(report).doesNotContain("[Started]");
        assertThat(report).doesNotContain("[Continued]");
        assertThat(report).doesNotContain("[Merged]");
    }
}
