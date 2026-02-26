# Activity Report CLI Tool

A command-line tool that generates intelligent activity reports from multiple sources (GitHub, JIRA, Zulip) using local AI for smart grouping and summarization.

## Features

- **Multiple Data Sources**: Fetch activities from GitHub, GitHub Enterprise, JIRA, and Zulip
- **Multi-Instance Support**: Connect to multiple instances of each provider
- **AI-Powered Grouping**: Uses local AI (via Podman AI Lab) to intelligently group related activities into coherent achievements
- **Flexible Date Ranges**: Query activities for the last N days or specify custom date ranges
- **Markdown Output**: Generate professional markdown reports suitable for status updates, performance reviews, or team reports
- **Fallback Mode**: Works without AI using simple markdown generation

## Prerequisites

- **Java 21+**: Required to run the application
- **JBang**: For easy execution (install from https://www.jbang.dev)
- **Podman AI Lab** (optional): For AI-powered report generation (https://podman-desktop.io/extensions/ai-lab)

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

The application can automatically load API tokens from a 1Password environment, providing secure credential storage without exposing tokens in config files or environment variables.

### Setup with 1Password

1. **Install 1Password CLI**:
   ```bash
   # See: https://developer.1password.com/docs/cli/get-started/
   ```

2. **Sign in to 1Password**:
   ```bash
   op signin
   ```

3. **Create a 1Password Environment**:
   - Go to 1Password web app
   - Create environment variables for your API tokens:
     - `GITHUB_TOKEN`
     - `JIRA_TOKEN`
     - `ZULIP_API_KEY`
   - Point them to the corresponding items in your vault
   - Learn more: https://developer.1password.com/docs/cli/secrets-environment-variables/

4. **Configure the application**:

   Add to your `~/.config/activity-report/config.yaml`:
   ```yaml
   onepassword:
     environment: "your-environment-id"
   ```

5. **Run the application**:
   ```bash
   report
   ```

   The application will automatically call `op environment read` at startup and load all secrets. No wrapper scripts needed!

### Alternative: Environment Variables (Without 1Password)

If you don't use 1Password, you can use regular environment variables:

```bash
# Add to ~/.bashrc or ~/.zshrc
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxx"
export JIRA_TOKEN="xxxxxxxxxxxxx"
export ZULIP_API_KEY="xxxxxxxxxxxxx"
```

### Creating API Tokens

See `config.yaml.example` for detailed instructions on obtaining tokens for each service.

## Setting Up Podman AI Lab (Optional)

For AI-powered report generation:

1. **Install Podman Desktop**: https://podman-desktop.io
2. **Install AI Lab Extension**: Open Podman Desktop → Extensions → Install AI Lab
3. **Download a Model**:
   - Open AI Lab
   - Go to "Models" tab
   - Download a model (e.g., "Mistral-7B" or "Llama-2-7B")
4. **Start the Model Server**:
   - Go to "Services" tab
   - Start the model server (default: http://localhost:8000)

The tool will auto-detect the available model. If AI Lab is not running, the tool will fall back to simple markdown generation.

## Usage

### Basic Usage

Generate a report for the last 7 days:
```bash
report
```

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


### Save Report to File

```bash
report > report.md
```

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

With AI enabled, you get an intelligent, achievement-oriented report:

```markdown
# Activity Report

During this period, I focused on improving the authentication system
and fixing several critical bugs in the payment processing module.

## Authentication System Overhaul

- Implemented OAuth2 integration ([#123](https://github.com/...))
- Fixed session timeout issues ([#124](https://github.com/...))
- Added comprehensive tests for auth flows
- Merged PR [#125](https://github.com/...) after review

## Payment Processing Fixes

- Resolved critical bug in transaction validation ([PROJ-456](https://jira...))
- Updated payment gateway integration
- Deployed hotfix to production

## Code Reviews and Collaboration

- Reviewed 5 pull requests from team members
- Participated in architecture discussions on Zulip
...
```

Without AI (`--no-ai`), you get a simpler chronological listing grouped by source and type.

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
- The tool will automatically fall back to simple markdown generation

### JIRA or Zulip API errors

- Verify your instance URLs are correct
- Check that your API tokens have the necessary permissions
- Some JIRA instances may have rate limiting - try a smaller date range

## Advanced Configuration

### Multiple GitHub Instances

You can configure both GitHub.com and GitHub Enterprise:

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
