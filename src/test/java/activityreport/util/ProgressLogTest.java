package activityreport.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgressLogTest {

    @BeforeEach
    void resetState() {
        ProgressLog.resetDepth();
    }

    @Test
    void formatLineNoColorNoPrefix() {
        String result = ProgressLog.formatLine(false, 0, "", "", "hello %s", "world");
        assertEquals("hello world", result);
    }

    @Test
    void formatLineNoColorWithPrefix() {
        String result = ProgressLog.formatLine(false, 0, "▶", "", "starting");
        assertEquals("▶ starting", result);
    }

    @Test
    void formatLineIndentation() {
        String result = ProgressLog.formatLine(false, 2, "", "", "deep");
        assertEquals("    deep", result);
    }

    @Test
    void formatLineIndentationWithPrefix() {
        String result = ProgressLog.formatLine(false, 1, "✓", "", "done");
        assertEquals("  ✓ done", result);
    }

    @Test
    void formatLineWithColorAndPrefix() {
        String result = ProgressLog.formatLine(true, 0, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "test");
        assertEquals(ProgressLog.BOLD + ProgressLog.CYAN + "▶ test" + ProgressLog.RESET, result);
    }

    @Test
    void formatLineWithColorNoPrefix() {
        String result = ProgressLog.formatLine(true, 0, "", ProgressLog.DIM, "detail");
        assertEquals(ProgressLog.DIM + "detail" + ProgressLog.RESET, result);
    }

    @Test
    void formatLineWithColorAndIndent() {
        String result = ProgressLog.formatLine(true, 1, "✓", ProgressLog.GREEN, "Found %d", 42);
        assertEquals("  " + ProgressLog.GREEN + "✓ Found 42" + ProgressLog.RESET, result);
    }

    @Test
    void formatLineNoArgsWithPercent() {
        String result = ProgressLog.formatLine(false, 0, "", "", "100% complete");
        assertEquals("100% complete", result);
    }

    @Test
    void formatLineEmptyColorCode() {
        String result = ProgressLog.formatLine(true, 0, "", "", "plain");
        assertEquals("plain", result);
    }

    @Test
    void indentedNormalFlow() throws Exception {
        assertEquals(0, ProgressLog.getDepth());

        ProgressLog.indented(() -> {
            assertEquals(1, ProgressLog.getDepth());
        });

        assertEquals(0, ProgressLog.getDepth());
    }

    @Test
    void indentedNested() throws Exception {
        ProgressLog.indented(() -> {
            assertEquals(1, ProgressLog.getDepth());
            ProgressLog.indented(() -> {
                assertEquals(2, ProgressLog.getDepth());
            });
            assertEquals(1, ProgressLog.getDepth());
        });

        assertEquals(0, ProgressLog.getDepth());
    }

    @Test
    void indentedResetsOnException() {
        assertEquals(0, ProgressLog.getDepth());

        try {
            ProgressLog.indented(() -> {
                assertEquals(1, ProgressLog.getDepth());
                throw new RuntimeException("boom");
            });
            fail("should have thrown");
        } catch (Exception e) {
            assertEquals("boom", e.getMessage());
        }

        assertEquals(0, ProgressLog.getDepth());
    }

    @Test
    void indentedResetsOnNestedExceptions() {
        try {
            ProgressLog.indented(() -> {
                ProgressLog.indented(() -> {
                    assertEquals(2, ProgressLog.getDepth());
                    throw new RuntimeException("inner");
                });
            });
            fail("should have thrown");
        } catch (Exception e) {
            assertEquals("inner", e.getMessage());
        }

        assertEquals(0, ProgressLog.getDepth());
    }

    @Test
    void sampleOutput() {
        boolean colors = "true".equals(System.getenv("FORCE_COLOR")) || System.console() != null;
        System.out.println("\n=== Sample ProgressLog Output " + (colors ? "(colored)" : "(no color)") + " ===\n");

        System.out.println(ProgressLog.formatLine(colors, 0, "", ProgressLog.BOLD, "Activity Report Generator"));
        System.out.println(ProgressLog.formatLine(colors, 0, "", ProgressLog.BOLD, "========================="));
        System.out.println();
        System.out.println(ProgressLog.formatLine(colors, 0, "", "", "Configuration loaded successfully."));
        System.out.println();
        System.out.println(ProgressLog.formatLine(colors, 0, "", "", "Fetching activities from 2026-07-24 to 2026-07-31"));
        System.out.println();

        // GitHub provider
        System.out.println(ProgressLog.formatLine(colors, 0, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Fetching from GitHub (all instances)..."));
        System.out.println(ProgressLog.formatLine(colors, 1, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "github.com (2 token(s))"));
        System.out.println(ProgressLog.formatLine(colors, 2, "", ProgressLog.DIM, "[events for yrodiere (token 1/2)] Processed 267 events, earliest event: 2026-07-23T16:10:13Z. Found 7 new issues, 49 new PRs"));
        System.out.println(ProgressLog.formatLine(colors, 2, "", ProgressLog.DIM, "[public events for yrodiere (token 1/2)] Processed 267 events, earliest event: 2026-07-23T16:10:13Z. Found 0 new issues, 0 new PRs"));
        System.out.println(ProgressLog.formatLine(colors, 2, "", ProgressLog.DIM, "[events for yrodiere-agent (token 1/2)] Processed 65 events, earliest event: 2026-07-22T12:29:18Z. Found 0 new issues, 9 new PRs"));
        System.out.println(ProgressLog.formatLine(colors, 1, "✓", ProgressLog.GREEN, "Found 65 activities"));

        System.out.println(ProgressLog.formatLine(colors, 1, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "github.ibm.com (3 token(s))"));
        System.out.println(ProgressLog.formatLine(colors, 2, "", ProgressLog.DIM, "[events for yrodiere (token 1/3)] Processed 18 events, earliest event: 2026-07-17T12:14:20Z. Found 3 new issues, 1 new PRs"));
        System.out.println(ProgressLog.formatLine(colors, 2, "", ProgressLog.DIM, "Successfully fetched issue hibernate/initiatives#55 using fallback client 2/3"));
        System.out.println(ProgressLog.formatLine(colors, 1, "✓", ProgressLog.GREEN, "Found 4 activities"));

        System.out.println(ProgressLog.formatLine(colors, 0, "✓", ProgressLog.GREEN, "Found 69 activities"));

        // JIRA provider
        System.out.println(ProgressLog.formatLine(colors, 0, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Fetching from JIRA (all instances)..."));
        System.out.println(ProgressLog.formatLine(colors, 1, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Hibernate JIRA"));
        System.out.println(ProgressLog.formatLine(colors, 1, "✓", ProgressLog.GREEN, "Found 0 activities"));
        System.out.println(ProgressLog.formatLine(colors, 1, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Red Hat JIRA"));
        System.out.println(ProgressLog.formatLine(colors, 1, "✓", ProgressLog.GREEN, "Found 0 activities"));
        System.out.println(ProgressLog.formatLine(colors, 0, "✓", ProgressLog.GREEN, "Found 0 activities"));

        // Zulip provider
        System.out.println(ProgressLog.formatLine(colors, 0, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Fetching from Zulip (all instances)..."));
        System.out.println(ProgressLog.formatLine(colors, 1, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "https://hibernate.zulipchat.com"));
        System.out.println(ProgressLog.formatLine(colors, 1, "✓", ProgressLog.GREEN, "Found 17 activities"));
        System.out.println(ProgressLog.formatLine(colors, 1, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "https://quarkusio.zulipchat.com"));
        System.out.println(ProgressLog.formatLine(colors, 1, "✓", ProgressLog.GREEN, "Found 8 activities"));
        System.out.println(ProgressLog.formatLine(colors, 0, "✓", ProgressLog.GREEN, "Found 25 activities"));

        // Total
        System.out.println();
        System.out.println(ProgressLog.formatLine(colors, 0, "✓", ProgressLog.BOLD + ProgressLog.GREEN, "Total: 94 activities"));
        System.out.println(ProgressLog.formatLine(colors, 0, "", ProgressLog.DIM, "Activities dumped to: /home/yrodiere/.local/share/activity-report/dumps/activities_2026-07-24_to_2026-07-31.json"));
        System.out.println();

        // Classify
        System.out.println(ProgressLog.formatLine(colors, 0, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Classifying activities into projects..."));
        System.out.println();

        // AI warning
        System.out.println(ProgressLog.formatLine(colors, 0, "⚠", ProgressLog.YELLOW, "Could not auto-detect AI model: Connection refused: localhost/127.0.0.1:8000"));
        System.out.println(ProgressLog.formatLine(colors, 0, "⚠", ProgressLog.YELLOW, "AI model not available, falling back to simple grouping"));
        System.out.println();

        // Generate
        System.out.println(ProgressLog.formatLine(colors, 0, "▶", ProgressLog.BOLD + ProgressLog.CYAN, "Generating report..."));
        System.out.println();
        System.out.println(ProgressLog.formatLine(colors, 0, "✓", ProgressLog.GREEN, "Report written to: /home/yrodiere/.local/share/activity-report/report_2026-07-24_to_2026-07-31.md"));
        System.out.println(ProgressLog.formatLine(colors, 0, "", "", "Report file: /home/yrodiere/.local/share/activity-report/report_2026-07-24_to_2026-07-31.md"));
        System.out.println(ProgressLog.formatLine(colors, 0, "", "", "Opening report..."));

        System.out.println("\n=== End of Sample ===\n");
    }
}
