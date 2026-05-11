package activityreport.model;

import activityreport.util.UrlExtractor;

import java.time.Instant;
import java.util.List;

/**
 * Interface for activity providers
 */
public interface ActivityProvider {
    String getName();
    List<Activity> fetchActivities(Instant startDate, Instant endDate, UrlExtractor urlExtractor) throws Exception;
    boolean isConfigured();
}
