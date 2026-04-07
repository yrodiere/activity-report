# Activity Report CLI Tool

A command-line tool that generates intelligent activity reports from multiple sources (GitHub, JIRA, Zulip) using local AI for enrichment and smart grouping.

## Features

- **Multiple Data Sources**: Fetch activities from GitHub, GitHub Enterprise, JIRA, and Zulip
- **Multi-Instance Support**: Connect to multiple instances of each provider
- **AI-Powered Enrichment**: Uses local AI (via Podman AI Lab) to add descriptions and assign projects to activities
- **Smart Activity Grouping**: Intelligently groups related activities (issues, PRs, commits) based on shared URLs and context
- **Project Classification**: Automatically organizes activities into project sections
- **Flexible Date Ranges**: Query activities for the last N days or specify custom date ranges
- **Markdown Output**: Generate professional markdown reports suitable for status updates, performance reviews, or team reports
- **Fallback Mode**: Works without AI using simple URL-based grouping

## Prerequisites

- **Java 21+**: Required to run the application
- **JBang**: For easy execution (install from https://www.jbang.dev)
- **Podman AI Lab** (optional): For AI-powered enrichment and grouping (https://podman-desktop.io/extensions/ai-lab)

## Installation

1. **Install JBang** (if not already installed):
   ```bash
   # macOS
   brew install jbangdev/tap/jbang

   # Linux
   curl -Ls https://sh.jbang.dev | bash -s - app setup

   # Windows
   # See https://www.jbang.dev/download/
   ```

2. **Clone or download** this repository

3. **Add to PATH** (recommended):
   ```bash
   # Add this to your ~/.bashrc or ~/.zshrc
   export PATH="$PATH:/path/to/productivity/bin"
   ```

   Then reload your shell or run:
   ```bash
   source ~/.bashrc  # or ~/.zshrc
   ```

## Configuration

The tool follows the XDG Base Directory Specification for configuration files. Configuration is loaded automatically from:
- `$XDG_CONFIG_HOME/activity-report/config.yaml` (if `XDG_CONFIG_HOME` is set)
- `~/.config/activity-report/config.yaml` (default)

1. **Create configuration directory**:
   ```bash
   mkdir -p ~/.config/activity-report
   ```

2. **Copy the example configuration**:
   ```bash
   cp /path/to/productivity/activity-report/config.yaml.example ~/.config/activity-report/config.yaml
   ```

3. **Edit the configuration** and add your credentials:
   ```bash
   # Use your favorite editor
   vim ~/.config/activity-report/config.yaml
   ```

**Note:** The configuration file location cannot be customized via command-line options. Use the `XDG_CONFIG_HOME` environment variable if you need a non-standard location.

4. **Set up API tokens** - See "Credential Management" section below.

## Credential Management

**RECOMMENDED: Use 1Password for Secure Secret Storage**

The application can automatically resolve 1Password secret references in your configuration, providing secure credential storage without exposing tokens in config files or environment variables.

### Setup with 1Password

1. **Install 1Password CLI**:
   ```bash
   # See: https://developer.1password.com/docs/cli/get-started/
   ```

2. **Sign in to 1Password**:
   ```bash
   op signin
   ```

3. **Store your API tokens in 1Password**:
   - Create items in your vault for each service (e.g., "GitHub", "JIRA")
   - Store tokens as fields within those items

4. **Use secret references in your config**:

   In your `~/.config/activity-report/config.yaml`, use `op://` syntax to reference secrets:
   ```yaml
   providers:
     github:
       enabled: true
       instances:
         - name: "GitHub.com"
           token: op://Private/GitHub/token
     jira:
       enabled: true
       instances:
         - name: "Work JIRA"
           token: op://Private/JIRA/api_token
   ```

   The secret reference format is: `op://vault-name/item-name/field-name`

   You can also reference specific sections: `op://vault-name/item-name/section-name/field-name`

5. **Run the application**:
   ```bash
   report
   ```

   The application will automatically resolve all `op://` references using the 1Password CLI. You'll be prompted to authenticate once, then all secrets are loaded in that session.

> [!TIP]
> **Using multiple 1Password accounts**
>
> If you have secrets stored in different 1Password accounts, you can specify which account to use by adding an `?account=` query parameter to the `op://` URL:
> ```yaml
> providers:
>   github:
>     enabled: true
>     instances:
>       - name: "Personal GitHub"
>         token: "op://Personal/GitHub/token?account=my-personal-account"
>       - name: "Work GitHub"
>         token: "op://Work/GitHub/token?account=my-work-account"
> ```
>
> Without the `?account` parameter, 1Password CLI will use your default account or the one most recently signed in.

### Alternative: Environment Variables (Without 1Password)

If you don't use 1Password, you can use regular environment variables:

```bash
# Add to ~/.bashrc or ~/.zshrc
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxx"
export JIRA_TOKEN="xxxxxxxxxxxxx"
export ZULIP_API_KEY="xxxxxxxxxxxxx"
```

### Creating API Tokens

#### GitHub

You have three options for GitHub authentication:

**Option 1: Classic Token (Full Access)**
1. Go to https://github.com/settings/tokens
2. Click "Generate new token (classic)"
3. Select scopes: `repo`, `read:user`, `user:email`
4. Copy token and store in 1Password or as environment variable

Note: The `repo` scope grants READ+WRITE access to all accessible repositories.

**Option 2: Fine-Grained Token (Recommended for Security)**
1. Go to https://github.com/settings/personal-access-tokens/new
2. Set repository access and permissions:
   - Repository access: Choose specific repos or "All repositories"
   - Repository permissions: Issues (Read), Pull requests (Read), Metadata (Read)
3. For organization private repos: Token must be authorized by the organization
4. Copy token and store securely

Note: Fine-grained tokens are READ-ONLY but filter events based on token scope. If you use a fine-grained token, you may need to also configure a `public-events-token` (see below).

**Option 3: Public Events Token (For Fine-Grained Token Users)**

If you use a fine-grained token as your main token, it will filter public events based on the token's repository/organization scope. This means you might miss public activities from organizations not accessible by your token.

To work around this, create a separate classic token with **NO scopes** for fetching public events:
1. Go to https://github.com/settings/tokens
2. Click "Generate new token (classic)"
3. **DO NOT select any scopes** (leave all checkboxes unchecked)
4. Copy token and configure as `public-events-token` in your config

Benefits:
- Sees ALL public events without organization filtering
- Higher rate limits (5000 requests/hour) vs unauthenticated access (60 requests/hour)
- No write permissions (more secure)

Example configuration:
```yaml
providers:
  github:
    instances:
      - name: "GitHub.com"
        token: "op://Private/GitHub-Fine-Grained/token"  # Fine-grained token
        public-events-token: "op://Private/GitHub-Public-Events/token"  # No-scope classic token
```

#### JIRA

**JIRA Cloud:**
1. Go to https://id.atlassian.com/manage-profile/security/api-tokens
2. Click "Create API token"
3. Copy token and store securely

**JIRA Server/Data Center:**
Use your JIRA password or create a personal access token in JIRA settings.

#### Zulip

1. Log in to your Zulip instance
2. Go to: Settings → Account & privacy → API key
3. Click "Show/change your API key"
4. Copy API key and store securely

## Setting Up Podman AI Lab (Optional)

For AI-powered activity enrichment and grouping:

1. **Install Podman Desktop**: https://podman-desktop.io
2. **Install AI Lab Extension**: Open Podman Desktop → Extensions → Install AI Lab
3. **Download a Model**:
   - Open AI Lab
   - Go to "Models" tab
   - Download a model (e.g., "Mistral-7B" or "Llama-2-7B")
4. **Start the Model Server**:
   - Go to "Services" tab
   - Start the model server (default: http://localhost:8000)

The tool will auto-detect the available model and use it to:
- Add descriptions to activities that don't have one
- Assign projects to activities based on their content
- Group related activities together (e.g., issue + PR + commits)

If AI Lab is not running, the tool will fall back to simple URL-based grouping without enrichment.

## Usage

### Basic Usage

Generate a report for the last 7 days:
```bash
report
```

The report will be automatically saved to `~/.local/share/activity-report/report_YYYY-MM-DD_to_YYYY-MM-DD.md` and opened in your preferred editor.

### Specify Number of Days

```bash
# Last 14 days
report --days 14

# Last 30 days
report -d 30
```

### Custom Date Range

```bash
report --start-date 2024-02-01 --end-date 2024-02-15
```

### Disable AI Processing

```bash
report --no-ai
```

### Opening the Report

The report is automatically opened using `xdg-open`, which will use your system's default application for markdown files. This could be a text editor, IDE, or markdown viewer depending on your system configuration.

If `xdg-open` is not available or fails, the file path will be displayed for manual opening.

### Report Location

Reports are stored in the XDG data directory:
- `$XDG_DATA_HOME/activity-report/` if `XDG_DATA_HOME` is set
- `~/.local/share/activity-report/` otherwise

Each report is named based on its date range, e.g., `report_2024-02-01_to_2024-02-15.md`.

### Full Command Reference

```bash
report --help
```

Options:
- `-d, --days <N>`: Number of days to look back (default: 7)
- `--start-date <YYYY-MM-DD>`: Start date for custom range
- `--end-date <YYYY-MM-DD>`: End date for custom range
- `--no-ai`: Disable AI processing and use simple markdown generation
- `-h, --help`: Show help message
- `-V, --version`: Print version information

## Example Output

With AI enabled, you get enriched activities with smart grouping:

```markdown
# General

(To be filled manually)

----

# Project: Quarkus

* [Fix caching issue in dev mode](https://github.com/quarkusio/quarkus/pull/12345)
  Fixed a bug where configuration changes weren't being picked up in dev mode.
  - [Issue: Dev mode not reloading config](https://github.com/quarkusio/quarkus/issues/12340)
  - [Review comment on PR #12345](https://github.com/quarkusio/quarkus/pull/12345#discussion_r123)

* [Add support for custom serializers](https://github.com/quarkusio/quarkus/pull/12350)
  Implemented support for custom JSON serializers in RESTEasy Reactive.

----

# Misc

Reviews, triage, discussions

* [Reviewed: Update documentation for Maven plugin](https://github.com/other-project/repo/pull/456)

* [Discussion: Performance optimization strategies](https://zulipchat.com/...)
...
```

Without AI (`--no-ai`), you get simple URL-based grouping without descriptions or AI-assigned projects.

## Troubleshooting

### "Configuration file not found"

Make sure you've created `~/.config/activity-report/config.yaml` from the example file. The tool follows the XDG Base Directory Specification and looks for configuration in:
1. `$XDG_CONFIG_HOME/activity-report/config.yaml` (if `XDG_CONFIG_HOME` is set)
2. `~/.config/activity-report/config.yaml` (default)

### "Environment variable not set"

The tool will warn about missing environment variables. Either:
1. Set the environment variables (recommended): `export GITHUB_TOKEN="..."`
2. Put tokens directly in the config file (less secure)

### "No activities found"

This can happen if:
- Your tokens don't have the right permissions
- There are no activities in the specified date range
- The configured providers are not accessible

Run with verbose output to see detailed errors from each provider.

### AI processing fails

If the AI model is not available:
- Check that Podman AI Lab is running
- Verify the model server is accessible at the configured URL
- The tool will automatically fall back to simple URL-based grouping without enrichment

### JIRA or Zulip API errors

- Verify your instance URLs are correct
- Check that your API tokens have the necessary permissions
- Some JIRA instances may have rate limiting - try a smaller date range

## Advanced Configuration

### Project Classification

The tool can organize your report by projects, creating sections like "Project: Quarkus", "Project: Hibernate ORM", etc. Activities are classified based on URL pattern matching.

Configure projects in your `config.yaml`:

```yaml
projects:
  - name: "Quarkus"
    urlPatterns:
      - "https://github.com/quarkusio/*"
      - "https://github.com/quarkiverse/*"
  - name: "Hibernate ORM"
    urlPatterns:
      - "https://github.com/hibernate/hibernate-orm/*"
      - "https://redhat.atlassian.net/browse/HHH-*"
  - name: "Infrastructure"
    urlPatterns:
      - "https://github.com/hibernate/hibernate.org/*"
```

**Classification Logic:**
1. The tool first tries to match the activity's main URL against the configured patterns
2. If no match, it tries to match URLs in the activity's content (comments, reviews, messages)
3. If still no match, it uses the instance's `defaultProject` (see below)
4. If still no match, the activity goes to the "Misc" section

**Default Projects for Instances:**

You can assign a default project to provider instances. This is useful when an entire instance is dedicated to a specific project:

```yaml
providers:
  github:
    enabled: true
    instances:
      - name: "GitHub.com"
        token: "${GITHUB_TOKEN}"
        defaultProject: "Quarkus"  # Activities from this instance default to Quarkus

  zulip:
    enabled: true
    instances:
      - url: "https://quarkus.zulipchat.com"
        email: "you@example.com"
        api_key: "${ZULIP_API_KEY}"
        defaultProject: "Quarkus"  # All Zulip messages default to Quarkus
```

### Multiple GitHub Instances

You can configure multiple GitHub instances for different purposes:

**GitHub Enterprise:**

```yaml
providers:
  github:
    enabled: true
    instances:
      - name: "GitHub.com"
        url: "https://api.github.com"
        username: "personal-account"
        token: "${GITHUB_TOKEN}"
      - name: "Company GitHub"
        url: "https://github.company.com/api/v3"
        username: "work-account"
        token: "${GHE_TOKEN}"
```

**Fine-Grained Tokens for Organization Private Repos:**

GitHub's fine-grained tokens provide read-only access (better security) but must be scoped per organization. You can use multiple instances with different tokens to cover all your organizations:

```yaml
providers:
  github:
    enabled: true
    instances:
      - name: "Personal & Public"
        token: "${GITHUB_PERSONAL_TOKEN}"  # Fine-grained token for personal repos
      - name: "Org A"
        token: "${GITHUB_ORG_A_TOKEN}"     # Fine-grained token authorized for Org A
      - name: "Org B"
        token: "${GITHUB_ORG_B_TOKEN}"     # Fine-grained token authorized for Org B
```

**Note:** The tool automatically deduplicates activities, so if multiple tokens have access to the same repository, each issue/PR will only appear once in the report.

**Token Permissions:**
- **Classic tokens:** Require `repo` scope (grants write access to all repos)
- **Fine-grained tokens:** Can use read-only permissions:
  - Repository permissions: Issues (Read), Pull requests (Read), Metadata (Read)
  - Must be explicitly authorized for each organization with private repos

### Multiple JIRA/Zulip Instances

Similarly, you can configure multiple JIRA and Zulip instances:

```yaml
providers:
  jira:
    enabled: true
    instances:
      - name: "Public JIRA"
        url: "https://project.atlassian.net"
        email: "you@example.com"
        token: "${JIRA_PUBLIC_TOKEN}"
      - name: "Internal JIRA"
        url: "https://jira.company.com"
        email: "you@company.com"
        token: "${JIRA_INTERNAL_TOKEN}"
```

## Development

### Running from Source

The tool is a single JBang script, so no build is necessary. You can use the `report` wrapper in `bin/`:

```bash
bin/report
```

Or run the JBang script directly:

```bash
jbang activity-report/ActivityReport.java
```

### Adding New Providers

To add a new activity provider:

1. Implement the `ActivityProvider` interface
2. Add configuration classes to `Config`
3. Instantiate the provider in `ActivityReportCommand.run()`

## License

[Your chosen license]

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues and questions, please open an issue on GitHub.
