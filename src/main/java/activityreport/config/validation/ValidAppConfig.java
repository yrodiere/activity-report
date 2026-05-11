package activityreport.config.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validation annotation to ensure that at least one provider is enabled
 * in the application configuration.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidAppConfigValidator.class)
@Documented
public @interface ValidAppConfig {
    String message() default "At least one provider must be enabled";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
