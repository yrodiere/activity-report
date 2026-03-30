package activityreport.report;

import activityreport.client.AIRestClient;
import activityreport.client.TraceClientLogger;
import activityreport.config.AppConfig;
import org.jboss.resteasy.reactive.client.api.LoggingScope;
import activityreport.model.Activity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI Processor for generating intelligent, grouped activity reports
 */
public class AIProcessor {
    private final AIRestClient client;
    private final String modelName;
    private final ObjectMapper mapper;

    public AIProcessor(AppConfig config) {
        String aiUrl = config.ai()
            .flatMap(ai -> ai.url())
            .orElse("http://localhost:8000/v1");

        // Build REST client
        this.client = QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(aiUrl))
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .loggingScope(LoggingScope.REQUEST_RESPONSE)
            .clientLogger(new TraceClientLogger())
            .build(AIRestClient.class);

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        // Auto-detect model if not specified
        var configuredModel = config.ai()
            .flatMap(ai -> ai.model())
            .orElse("auto");
        if (configuredModel.equals("auto")) {
            this.modelName = detectModel();
        } else {
            this.modelName = configuredModel;
        }

        if (this.modelName != null) {
            Log.infof("Using AI model: %s", this.modelName);
        }
    }

    private String detectModel() {
        try {
            var root = client.listModels();
            var data = root.get("data");
            if (data != null && data.isArray() && !data.isEmpty()) {
                var model = data.get(0).get("id").asText();
                Log.infof("Auto-detected AI model: %s", model);
                return model;
            }
        } catch (Exception e) {
            Log.warnf("Could not auto-detect AI model: %s", e.getMessage());
        }
        return null;
    }

    public String generateGroupedReport(List<Activity> activities, Instant startDate, Instant endDate) {
        if (modelName == null) {
            Log.info("AI model not available, falling back to simple markdown");
            return MarkdownReportGenerator.generateSimple(activities, startDate, endDate);
        }

        try {
            // Prepare activities as JSON
            String activitiesJson = serializeActivities(activities);

            // Build prompt
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy")
                .withZone(ZoneId.systemDefault());

            String prompt = """
                You are analyzing a developer's activity from %s to %s.

                Your task is to create a concise, achievement-oriented activity report. \
                Group related activities into coherent achievements. For example, an issue, pull request, \
                and commits related to the same feature should be grouped together as one achievement.

                Guidelines:
                - Focus on WHAT was accomplished, not just listing actions
                - Group related items (e.g., issue + PR + commits = one achievement)
                - Use clear, professional language
                - Include relevant links from the activities
                - Be concise but informative

                Activities data (JSON):
                %s

                Generate a markdown report with:
                1. A brief summary paragraph (2-3 sentences)
                2. Main achievements grouped by theme (use ## headers)
                3. Include links to relevant issues/PRs where available
                4. Keep it professional and concise

                Return ONLY the markdown report, nothing else.
                """.formatted(
                    dateFormatter.format(startDate),
                    dateFormatter.format(endDate),
                    activitiesJson
                );

            // Make API call
            return callAIModel(prompt);

        } catch (Exception e) {
            Log.warnf("AI processing failed: %s", e.getMessage());
            Log.info("Falling back to simple markdown generation");
            return MarkdownReportGenerator.generateSimple(activities, startDate, endDate);
        }
    }

    private String serializeActivities(List<Activity> activities) throws Exception {
        // Create simplified JSON representation of activities
        List<Map<String, Object>> simplified = activities.stream()
            .sorted(Comparator.comparing(Activity::timestamp).reversed())
            .map(activity -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("source", activity.source());
                map.put("action", activity.action());
                map.put("title", activity.title());
                if (activity.description() != null && !activity.description().isEmpty()) {
                    // Truncate very long descriptions
                    String desc = activity.description();
                    if (desc.length() > 500) {
                        desc = desc.substring(0, 500) + "...";
                    }
                    map.put("description", desc);
                }
                if (activity.url() != null && !activity.url().isEmpty()) {
                    map.put("url", activity.url());
                }
                if (activity.contentUrls() != null && !activity.contentUrls().isEmpty()) {
                    map.put("contentUrls", activity.contentUrls());
                }
                map.put("timestamp", activity.timestamp().toString());
                return map;
            })
            .collect(Collectors.toList());

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(simplified);
    }

    private String callAIModel(String prompt) throws Exception {
        // Build request body
        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", modelName);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 2000);

        // Make API call
        var root = client.chatCompletion(requestBody);

        // Parse response
        var choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            var message = choices.get(0).get("message");
            if (message != null) {
                return message.get("content").asText();
            }
        }

        throw new RuntimeException("Unexpected AI API response format");
    }
}
