package activityreport.report;

import activityreport.client.AIRestClient;
import activityreport.client.TraceClientLogger;
import activityreport.config.AppConfig;
import org.jboss.resteasy.reactive.client.api.LoggingScope;
import activityreport.model.Activity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;

import java.net.URI;
import java.util.*;

/**
 * AI Processor for enriching and grouping activities
 */
public class AIProcessor {
    private final AIRestClient client;
    private final String modelName;
    private final ObjectMapper mapper;
    private final Set<String> availableProjects;

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

        // Extract available projects from config
        this.availableProjects = new LinkedHashSet<>();
        config.projects().ifPresent(projects ->
            projects.forEach(project -> availableProjects.add(project.name()))
        );

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

    public boolean isAvailable() {
        return modelName != null;
    }

    /**
     * Enrich activities by adding descriptions and projects where missing.
     * Returns a new list with enriched activities.
     */
    public List<Activity> enrichActivities(List<Activity> activities) {
        if (!isAvailable()) {
            return activities;
        }

        try {
            Log.info("AI: Enriching activities with descriptions and projects...");

            // Prepare request
            List<Map<String, Object>> activitiesJson = new ArrayList<>();
            for (int i = 0; i < activities.size(); i++) {
                Activity activity = activities.get(i);
                Map<String, Object> activityMap = new LinkedHashMap<>();
                activityMap.put("index", i);
                activityMap.put("source", activity.source());
                activityMap.put("action", activity.action());
                activityMap.put("actionCategory", activity.actionCategory().name());
                activityMap.put("title", activity.title());
                activityMap.put("description", activity.description());
                activityMap.put("url", activity.url());
                activityMap.put("contentUrls", activity.contentUrls());
                activityMap.put("project", activity.project());
                activitiesJson.add(activityMap);
            }

            Map<String, Object> requestData = new LinkedHashMap<>();
            requestData.put("activities", activitiesJson);
            requestData.put("availableProjects", availableProjects);

            String prompt = """
                You are enriching developer activities with descriptions and project assignments.

                For each activity:
                1. If it has NO description or an empty description, generate a concise 1-2 sentence description
                2. If it has NO project assigned, try to assign it to one of the available projects based on the URLs and content

                Base your decisions on:
                - The activity's title, URLs (main and content URLs)
                - Only assign projects that are in the availableProjects list
                - Only provide descriptions for activities that need them
                - Keep descriptions professional and concise

                Activities data:
                %s

                Return ONLY valid JSON (no markdown formatting) with this structure:
                {
                  "descriptions": [
                    {"index": 0, "description": "..."}
                  ],
                  "projects": [
                    {"index": 1, "project": "ProjectName"}
                  ]
                }

                If no enrichments are needed, return: {"descriptions": [], "projects": []}
                """.formatted(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(requestData));

            String response = callAIModel(prompt);
            JsonNode enrichments = parseJsonResponse(response);

            // Apply enrichments
            List<Activity> enriched = new ArrayList<>(activities);

            // Apply descriptions
            if (enrichments.has("descriptions")) {
                for (JsonNode desc : enrichments.get("descriptions")) {
                    int index = desc.get("index").asInt();
                    String description = desc.get("description").asText();
                    Activity original = enriched.get(index);
                    enriched.set(index, new Activity(
                        original.source(),
                        original.action(),
                        original.actionCategory(),
                        original.title(),
                        description,
                        original.url(),
                        original.timestamp(),
                        original.contentUrls(),
                        original.project(),
                        original.metadata()
                    ));
                }
            }

            // Apply projects
            if (enrichments.has("projects")) {
                for (JsonNode proj : enrichments.get("projects")) {
                    int index = proj.get("index").asInt();
                    String project = proj.get("project").asText();
                    Activity original = enriched.get(index);
                    enriched.set(index, new Activity(
                        original.source(),
                        original.action(),
                        original.actionCategory(),
                        original.title(),
                        original.description(),
                        original.url(),
                        original.timestamp(),
                        original.contentUrls(),
                        project,
                        original.metadata()
                    ));
                }
            }

            Log.infof("AI: Enriched %d descriptions and %d projects",
                enrichments.has("descriptions") ? enrichments.get("descriptions").size() : 0,
                enrichments.has("projects") ? enrichments.get("projects").size() : 0);

            return enriched;

        } catch (Exception e) {
            Log.warnf("AI enrichment failed: %s", e.getMessage());
            return activities;
        }
    }

