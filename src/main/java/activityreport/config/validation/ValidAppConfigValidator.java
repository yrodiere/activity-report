package activityreport.config.validation;

import activityreport.config.AppConfig;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Validator that checks if at least one provider is enabled in the application configuration.
 */
public class ValidAppConfigValidator implements ConstraintValidator<ValidAppConfig, AppConfig> {

    @Override
    public boolean isValid(AppConfig config, ConstraintValidatorContext context) {
        if (config == null || config.providers() == null) {
            return true; // Let other validators handle null values
        }

        boolean hasEnabledProvider =
                config.providers().github().map(g -> g.enabled()).orElse(false) ||
                config.providers().jira().map(j -> j.enabled()).orElse(false) ||
                config.providers().zulip().map(z -> z.enabled()).orElse(false);

        if (!hasEnabledProvider) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "At least one provider must be enabled (github, jira, or zulip)")
                    .addConstraintViolation();
            return false;
        }

        return true;
    }
}
