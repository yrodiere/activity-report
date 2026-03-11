package activityreport.config;

import io.quarkus.logging.Log;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
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
 * ConfigSourceFactory that resolves 1Password secret references in config values.
 *
 * Any config value starting with "op://" will be resolved using the 1Password CLI.
 * For example, in config.yaml:
 *   github:
 *     token: op://vault/github/token
 *
 * All references are resolved in a single authentication session to avoid
 * multiple authentication prompts.
 */
public class OnePasswordConfigSource implements ConfigSourceFactory {

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        if (!isOpCliAvailable()) {
            // No op CLI available, skip silently
            return Collections.emptyList();
        }

        // Scan all existing config values for op:// references
        Map<String, String> resolvedValues = new HashMap<>();
        Map<String, String> referencesToResolve = new HashMap<>();

        // Iterate through all config sources to find op:// references
        for (ConfigSource configSource : context.getConfigSources()) {
            for (String propertyName : configSource.getPropertyNames()) {
                String value = configSource.getValue(propertyName);
                if (value != null && value.startsWith("op://")) {
                    referencesToResolve.put(propertyName, value);
                }
            }
        }

        if (referencesToResolve.isEmpty()) {
            Log.debug("No 1Password references (op://) found in configuration");
            return Collections.emptyList();
        }

        Log.debugf("Found %d 1Password reference(s) to resolve", referencesToResolve.size());

        // Resolve all references (this will authenticate once and reuse the session)
        for (Map.Entry<String, String> entry : referencesToResolve.entrySet()) {
            String propertyName = entry.getKey();
            String reference = entry.getValue();
            String resolvedValue = readFromOnePassword(reference);
            if (resolvedValue != null) {
                resolvedValues.put(propertyName, resolvedValue);
            }
        }

        if (resolvedValues.isEmpty()) {
            Log.warn("No 1Password references could be resolved (check logs above for errors)");
            return Collections.emptyList();
        }

        Log.debugf("Resolved %d secret(s) from 1Password", resolvedValues.size());

        // Return a ConfigSource with the resolved properties
        ConfigSource source = new ConfigSource() {
            @Override
            public Map<String, String> getProperties() {
                return new HashMap<>(resolvedValues);
            }

            @Override
            public String getValue(String propertyName) {
                return resolvedValues.get(propertyName);
            }

            @Override
            public String getName() {
                return "OnePasswordConfigSource";
            }

            @Override
            public Set<String> getPropertyNames() {
                return resolvedValues.keySet();
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

    private String readFromOnePassword(String reference) {
        try {
            ProcessBuilder pb = new ProcessBuilder("op", "read", reference);
            Process process = pb.start();

            // Read stdout (the secret value)
            StringBuilder value = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    if (value.length() > 0) {
                        value.append('\n');
                    }
                    value.append(line);
                }
            }

            // Read stderr (messages from op CLI)
            StringBuilder errorOutput = new StringBuilder();
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {

                String errLine;
                while ((errLine = errReader.readLine()) != null) {
                    if (errorOutput.length() > 0) {
                        errorOutput.append('\n');
                    }
                    errorOutput.append(errLine);
                }
            }

            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                Log.errorf("1Password CLI timed out reading: %s", reference);
                return null;
            }

            if (process.exitValue() != 0) {
                if (errorOutput.length() > 0) {
                    Log.errorf("Failed to read %s: %s", reference, errorOutput.toString());
                } else {
                    Log.errorf("Failed to read %s (exit code: %d)", reference, process.exitValue());
                }
                return null;
            }

            // Log stderr output even on success (might contain helpful info)
            if (errorOutput.length() > 0) {
                Log.debugf("1Password CLI: %s", errorOutput.toString());
            }

            return value.toString();

        } catch (Exception e) {
            Log.errorf("Error reading 1Password reference %s: %s", reference, e.getMessage());
            return null;
        }
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
