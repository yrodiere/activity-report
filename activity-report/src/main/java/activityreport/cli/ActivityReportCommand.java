package activityreport.cli;

import activityreport.config.AppConfig;
import activityreport.model.Activity;
import activityreport.model.ActivityProvider;
import activityreport.providers.GitHubProvider;
import activityreport.providers.JiraProvider;
import activityreport.providers.ZulipProvider;
import activityreport.report.AIProcessor;
import activityreport.report.MarkdownReportGenerator;
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
     * Validate the configuration to ensure at least one provider is enabled and properly configured.
     */
    private void validateConfig() {
        if (config == null || config.providers() == null) {
            throw new IllegalStateException(
                "Configuration file not found. Please create a configuration file at: " +
                activityreport.config.XdgYamlConfigSourceFactory.getDefaultConfigPath() + "\n" +
                "See config.yaml.example for reference."
            );
        }

        // Check if at least one provider is enabled
        boolean hasEnabledProvider = false;

        if (config.providers().github().map(g -> g.enabled()).orElse(false)) {
            hasEnabledProvider = true;
            if (config.providers().github().get().instances() == null ||
                config.providers().github().get().instances().isEmpty()) {
                throw new IllegalStateException("GitHub is enabled but no instances are configured");
            }
        }

        if (config.providers().jira().map(j -> j.enabled()).orElse(false)) {
            hasEnabledProvider = true;
            if (config.providers().jira().get().instances() == null ||
                config.providers().jira().get().instances().isEmpty()) {
                throw new IllegalStateException("JIRA is enabled but no instances are configured");
            }
        }

        if (config.providers().zulip().map(z -> z.enabled()).orElse(false)) {
            hasEnabledProvider = true;
            if (config.providers().zulip().get().instances() == null ||
                config.providers().zulip().get().instances().isEmpty()) {
                throw new IllegalStateException("Zulip is enabled but no instances are configured");
            }
        }

        if (!hasEnabledProvider) {
            throw new IllegalStateException("No providers are enabled. Please enable at least one provider in the configuration.");
        }
    }

    @Override
    public void run() {
        try {
            System.err.println("Activity Report Generator");
            System.err.println("=========================\n");

            // Validate configuration
            validateConfig();
            System.err.println("Configuration loaded successfully.\n");

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
            System.err.println("Fetching activities from " + formatter.format(startDate) +
                             " to " + formatter.format(endDate) + "\n");

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
                System.err.println("Error: No providers are configured and enabled.");
                System.exit(1);
                return;
            }

            // Fetch activities from all providers
            List<Activity> allActivities = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (ActivityProvider provider : providers) {
                System.err.println("Fetching from " + provider.getName() + "...");
                try {
                    List<Activity> activities = provider.fetchActivities(startDate, endDate);
                    allActivities.addAll(activities);
                    System.err.println("  Found " + activities.size() + " activities");
                } catch (Exception e) {
                    String errorMsg = "  Error: " + e.getMessage();
                    System.err.println(errorMsg);
                    errors.add(provider.getName() + ": " + e.getMessage());
                }
            }

            System.err.println("\nTotal activities found: " + allActivities.size());

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

            // Generate report
            System.err.println("Generating report...\n");

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
                System.err.println("\nWarning: Some providers encountered errors:");
                for (String error : errors) {
                    System.err.println("  - " + error);
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace(System.err);
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
