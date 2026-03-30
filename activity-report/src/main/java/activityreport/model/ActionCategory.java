package activityreport.model;

/**
 * The category of action performed in an activity
 */
public enum ActionCategory {
    /**
     * Code work - authoring PRs, commits
     */
    CODE,

    /**
     * Reviewing others' work
     */
    REVIEW,

    /**
     * Discussions, triage, commenting on issues
     */
    DISCUSS
}