    /**
     * Group related activities together.
     * Returns groups of activities.
     */
    public List<ActivityGroup> groupActivities(List<Activity> activities) {
        if (!isAvailable()) {
            return SimpleGrouper.groupActivities(activities);
        }

        try {
            Log.info("AI: Grouping related activities...");

            // Prepare request
            List<Map<String, Object>> activitiesJson = new ArrayList<>();
            for (int i = 0; i < activities.size(); i++) {
                Activity activity = activities.get(i);
                Map<String, Object> activityMap = new LinkedHashMap<>();
                activityMap.put("index", i);
                activityMap.put("source", activity.source());
                activityMap.put("action", activity.action());
                activityMap.put("actionCategory", activity.actionCategory().name());
                activityMap.put("title", activity.title());
                activityMap.put("description", truncate(activity.description(), 300));
                activityMap.put("url", activity.url());
                activityMap.put("contentUrls", activity.contentUrls());
                activityMap.put("project", activity.project());
                activitiesJson.add(activityMap);
            }

            String prompt = """
                You are grouping related developer activities.

                Group activities that are related based on:
                1. Shared URLs (primary URL or content URLs) - this is the PRIMARY signal
                2. Same project
                3. Related titles/descriptions

                Guidelines:
                - Activities sharing a URL should ALWAYS be grouped together
                - Within each group, choose ONE primary activity (preferably CODE category)
                - All others in the group are secondary
                - Activities can only be in one group
                - Some activities may remain ungrouped

                Activities data:
                %s

                Return ONLY valid JSON (no markdown formatting) with this structure:
                {
                  "groups": [
                    {
                      "primaryIndex": 0,
                      "secondaryIndices": [1, 2]
                    }
                  ]
                }

                If no grouping is needed, return: {"groups": []}
                """.formatted(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(activitiesJson));

            String response = callAIModel(prompt);
            JsonNode groupsData = parseJsonResponse(response);

            // Build groups
            List<ActivityGroup> groups = new ArrayList<>();
            Set<Integer> grouped = new HashSet<>();

            if (groupsData.has("groups")) {
                for (JsonNode groupNode : groupsData.get("groups")) {
                    int primaryIndex = groupNode.get("primaryIndex").asInt();
                    Activity primary = activities.get(primaryIndex);
                    grouped.add(primaryIndex);

                    List<Activity> secondary = new ArrayList<>();
                    if (groupNode.has("secondaryIndices")) {
                        for (JsonNode secNode : groupNode.get("secondaryIndices")) {
                            int secIndex = secNode.asInt();
                            secondary.add(activities.get(secIndex));
                            grouped.add(secIndex);
                        }
                    }

                    groups.add(new ActivityGroup(primary, secondary));
                }
            }

            // Add ungrouped activities
            for (int i = 0; i < activities.size(); i++) {
                if (!grouped.contains(i)) {
                    groups.add(new ActivityGroup(activities.get(i), List.of()));
                }
            }

            Log.infof("AI: Created %d groups from %d activities", groups.size(), activities.size());

            return groups;

        } catch (Exception e) {
            Log.warnf("AI grouping failed: %s", e.getMessage());
            return SimpleGrouper.groupActivities(activities);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private String callAIModel(String prompt) throws Exception {
        // Build request body
        var requestBody = new LinkedHashMap<String, Object>();
        requestBody.put("model", modelName);
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", prompt)
        ));
        requestBody.put("temperature", 0.3); // Lower temperature for more consistent JSON
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

    private JsonNode parseJsonResponse(String response) throws Exception {
        // Strip markdown code fences if present
        String cleaned = response.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        return mapper.readTree(cleaned);
    }
}
