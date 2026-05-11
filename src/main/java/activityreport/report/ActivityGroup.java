package activityreport.report;

import activityreport.model.Activity;

import java.util.List;

/**
 * Represents a group of related activities
 */
public record ActivityGroup(
    Activity primary,
    List<Activity> secondary
) {
    public ActivityGroup {
        if (primary == null) {
            throw new IllegalArgumentException("Primary activity cannot be null");
        }
        secondary = secondary != null ? List.copyOf(secondary) : List.of();
    }
}
