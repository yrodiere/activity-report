package activityreport.config.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validation annotation to ensure that when a provider is enabled,
 * it has at least one instance configured.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidProviderConfigValidator.class)
@Documented
public @interface ValidProviderConfig {
    String message() default "When provider is enabled, at least one instance must be configured";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
