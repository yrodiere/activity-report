package activityreport.config;

import activityreport.config.validation.ValidAppConfig;
import activityreport.config.validation.ValidProviderConfig;
import io.smallrye.config.ConfigMapping;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.URL;

import java.util.List;
import java.util.Optional;

/**
 * Main configuration interface using Quarkus ConfigMapping.
 * Configuration is loaded from XDG-compliant locations via XdgYamlConfigSource.
 */
@ConfigMapping(prefix = "")
@ValidAppConfig
@io.quarkus.arc.Unremovable
public interface AppConfig {
    @Valid
    Providers providers();

    Optional<@Valid Ai> ai();

    Optional<List<@Valid Project>> projects();

    interface Providers {
        Optional<@Valid Github> github();
        Optional<@Valid Jira> jira();
        Optional<@Valid Zulip> zulip();
    }

    interface ProviderConfig {
        boolean enabled();
        List<?> instances();
    }

    @ValidProviderConfig
    interface Github extends ProviderConfig {
        boolean enabled();
        @NotNull List<@Valid GithubInstance> instances();

        interface GithubInstance {
            @NotBlank String name();
            Optional<@URL String> url();
            Optional<String> username();
            Optional<String> token();
            Optional<String> publicEventsToken();
            Optional<String> defaultProject();
        }
    }

    @ValidProviderConfig
    interface Jira extends ProviderConfig {
        boolean enabled();
        @NotNull List<@Valid JiraInstance> instances();

        interface JiraInstance {
            @NotBlank String name();
            @NotBlank @URL(protocol = "https") String url();
            @NotBlank @Email String email();
            @NotBlank String token();
            Optional<String> defaultProject();
        }
    }

    @ValidProviderConfig
    interface Zulip extends ProviderConfig {
        boolean enabled();
        @NotNull List<@Valid ZulipInstance> instances();

        interface ZulipInstance {
            @NotBlank @URL(protocol = "https") String url();
            @NotBlank @Email String email();
            @NotBlank @io.smallrye.config.WithName("api_key") String apiKey();
            Optional<String> defaultProject();
        }
    }

    interface Ai {
        Optional<@URL String> url();
        Optional<String> model();
    }

    interface Project {
        @NotBlank String name();
        Optional<List<String>> urlPatterns();
    }
}
