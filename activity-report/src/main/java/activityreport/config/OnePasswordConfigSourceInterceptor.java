package activityreport.config;

import io.quarkus.logging.Log;
import io.smallrye.config.ConfigSourceInterceptor;
import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Priority;

/**
 * ConfigSourceInterceptor that resolves 1Password secret references in config values at runtime.
 *
 * Any config value starting with "op://" will be resolved using the 1Password CLI.
 * For example, in config.yaml:
 *   github:
 *     token: op://vault/github/token
 *
 * This interceptor runs at runtime (not build time) and resolves secrets on-demand.
 */
@Priority(290)
public class OnePasswordConfigSourceInterceptor implements ConfigSourceInterceptor {

    private static volatile Boolean opCliAvailable = null;
    private static volatile boolean promptShown = false;

    @Override
    public ConfigValue getValue(ConfigSourceInterceptorContext context, String name) {
        ConfigValue configValue = context.proceed(name);

        if (configValue == null || configValue.getValue() == null) {
            return configValue;
        }

        String value = configValue.getValue();

        // Check if the value is a 1Password reference
        if (!value.startsWith("op://")) {
            return configValue;
        }

        // Check if op CLI is available (cached after first check)
        if (!isOpCliAvailable()) {
            Log.warnf("1Password CLI not available, cannot resolve: %s", value);
            return configValue;
        }

        // Show prompt before first 1Password access
        showPromptIfNeeded();

        // Resolve the 1Password reference
        String resolvedValue = readFromOnePassword(value);

        if (resolvedValue == null) {
            Log.warnf("Failed to resolve 1Password reference for property '%s': %s", name, value);
            return configValue;
        }

        Log.debugf("Resolved 1Password secret for property '%s'", name);

        // Return a new ConfigValue with the resolved secret
        return configValue.withValue(resolvedValue);
    }

    private static void showPromptIfNeeded() {
        if (!promptShown) {
            synchronized (OnePasswordConfigSourceInterceptor.class) {
                if (!promptShown) {
                    System.err.println("Accessing 1Password to retrieve secrets...");
                    System.err.println("You may be prompted to authenticate with 1Password CLI.");
                    System.err.println();
                    promptShown = true;
                }
            }
        }
    }

    private static String readFromOnePassword(String reference) {
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

    private static boolean isOpCliAvailable() {
        // Cache the result to avoid repeated checks
        if (opCliAvailable != null) {
            return opCliAvailable;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("op", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(2, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                opCliAvailable = false;
                return false;
            }
            opCliAvailable = process.exitValue() == 0;
            return opCliAvailable;
        } catch (Exception e) {
            opCliAvailable = false;
            return false;
        }
    }
}
