package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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

        String report = MarkdownReportGenerator.generate(groups);

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

        String report = MarkdownReportGenerator.generate(groups);

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

        String report = MarkdownReportGenerator.generate(groups);

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

        String report = MarkdownReportGenerator.generate(groups);

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

        String report = MarkdownReportGenerator.generate(groups);

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

        String report = MarkdownReportGenerator.generate(groups);

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

        String report = MarkdownReportGenerator.generate(groups);

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
}
