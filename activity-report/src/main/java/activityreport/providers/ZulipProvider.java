package activityreport.providers;

import activityreport.client.BasicAuthRequestFilter;
import activityreport.client.ZulipRestClient;
import activityreport.config.AppConfig;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Zulip Activity Provider supporting multiple instances
 */
public class ZulipProvider implements ActivityProvider {
    private final List<ZulipInstance> instances;

    private record ZulipInstance(String url, String email, String apiKey) {}

    public ZulipProvider(AppConfig config) {
        this.instances = new ArrayList<>();

        config.providers().zulip().ifPresent(zulip -> {
            if (zulip.enabled() && zulip.instances() != null) {
                for (var instance : zulip.instances()) {
                    instances.add(new ZulipInstance(
                        instance.url(),
                        instance.email(),
                        instance.apiKey()
                    ));
                }
            }
        });
    }

    @Override
    public String getName() {
        return "Zulip (all instances)";
    }

    @Override
    public boolean isConfigured() {
        return !instances.isEmpty();
    }

    @Override
    public List<Activity> fetchActivities(Instant startDate, Instant endDate) throws Exception {
        List<Activity> allActivities = new ArrayList<>();

        for (ZulipInstance instance : instances) {
            try {
                allActivities.addAll(fetchFromInstance(instance, startDate, endDate));
            } catch (Exception e) {
                Log.warnf("Error fetching from Zulip instance %s: %s", instance.url, e.getMessage());
            }
        }

        return allActivities;
    }

    private List<Activity> fetchFromInstance(ZulipInstance instance, Instant startDate, Instant endDate) throws Exception {
        List<Activity> activities = new ArrayList<>();

        // Build REST client for this instance
        var client = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(instance.url))
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .register(new BasicAuthRequestFilter(instance.email, instance.apiKey))
            .build(ZulipRestClient.class);

        // Get current user
        var userRoot = client.getCurrentUser();
        int userId = userRoot.get("user_id").asInt();

        // Fetch messages sent by this user
        var narrow = String.format("[{\"operator\":\"sender\",\"operand\":%d}]", userId);
        var messagesRoot = client.getMessages("newest", 1000, 0, narrow);
        var messages = messagesRoot.get("messages");

        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                long timestamp = message.get("timestamp").asLong();
                Instant messageTime = Instant.ofEpochSecond(timestamp);

                // Skip if outside date range
                if (messageTime.isBefore(startDate) || messageTime.isAfter(endDate)) {
                    continue;
                }

                String subject = message.get("subject").asText();
                String content = message.get("content").asText();
                String messageType = message.get("type").asText();
                int messageId = message.get("id").asInt();

                // Build message URL
                String streamName = messageType.equals("stream") ?
                    message.get("display_recipient").asText() : "private";
                String messageUrl = instance.url + "/#narrow/stream/" + streamName + "/topic/" + subject + "/near/" + messageId;

                Activity activity = new Activity(
                    "Zulip - " + instance.url.replace("https://", "").replace("http://", ""),
                    "message",
                    "Message in " + streamName + ": " + subject,
                    content.length() > 200 ? content.substring(0, 200) + "..." : content,
                    messageUrl,
                    messageTime
                );

                activity.addMetadata("messageType", messageType);
                activities.add(activity);
            }
        }

        return activities;
    }
}
