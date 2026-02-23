package activityreport.config;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.OptionalInt;

/**
 * Custom ConfigSource factory that loads YAML configuration from XDG-compliant locations.
 * Looks for config.yaml in:
 * 1. $XDG_CONFIG_HOME/activity-report/config.yaml (if XDG_CONFIG_HOME is set)
 * 2. ~/.config/activity-report/config.yaml (default)
 */
public class XdgYamlConfigSourceFactory implements ConfigSourceFactory {

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        Path configPath = getDefaultConfigPath();

        if (Files.exists(configPath)) {
            try {
                YamlConfigSource yamlSource = new YamlConfigSource(configPath.toUri().toURL(), 275);
                return Collections.singletonList(yamlSource);
            } catch (IOException e) {
                // Don't fail during build - the application will validate at runtime
                System.err.println("Warning: Failed to load configuration from " + configPath + ": " + e.getMessage());
            }
        }
        // Return empty list to allow build to succeed - runtime validation will catch missing config
        return Collections.emptyList();
    }

    @Override
    public OptionalInt getPriority() {
        // Priority 275 is between application.properties (250) and system properties (300)
        // This allows command-line/env vars to override config file, but config file overrides application.properties
        return OptionalInt.of(275);
    }

    /**
     * Get the default configuration file path following XDG Base Directory Specification.
     */
    public static Path getDefaultConfigPath() {
        String xdgConfigHome = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfigHome != null && !xdgConfigHome.isEmpty()) {
            return Paths.get(xdgConfigHome, "activity-report", "config.yaml");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "activity-report", "config.yaml");
    }
}
