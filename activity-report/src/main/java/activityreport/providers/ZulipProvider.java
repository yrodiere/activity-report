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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Zulip Activity Provider supporting multiple instances
 */
public class ZulipProvider implements ActivityProvider {
    private final List<ZulipInstance> instances;

    private record ZulipInstance(String url, String email, String apiKey, String defaultProject) {}

    public ZulipProvider(AppConfig config) {
        this.instances = new ArrayList<>();

        config.providers().zulip().ifPresent(zulip -> {
            if (zulip.enabled() && zulip.instances() != null) {
                for (var instance : zulip.instances()) {
                    instances.add(new ZulipInstance(
                        instance.url(),
                        instance.email(),
                        instance.apiKey(),
                        instance.defaultProject().orElse(null)
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

        // Group messages by topic (stream + subject)
        Map<TopicRef, TopicMessages> topicMap = new HashMap<>();

        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                String messageType = message.get("type").asText();

                // Skip DMs (private messages)
                if ("private".equals(messageType)) {
                    continue;
                }

                long timestamp = message.get("timestamp").asLong();
                Instant messageTime = Instant.ofEpochSecond(timestamp);

                // Skip if outside date range
                if (messageTime.isBefore(startDate) || messageTime.isAfter(endDate)) {
                    continue;
                }

                String streamName = message.get("display_recipient").asText();
                String subject = message.get("subject").asText();
                int messageId = message.get("id").asInt();

                TopicRef topicRef = new TopicRef(streamName, subject);
                TopicMessages topicMessages = topicMap.computeIfAbsent(topicRef,
                    k -> new TopicMessages(streamName, subject, new ArrayList<>()));

                topicMessages.messageIds.add(messageId);
                topicMessages.updateTimestamp(messageTime);
            }
        }

        // Create one activity per topic
        String source = "Zulip - " + instance.url.replace("https://", "").replace("http://", "");

        for (TopicMessages topic : topicMap.values()) {
            try {
                // Collect message URLs
                List<String> contentUrls = new ArrayList<>();
                for (int messageId : topic.messageIds) {
                    String messageUrl = buildMessageUrl(instance.url, topic.streamName, topic.subject, messageId);
                    contentUrls.add(messageUrl);
                }

                String topicUrl = buildTopicUrl(instance.url, topic.streamName, topic.subject);

                Activity activity = new Activity(
                    source,
                    "topic",
                    topic.streamName + " / " + topic.subject,
                    "", // description
                    topicUrl,
                    topic.latestTimestamp,
                    contentUrls
                );

                // Add default project if configured
                if (instance.defaultProject != null) {
                    activity.addMetadata("defaultProject", instance.defaultProject);
                }

                activities.add(activity);
            } catch (Exception e) {
                Log.tracef("Failed to create activity for topic %s/%s: %s",
                    topic.streamName, topic.subject, e.getMessage());
            }
        }

        return activities;
    }

    private record TopicRef(String streamName, String subject) {}

    private static class TopicMessages {
        final String streamName;
        final String subject;
        final List<Integer> messageIds;
        Instant latestTimestamp;

        TopicMessages(String streamName, String subject, List<Integer> messageIds) {
            this.streamName = streamName;
            this.subject = subject;
            this.messageIds = messageIds;
        }

        void updateTimestamp(Instant timestamp) {
            if (latestTimestamp == null || timestamp.isAfter(latestTimestamp)) {
                latestTimestamp = timestamp;
            }
        }
    }

    private String buildTopicUrl(String baseUrl, String streamName, String subject) {
        try {
            String encodedStream = URLEncoder.encode(streamName, StandardCharsets.UTF_8);
            String encodedSubject = URLEncoder.encode(subject, StandardCharsets.UTF_8);
            return baseUrl + "/#narrow/stream/" + encodedStream + "/topic/" + encodedSubject;
        } catch (Exception e) {
            // Fallback to non-encoded if encoding fails
            return baseUrl + "/#narrow/stream/" + streamName + "/topic/" + subject;
        }
    }

    private String buildMessageUrl(String baseUrl, String streamName, String subject, int messageId) {
        try {
            String encodedStream = URLEncoder.encode(streamName, StandardCharsets.UTF_8);
            String encodedSubject = URLEncoder.encode(subject, StandardCharsets.UTF_8);
            return baseUrl + "/#narrow/stream/" + encodedStream + "/topic/" + encodedSubject + "/near/" + messageId;
        } catch (Exception e) {
            // Fallback to non-encoded if encoding fails
            return baseUrl + "/#narrow/stream/" + streamName + "/topic/" + subject + "/near/" + messageId;
        }
    }
}
