package activityreport.report;

import activityreport.model.ActionCategory;
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

        // Group by project and action category
        Map<String, List<Activity>> byProject = new LinkedHashMap<>();
        byProject.put("General", new ArrayList<>()); // Always empty, manually filled

        List<Activity> miscActivities = new ArrayList<>();

        for (Activity activity : activities) {
            ActionCategory actionCategory = activity.actionCategory();
            String project = (String) activity.metadata().get("project");

            // Only CODE activities go to projects, others go to Misc
            if (actionCategory == ActionCategory.CODE && project != null && !project.isEmpty()) {
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

        // Generate Misc section (reviews, discussions)
        if (!miscActivities.isEmpty()) {
            report.append("# Misc\n\n");
            report.append("Reviews, triage, discussions\n\n");

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

        // Add description if present
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
