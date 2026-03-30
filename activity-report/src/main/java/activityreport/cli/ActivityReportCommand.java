package activityreport.cli;

import activityreport.config.AppConfig;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import activityreport.providers.GitHubProvider;
import activityreport.providers.JiraProvider;
import activityreport.providers.ZulipProvider;
import activityreport.report.AIProcessor;
import activityreport.report.MarkdownReportGenerator;
import activityreport.report.ProjectClassifier;
import io.quarkus.logging.Log;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@TopCommand
@Command(
    name = "report",
    mixinStandardHelpOptions = true,
    versionProvider = ActivityReportCommand.ManifestVersionProvider.class,
    description = "Generate activity reports from GitHub, JIRA, Zulip, and other sources"
)
public class ActivityReportCommand implements Runnable {

    @Inject
    AppConfig config;

    @Option(names = {"-d", "--days"}, description = "Number of days to look back (default: 7)")
    private int days = 7;

    @Option(names = {"--start-date"}, description = "Start date (ISO format: YYYY-MM-DD)")
    private String startDateStr;

    @Option(names = {"--end-date"}, description = "End date (ISO format: YYYY-MM-DD)")
    private String endDateStr;

    @Option(names = {"--no-ai"}, description = "Disable AI processing and use simple markdown generation")
    private boolean noAi = false;

    /**
     * Validate the configuration file exists.
     * Detailed validation is handled by Hibernate Validator at startup.
     */
    private void validateConfig() {
        if (config == null || config.providers() == null) {
            throw new IllegalStateException(
                "Configuration file not found. Please create a configuration file at: " +
                activityreport.config.XdgYamlConfigSource.getDefaultConfigPath() + "\n" +
                "See config.yaml.example for reference."
            );
        }
        // All other validation handled by Hibernate Validator at startup
    }

    @Override
    public void run() {
        try {
            Log.info("Activity Report Generator");
            Log.info("=========================\n");

            // Validate configuration
            validateConfig();
            Log.info("Configuration loaded successfully.\n");

            // Determine date range
            Instant startDate, endDate;
            if (startDateStr != null && endDateStr != null) {
                startDate = LocalDate.parse(startDateStr).atStartOfDay(ZoneId.systemDefault()).toInstant();
                endDate = LocalDate.parse(endDateStr).atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant();
            } else {
                endDate = Instant.now();
                startDate = endDate.minus(Duration.ofDays(days));
            }

            DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault());
            Log.infof("Fetching activities from %s to %s\n",
                     formatter.format(startDate), formatter.format(endDate));

            // Initialize providers
            List<ActivityProvider> providers = new ArrayList<>();

            if (config.providers().github().map(g -> g.enabled()).orElse(false)) {
                var githubProvider = new GitHubProvider(config);
                if (githubProvider.isConfigured()) {
                    providers.add(githubProvider);
                }
            }

            if (config.providers().jira().map(j -> j.enabled()).orElse(false)) {
                var jiraProvider = new JiraProvider(config);
                if (jiraProvider.isConfigured()) {
                    providers.add(jiraProvider);
                }
            }

            if (config.providers().zulip().map(z -> z.enabled()).orElse(false)) {
                var zulipProvider = new ZulipProvider(config);
                if (zulipProvider.isConfigured()) {
                    providers.add(zulipProvider);
                }
            }

            if (providers.isEmpty()) {
                Log.error("No providers are configured and enabled.");
                System.exit(1);
                return;
            }

            // Fetch activities from all providers
            List<Activity> allActivities = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (ActivityProvider provider : providers) {
                Log.infof("Fetching from %s...", provider.getName());
                try {
                    List<Activity> activities = provider.fetchActivities(startDate, endDate);
                    allActivities.addAll(activities);
                    Log.infof("  Found %d activities", activities.size());
                } catch (Exception e) {
                    Log.errorf("  %s", e.getMessage());
                    errors.add(provider.getName() + ": " + e.getMessage());
                }
            }

            Log.infof("\nTotal activities found: %d", allActivities.size());

            if (allActivities.isEmpty()) {
                System.out.println("# Activity Report\n");
                System.out.println("No activities found for the specified date range.\n");
                if (!errors.isEmpty()) {
                    System.out.println("## Errors\n");
                    for (String error : errors) {
                        System.out.println("- " + error + "\n");
                    }
                }
                return;
            }

            // Classify activities into projects
            Log.info("Classifying activities into projects...\n");
            ProjectClassifier classifier = new ProjectClassifier(config);
            for (Activity activity : allActivities) {
                classifier.classifyActivity(activity).ifPresent(project ->
                    activity.addMetadata("project", project)
                );
            }

            // Generate report
            Log.info("Generating report...\n");

            String report;
            if (noAi) {
                report = MarkdownReportGenerator.generateSimple(allActivities, startDate, endDate);
            } else {
                AIProcessor aiProcessor = new AIProcessor(config);
                report = aiProcessor.generateGroupedReport(allActivities, startDate, endDate);
            }

            // Output the report
            System.out.println(report);

            // Report any errors at the end
            if (!errors.isEmpty()) {
                Log.warn("\nSome providers encountered errors:");
                for (String error : errors) {
                    Log.warnf("  - %s", error);
                }
            }

        } catch (Exception e) {
            Log.errorf("%s", e.getMessage());
            Log.error("", e);
            System.exit(1);
        }
    }

    /**
     * Version provider that reads version information from Quarkus configuration.
     * The version is injected from pom.xml via Maven resource filtering.
     */
    static class ManifestVersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            var config = ConfigProvider.getConfig();
            String version = config.getOptionalValue("app.version", String.class).orElse("unknown");
            String name = config.getOptionalValue("app.name", String.class).orElse("report");
            return new String[] { name + " " + version };
        }
    }
}
