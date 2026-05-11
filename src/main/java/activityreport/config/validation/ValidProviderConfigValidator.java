package activityreport.config.validation;

import activityreport.config.AppConfig;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

/**
 * Validator that checks if a provider configuration is valid.
 * When a provider is enabled, it must have at least one instance configured.
 */
public class ValidProviderConfigValidator implements ConstraintValidator<ValidProviderConfig, AppConfig.ProviderConfig> {

    @Override
    public boolean isValid(AppConfig.ProviderConfig provider, ConstraintValidatorContext context) {
        if (provider == null) {
            return true; // Let @NotNull handle null values
        }

        if (provider.enabled()) {
            List<?> instances = provider.instances();
            if (instances == null || instances.isEmpty()) {
                return false;
            }
        }

        return true;
    }
}
