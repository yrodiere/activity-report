package activityreport.report;

import activityreport.model.ActionCategory;
import activityreport.model.Activity;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple URL-based activity grouper (fallback when AI is not available)
 */
public class SimpleGrouper {

    private static int activityPriorityRank(Activity a) {
        return switch (a.actionCategory()) {
            case CODE -> Boolean.TRUE.equals(a.metadata().get("targetsDefaultBranch")) ? 0 : 1;
            case REVIEW -> 2;
            case DISCUSS -> 3;
            case CHORE -> 4;
        };
    }

    private static final Comparator<Activity> ACTIVITY_PRIORITY =
        Comparator.comparingInt(SimpleGrouper::activityPriorityRank);

    public static List<ActivityGroup> groupActivities(List<Activity> activities) {
        // Build URL -> Activities map
        Map<String, Set<Activity>> urlToActivities = new HashMap<>();

        for (Activity activity : activities) {
            Set<String> urls = new HashSet<>();

            // Add primary URL
            if (activity.url() != null && !activity.url().isEmpty()) {
                urls.add(activity.url());
            }

            // Add content URLs
            if (activity.contentUrls() != null) {
                urls.addAll(activity.contentUrls());
            }

            // Add to map for each URL
            for (String url : urls) {
                urlToActivities.computeIfAbsent(url, k -> new HashSet<>()).add(activity);
            }
        }

        // Merge groups that share activities (transitive closure)
        Map<Activity, Set<Activity>> activityToGroup = new HashMap<>();

        for (Set<Activity> relatedActivities : urlToActivities.values()) {
            if (relatedActivities.size() < 2) {
                continue; // Not a group, just a single activity
            }

            // Find all existing groups that contain any of these activities
            Set<Activity> mergedGroup = new HashSet<>(relatedActivities);
            for (Activity activity : relatedActivities) {
                if (activityToGroup.containsKey(activity)) {
                    mergedGroup.addAll(activityToGroup.get(activity));
                }
            }

            // Update all activities in the merged group to point to the same group
            for (Activity activity : mergedGroup) {
                activityToGroup.put(activity, mergedGroup);
            }
        }

        // Create unique groups
        Set<Set<Activity>> uniqueGroups = new HashSet<>(activityToGroup.values());
        List<ActivityGroup> groups = new ArrayList<>();

        for (Set<Activity> groupSet : uniqueGroups) {
            if (groupSet.isEmpty()) {
                continue;
            }

            List<Activity> sorted = groupSet.stream()
                .sorted(ACTIVITY_PRIORITY)
                .collect(Collectors.toCollection(ArrayList::new));

            groups.add(new ActivityGroup(sorted.removeFirst(), sorted));
        }

        // Add ungrouped activities as single-item groups
        Set<Activity> allGrouped = activityToGroup.keySet();
        for (Activity activity : activities) {
            if (!allGrouped.contains(activity)) {
                groups.add(new ActivityGroup(activity, List.of()));
            }
        }

        return groups;
    }
}
