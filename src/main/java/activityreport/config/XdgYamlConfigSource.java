package activityreport.config;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ExecutionMode;
import io.smallrye.config.source.yaml.YamlConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

/**
 * ConfigSourceProvider that loads YAML configuration from XDG-compliant locations.
 * Looks for config.yaml in:
 * 1. $XDG_CONFIG_HOME/activity-report/config.yaml (if XDG_CONFIG_HOME is set)
 * 2. ~/.config/activity-report/config.yaml (default)
 *
 * This provider returns an empty list during build time to avoid triggering secret resolution.
 */
public class XdgYamlConfigSource implements ConfigSourceProvider {

    private static final int ORDINAL = 275;

    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader forClassLoader) {
        // Skip loading config during build time to avoid triggering 1Password resolution
        if (isBuildTime()) {
            Log.debug("Skipping XDG YAML config loading during build time");
            return Collections.emptyList();
        }

        Path configPath = getConfigPath();
        try {
            YamlConfigSource source = new YamlConfigSource(configPath.toUri().toURL(), ORDINAL);
            Log.debugf("Loaded configuration from '%s'", configPath);
            return Collections.singletonList(source);
        } catch (IOException e) {
            Log.debugf("Configuration file not found at %s (this is normal during initial setup)", configPath);
            return Collections.emptyList();
        }
    }

    /**
     * Get the configuration file path.
     * Checks for REPORT_CONFIG_PATH environment variable first, then falls back to XDG paths.
     */
    public static Path getConfigPath() {
        String configPathEnv = System.getenv("REPORT_CONFIG_PATH");
        if (configPathEnv != null && !configPathEnv.isEmpty()) {
            return Paths.get(configPathEnv);
        }
        return getDefaultConfigPath();
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

    /**
     * Detect if we're running during Quarkus build/augmentation or static init phase.
     * During these phases, we should skip loading the config file to avoid triggering secret resolution.
     */
    private static boolean isBuildTime() {
        ExecutionMode mode = ExecutionMode.current();
        // Skip during STATIC_INIT phase (build time + early startup)
        // Also handle UNSET which occurs during very early initialization
        return mode == ExecutionMode.STATIC_INIT || mode == ExecutionMode.UNSET;
    }
}
