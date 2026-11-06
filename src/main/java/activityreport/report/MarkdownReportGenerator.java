package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;

import java.util.*;

/**
 * Markdown report generator working with grouped activities
 */
public class MarkdownReportGenerator {

    public static String generate(List<ActivityGroup> groups, List<String> projectOrder) {
        StringBuilder report = new StringBuilder();

        // Organize groups by project, preserving configuration order
        Map<String, List<ActivityGroup>> byProject = new LinkedHashMap<>();
        byProject.put("General", new ArrayList<>()); // Always empty, manually filled
        for (String project : projectOrder) {
            byProject.put(project, new ArrayList<>());
        }

        List<ActivityGroup> reviewGroups = new ArrayList<>();
        List<ActivityGroup> triageDiscussGroups = new ArrayList<>();
        List<ActivityGroup> choreGroups = new ArrayList<>();

        for (ActivityGroup group : groups) {
            Activity primary = group.primary();
            ActionCategory actionCategory = primary.actionCategory();
            String project = primary.project();

            // CODE activities go to project sections (or "Unclassified" if no project)
            if (actionCategory == ActionCategory.CODE) {
                String projectKey = (project != null && !project.isEmpty()) ? project : "Unclassified";
                byProject.computeIfAbsent(projectKey, k -> new ArrayList<>()).add(group);
            } else if (actionCategory == ActionCategory.CHORE) {
                choreGroups.add(group);
            } else if (actionCategory == ActionCategory.REVIEW) {
                reviewGroups.add(group);
            } else {
                // DISCUSS and others
                triageDiscussGroups.add(group);
            }
        }

        // Generate General section (always empty)
        report.append("# General\n\n");
        report.append("(To be filled manually)\n\n");
        report.append("----\n");

        // Generate Project sections
        for (Map.Entry<String, List<ActivityGroup>> entry : byProject.entrySet()) {
            String project = entry.getKey();
            if ("\nGeneral".equals(project)) {
                continue; // Already handled
            }

            List<ActivityGroup> projectGroups = entry.getValue();
            if (projectGroups.isEmpty()) {
                continue;
            }

            report.append(String.format("\n# Project: %s\n\n", project));

            for (ActivityGroup group : projectGroups) {
                formatGroup(report, group);
            }

            report.append("\n----\n");
        }

        // Generate Misc section (reviews, triage/discussions, chores)
        if (!reviewGroups.isEmpty() || !triageDiscussGroups.isEmpty() || !choreGroups.isEmpty()) {
            report.append("\n# Misc\n");

            if (!reviewGroups.isEmpty()) {
                report.append("\nReviews\n\n");
                for (ActivityGroup group : reviewGroups) {
                    formatGroup(report, group);
                }
            }

            if (!triageDiscussGroups.isEmpty()) {
                report.append("\nTriage, discussions\n\n");
                for (ActivityGroup group : triageDiscussGroups) {
                    formatGroup(report, group);
                }
            }

            if (!choreGroups.isEmpty()) {
                report.append("\nChores\n\n");
                for (ActivityGroup group : choreGroups) {
                    formatGroup(report, group);
                }
            }
        }

        return report.toString();
    }

    private static void formatGroup(StringBuilder report, ActivityGroup group) {
        Activity primary = group.primary();

        // Primary activity line with optional status prefix and link
        String statusPrefix = "";
        Object prStatus = primary.metadata().get("prStatus");
        if (prStatus instanceof String s) {
            statusPrefix = "[" + s + "] ";
        }
        if (primary.url() != null && !primary.url().isEmpty()) {
            report.append(String.format("* %s[%s](%s)\n", statusPrefix, primary.title(), primary.url()));
        } else {
            report.append(String.format("* %s%s\n", statusPrefix, primary.title()));
        }

        // Add description if present
        if (primary.description() != null && !primary.description().isEmpty()) {
            String[] lines = primary.description().split("\n");
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty()) {
                    report.append(String.format("  %s\n", line));
                }
            }
        }

        // Add secondary activities as nested list items
        for (Activity secondary : group.secondary()) {
            String secStatusPrefix = "";
            Object secPrStatus = secondary.metadata().get("prStatus");
            if (secPrStatus instanceof String s) {
                secStatusPrefix = "[" + s + "] ";
            }
            if (secondary.url() != null && !secondary.url().isEmpty()) {
                report.append(String.format("    * %s[%s](%s)\n", secStatusPrefix, secondary.title(), secondary.url()));
            } else {
                report.append(String.format("    * %s%s\n", secStatusPrefix, secondary.title()));
            }
        }
    }
}
