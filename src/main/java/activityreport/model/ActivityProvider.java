package activityreport.model;

import java.time.Instant;
import java.util.List;

/**
 * Interface for activity providers
 */
public interface ActivityProvider {
    String getName();
    List<Activity> fetchActivities(Instant startDate, Instant endDate) throws Exception;
    boolean isConfigured();
}
