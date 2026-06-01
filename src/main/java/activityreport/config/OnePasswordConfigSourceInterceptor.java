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

        // Check if the value contains any 1Password references
        if (!value.contains("op://")) {
            return configValue;
        }

        // Check if op CLI is available (cached after first check)
        if (!isOpCliAvailable()) {
            Log.warnf("1Password CLI not available, cannot resolve: %s", value);
            return configValue;
        }

        // Show prompt before first 1Password access
        showPromptIfNeeded();

        // Handle array values (comma-separated) - Quarkus represents arrays this way
        if (value.contains(",")) {
            // Split on comma and resolve each reference
            String[] references = value.split(",");
            StringBuilder resolvedArray = new StringBuilder();

            for (int i = 0; i < references.length; i++) {
                String ref = references[i].trim();

                if (ref.startsWith("op://")) {
                    String resolved = resolveReference(ref, name + "[" + i + "]");
                    if (resolved == null) {
                        Log.warnf("Failed to resolve 1Password reference for property '%s': %s", name, value);
                        return configValue; // Return original if any reference fails
                    }
                    if (i > 0) {
                        resolvedArray.append(',');
                    }
                    resolvedArray.append(resolved);
                } else {
                    // Not a 1Password reference, keep as-is
                    if (i > 0) {
                        resolvedArray.append(',');
                    }
                    resolvedArray.append(ref);
                }
            }

            return configValue.withValue(resolvedArray.toString());
        } else {
            // Single value
            if (value.startsWith("op://")) {
                String resolved = resolveReference(value, name);
                if (resolved == null) {
                    Log.warnf("Failed to resolve 1Password reference for property '%s': %s", name, value);
                    return configValue;
                }
                return configValue.withValue(resolved);
            } else {
                return configValue;
            }
        }
    }

    private String resolveReference(String value, String propertyName) {
        // Parse account from query parameter if present
        String account = null;
        String cleanReference = value;

        int queryStart = value.indexOf('?');
        if (queryStart != -1) {
            String query = value.substring(queryStart + 1);
            cleanReference = value.substring(0, queryStart);

            // Parse query parameters
            for (String param : query.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2 && "account".equals(parts[0])) {
                    account = parts[1];
                    Log.debugf("Using 1Password account '%s' for property '%s'", account, propertyName);
                    break;
                }
            }
        }

        // Resolve the 1Password reference
        String resolvedValue = readFromOnePassword(cleanReference, account);

        if (resolvedValue != null) {
            Log.debugf("Resolved 1Password secret for property '%s'", propertyName);
        }

        return resolvedValue;
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

    private static String readFromOnePassword(String reference, String account) {
        try {
            // Build command with optional account flag
            ProcessBuilder pb;
            if (account != null && !account.isEmpty()) {
                pb = new ProcessBuilder("op", "read", "--account", account, reference);
            } else {
                pb = new ProcessBuilder("op", "read", reference);
            }
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
