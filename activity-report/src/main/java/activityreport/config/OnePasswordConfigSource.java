package activityreport.config;

import io.quarkus.logging.Log;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * ConfigSourceFactory that loads environment variables from a 1Password environment.
 *
 * Enable by adding to your config.yaml:
 *   onepassword:
 *     environment: "your-environment-id"
 *
 * When configured, this factory will call `op environment read` to load
 * all environment variables defined in the specified 1Password environment.
 */
public class OnePasswordConfigSource implements ConfigSourceFactory {

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        // Read the onepassword.environment config value
        ConfigValue configValue = context.getValue("onepassword.environment");

        if (configValue == null || configValue.getValue() == null) {
            // 1Password not configured - return empty
            Log.debug("onepassword.environment not configured, skipping 1Password integration");
            return Collections.emptyList();
        }

        String environmentId = configValue.getValue();
        Log.infof("Loading secrets from 1Password environment: %s", environmentId);
        Map<String, String> properties = loadFromOnePassword(environmentId);

        if (properties.isEmpty()) {
            Log.warn("No secrets loaded from 1Password environment (check logs above for errors)");
            return Collections.emptyList();
        }

        // Return a ConfigSource with the loaded properties
        ConfigSource source = new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return new HashMap<>(properties);
            }

            @Override
            public String getValue(String propertyName) {
                return properties.get(propertyName);
            }

            @Override
            public String getName() {
                return "OnePasswordConfigSource (" + environmentId + ")";
            }

            @Override
            public Set<String> getPropertyNames() {
                return properties.keySet();
            }
        };

        return Collections.singletonList(source);
    }

    @Override
    public OptionalInt getPriority() {
        // Priority 290 - higher than YAML config (275) and default sources (250)
        // but lower than system properties (300)
        // This allows system properties to override 1Password values if needed
        return OptionalInt.of(290);
    }

    private Map<String, String> loadFromOnePassword(String envId) {
        Map<String, String> properties = new HashMap<>();

        if (!isOpCliAvailable()) {
            Log.errorf("1Password environment configured (%s) but 'op' CLI not found", envId);
            Log.error("Install from: https://developer.1password.com/docs/cli/get-started/");
            return properties;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("op", "environment", "read", envId);
            Process process = pb.start();

            // Read stdout (the actual environment data)
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    // Parse KEY=VALUE format
                    int equalsIndex = line.indexOf('=');
                    if (equalsIndex > 0) {
                        String key = line.substring(0, equalsIndex);
                        String value = line.substring(equalsIndex + 1);
                        properties.put(key, value);
                    }
                }
            }

            // Read stderr (messages from op CLI)
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {

                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    Log.infof("1Password CLI: %s", errLine);
                }
            }

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                Log.errorf("1Password CLI timed out reading environment: %s", envId);
                return properties;
            }

            if (process.exitValue() != 0) {
                Log.errorf("Failed to read 1Password environment: %s", envId);
                Log.error("Make sure you're signed in with: op signin");
                return properties;
            }

            if (!properties.isEmpty()) {
                Log.infof("Loaded %d secrets from 1Password environment: %s",
                         properties.size(), envId);
            }

        } catch (Exception e) {
            Log.errorf("Error loading 1Password environment: %s", e.getMessage());
        }

        return properties;
    }

    private boolean isOpCliAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("op", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
