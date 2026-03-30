package activityreport.report;

import activityreport.model.Activity;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple markdown report generator (fallback when AI is disabled)
 */
public class MarkdownReportGenerator {

    public static String generateSimple(List<Activity> activities, Instant startDate, Instant endDate) {
        StringBuilder report = new StringBuilder();

        // Group by project
        Map<String, List<Activity>> byProject = new LinkedHashMap<>();
        byProject.put("General", new ArrayList<>()); // Always empty, manually filled

        List<Activity> miscActivities = new ArrayList<>();

        for (Activity activity : activities) {
            String project = (String) activity.metadata().get("project");
            if (project != null && !project.isEmpty()) {
                byProject.computeIfAbsent(project, k -> new ArrayList<>()).add(activity);
            } else {
                miscActivities.add(activity);
            }
        }

        // Generate General section (always empty)
        report.append("# General\n\n");
        report.append("(To be filled manually)\n\n");
        report.append("----\n\n");

        // Generate Project sections
        for (Map.Entry<String, List<Activity>> entry : byProject.entrySet()) {
            String project = entry.getKey();
            if ("General".equals(project)) {
                continue; // Already handled
            }

            List<Activity> projectActivities = entry.getValue();
            if (projectActivities.isEmpty()) {
                continue;
            }

            report.append(String.format("# Project: %s\n\n", project));

            for (Activity activity : projectActivities) {
                formatActivity(report, activity);
            }

            report.append("----\n\n");
        }

        // Generate Misc section
        if (!miscActivities.isEmpty()) {
            report.append("# Misc\n\n");
            report.append("Discussions, triage\n\n");

            for (Activity activity : miscActivities) {
                formatActivity(report, activity);
            }
        }

        return report.toString();
    }

    private static void formatActivity(StringBuilder report, Activity activity) {
        // Main activity line with link
        if (activity.url() != null && !activity.url().isEmpty()) {
            report.append(String.format("* [%s](%s)", activity.title(), activity.url()));
        } else {
            report.append(String.format("* %s", activity.title()));
        }

        // Add content URLs as sub-items
        List<String> contentUrls = activity.contentUrls();
        if (contentUrls != null && !contentUrls.isEmpty()) {
            report.append("\n");
            for (String url : contentUrls) {
                report.append(String.format("  %s\n", url));
            }
        }

        // Add description if present (for additional text content)
        if (activity.description() != null && !activity.description().isEmpty()) {
            report.append("\n");
            String[] lines = activity.description().split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty()) {
                    report.append(String.format("  %s\n", line));
                }
            }
        }

        report.append("\n");
    }
}
