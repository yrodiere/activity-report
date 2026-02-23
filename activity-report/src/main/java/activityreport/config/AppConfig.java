package activityreport.config;

import io.smallrye.config.ConfigMapping;

import java.util.List;
import java.util.Optional;

/**
 * Main configuration interface using Quarkus ConfigMapping.
 * Configuration is loaded from XDG-compliant locations via XdgYamlConfigSourceFactory.
 */
@ConfigMapping(prefix = "")
public interface AppConfig {
    Providers providers();
    Optional<Ai> ai();

    interface Providers {
        Optional<Github> github();
        Optional<Jira> jira();
        Optional<Zulip> zulip();
    }

    interface Github {
        boolean enabled();
        List<GithubInstance> instances();

        interface GithubInstance {
            String name();
            Optional<String> url();
            Optional<String> username();
            Optional<String> token();
        }
    }

    interface Jira {
        boolean enabled();
        List<JiraInstance> instances();

        interface JiraInstance {
            String name();
            String url();
            String email();
            String token();
        }
    }

    interface Zulip {
        boolean enabled();
        List<ZulipInstance> instances();

        interface ZulipInstance {
            String url();
            String email();
            @io.smallrye.config.WithName("api_key")
            String apiKey();
        }
    }

    interface Ai {
        Optional<String> url();
        Optional<String> model();
    }
}
