package activityreport.providers;

import activityreport.config.AppConfig;
import activityreport.model.ActionCategory;
import io.quarkus.logging.Log;
import org.kohsuke.github.GHIssue;
import org.kohsuke.github.GHPullRequest;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Encapsulates compiled category filters for efficient matching
 */
class CategoryFilterFunction {
    private final List<CompiledFilter> filters;

    private record CompiledFilter(
            ActionCategory category,
            Optional<Pattern> titlePattern,
            Optional<String> label,
            Optional<String> author
    ) {
    }

    CategoryFilterFunction(List<AppConfig.Github.GithubInstance.CategoryFilter> filterConfigs) {
        this.filters = filterConfigs.stream()
                .map(config -> {
                    ActionCategory category;
                    try {
                        category = ActionCategory.valueOf(config.category().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(String.format(Locale.ROOT, "Invalid category '%s' in filter, skipping. Valid values: CODE, REVIEW, DISCUSS, CHORE",
                                config.category()), e);
                    }

                    Optional<Pattern> titlePattern = config.titlePattern().map(pattern -> {
                        try {
                            return Pattern.compile(pattern);
                        } catch (Exception e) {
                            throw new IllegalArgumentException(String.format(Locale.ROOT, "Invalid title pattern '%s': %s", pattern, e.getMessage()), e);
                        }
                    });

                    return new CompiledFilter(category, titlePattern, config.label(), config.author());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Check if the given issue matches any filter and return the category
     */
    Optional<ActionCategory> matchIssue(GHIssue issue) {
        for (var filter : filters) {
            if (matches(issue, filter)) {
                return Optional.of(filter.category);
            }
        }
        return Optional.empty();
    }

    /**
     * Check if the given PR matches any filter and return the category
     */
    Optional<ActionCategory> matchPullRequest(GHPullRequest pr) {
        for (var filter : filters) {
            if (matches(pr, filter)) {
                return Optional.of(filter.category);
            }
        }
        return Optional.empty();
    }

    private boolean matches(GHIssue issue, CompiledFilter filter) {
        // Check title pattern if specified
        if (filter.titlePattern.isPresent()) {
            if (!filter.titlePattern.get().matcher(issue.getTitle()).find()) {
                return false;
            }
        }

        // Check label if specified
        if (filter.label.isPresent()) {
            try {
                boolean hasLabel = issue.getLabels().stream()
                        .anyMatch(label -> label.getName().equals(filter.label.get()));
                if (!hasLabel) {
                    return false;
                }
            } catch (Exception e) {
                Log.tracef("Failed to check labels for issue: %s", e.getMessage());
                return false;
            }
        }

        // Check author if specified
        if (filter.author.isPresent()) {
            try {
                String author = issue.getUser().getLogin();
                if (!author.equals(filter.author.get())) {
                    return false;
                }
            } catch (Exception e) {
                Log.tracef("Failed to check author for issue: %s", e.getMessage());
                return false;
            }
        }

        return true;
    }

    private boolean matches(GHPullRequest pr, CompiledFilter filter) {
        // Check title pattern if specified
        if (filter.titlePattern.isPresent()) {
            if (!filter.titlePattern.get().matcher(pr.getTitle()).find()) {
                return false;
            }
        }

        // Check label if specified
        if (filter.label.isPresent()) {
            try {
                boolean hasLabel = pr.getLabels().stream()
                        .anyMatch(label -> label.getName().equals(filter.label.get()));
                if (!hasLabel) {
                    return false;
                }
            } catch (Exception e) {
                Log.tracef("Failed to check labels for PR: %s", e.getMessage());
                return false;
            }
        }

        // Check author if specified
        if (filter.author.isPresent()) {
            try {
                String author = pr.getUser().getLogin();
                if (!author.equals(filter.author.get())) {
                    return false;
                }
            } catch (Exception e) {
                Log.tracef("Failed to check author for PR: %s", e.getMessage());
                return false;
            }
        }

        return true;
    }
}
