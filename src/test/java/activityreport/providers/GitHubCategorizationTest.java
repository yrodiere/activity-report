package activityreport.providers;

import activityreport.model.ActionCategory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubCategorizationTest {

    private static ActionCategory categorize(Set<String> userLogins, String prAuthorLogin) {
        boolean isAuthor = userLogins.contains(prAuthorLogin);
        return isAuthor ? ActionCategory.CODE : ActionCategory.REVIEW;
    }

    private static Set<String> userLoginsFromConfig(List<String> configuredUsers) {
        Set<String> userLogins = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        userLogins.addAll(configuredUsers);
        return userLogins;
    }

    @Test
    void prByConfiguredUserIsCategorizedAsCode() {
        Set<String> userLogins = userLoginsFromConfig(List.of("yrodiere", "yrodiere-agent"));

        assertThat(categorize(userLogins, "yrodiere-agent")).isEqualTo(ActionCategory.CODE);
        assertThat(categorize(userLogins, "yrodiere")).isEqualTo(ActionCategory.CODE);
    }

    @Test
    void prByNonConfiguredUserIsCategorizedAsReview() {
        Set<String> userLogins = userLoginsFromConfig(List.of("yrodiere", "yrodiere-agent"));

        assertThat(categorize(userLogins, "someone-else")).isEqualTo(ActionCategory.REVIEW);
    }

    @Test
    void loginComparisonIsCaseInsensitive() {
        Set<String> userLogins = userLoginsFromConfig(List.of("Yrodiere", "Yrodiere-Agent"));

        assertThat(categorize(userLogins, "yrodiere-agent")).isEqualTo(ActionCategory.CODE);
        assertThat(categorize(userLogins, "YRODIERE")).isEqualTo(ActionCategory.CODE);
    }

    @Test
    void singleUserConfig() {
        Set<String> userLogins = userLoginsFromConfig(List.of("yrodiere"));

        assertThat(categorize(userLogins, "yrodiere")).isEqualTo(ActionCategory.CODE);
        assertThat(categorize(userLogins, "yrodiere-agent")).isEqualTo(ActionCategory.REVIEW);
    }
}
