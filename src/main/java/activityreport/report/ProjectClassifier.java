package activityreport.report;

import activityreport.config.AppConfig;
import activityreport.model.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Classifies activities into projects based on URL patterns.
 */
public class ProjectClassifier {
    private final List<ProjectMatcher> projectMatchers;

    public ProjectClassifier(AppConfig config) {
        this.projectMatchers = new ArrayList<>();

        config.projects().ifPresent(projects -> {
            for (var project : projects) {
                List<Pattern> patterns = new ArrayList<>();
                project.urlPatterns().ifPresent(urlPatterns -> {
                    for (String urlPattern : urlPatterns) {
                        // Convert glob-like patterns to regex
                        String regex = convertToRegex(urlPattern);
                        patterns.add(Pattern.compile(regex));
                    }
                });
                projectMatchers.add(new ProjectMatcher(project.name(), patterns));
            }
        });
    }

    public Optional<String> classifyActivity(Activity activity) {
        // If already classified, return existing project
        if (activity.project() != null && !activity.project().isEmpty()) {
            return Optional.of(activity.project());
        }

        // Try to match the activity URL
        String activityUrl = activity.url();
        if (activityUrl != null && !activityUrl.isEmpty()) {
            Optional<String> match = matchUrl(activityUrl);
            if (match.isPresent()) {
                return match;
            }
        }

        // Try to match content URLs
        List<String> contentUrls = activity.contentUrls();
        if (contentUrls != null && !contentUrls.isEmpty()) {
            for (String url : contentUrls) {
                Optional<String> match = matchUrl(url);
                if (match.isPresent()) {
                    return match;
                }
            }
        }

        // Try to match URLs in the description (for backward compatibility)
        String description = activity.description();
        if (description != null && !description.isEmpty()) {
            // Extract URLs from description (simple heuristic: look for http/https)
            String[] lines = description.split("\n");
            for (String line : lines) {
                if (line.contains("http://") || line.contains("https://")) {
                    // Extract the URL part
                    String[] parts = line.split("\\s+");
                    for (String part : parts) {
                        if (part.startsWith("http://") || part.startsWith("https://")) {
                            Optional<String> match = matchUrl(part);
                            if (match.isPresent()) {
                                return match;
                            }
                        }
                    }
                }
            }
        }

        // Fall back to default project if configured (backward compatibility)
        Object defaultProject = activity.metadata().get("defaultProject");
        if (defaultProject instanceof String) {
            return Optional.of((String) defaultProject);
        }

        return Optional.empty();
    }

    private Optional<String> matchUrl(String url) {
        for (ProjectMatcher matcher : projectMatchers) {
            if (matcher.matches(url)) {
                return Optional.of(matcher.projectName);
            }
        }
        return Optional.empty();
    }

    private String convertToRegex(String pattern) {
        // Convert simple glob-like patterns to regex
        // e.g., "https://github.com/quarkusio/*" -> "https://github\.com/quarkusio/.*"
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '.' -> regex.append("\\.");
                case '?' -> regex.append(".");
                case '/' -> regex.append("/");
                case ':' -> regex.append(":");
                case '-' -> regex.append("-");
                default -> {
                    if (Character.isLetterOrDigit(c)) {
                        regex.append(c);
                    } else {
                        regex.append("\\").append(c);
                    }
                }
            }
        }
        return regex.toString();
    }

    private record ProjectMatcher(String projectName, List<Pattern> patterns) {
        boolean matches(String url) {
            for (Pattern pattern : patterns) {
                if (pattern.matcher(url).find()) {
                    return true;
                }
            }
            return false;
        }
    }
}
