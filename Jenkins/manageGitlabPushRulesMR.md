# Manage GitLab Push Rules & MR Settings

Jenkins pipeline to bulk configure GitLab push rules and merge request settings across projects and groups.

## Features

- Configure push rules (commit verification, secrets detection, file size limits, regex patterns)
- Configure MR settings (merge methods, squash options, pipeline requirements, merge trains)
- Bulk operations on groups or individual projects
- Read-only mode to view current settings
- Shows diff of changes before applying

## Usage

1. Run the Jenkins pipeline
2. Enter target (group/project ID or path, comma-separated)
3. Review/modify settings in the interactive form
4. Confirm to apply changes

## Parameters

**Initial Input:**
- `TARGET` - Group/project identifiers (e.g., `my-group`, `123`, `group/project`)
- `READ_ONLY` - View settings without making changes

**Push Rules:**
- Reject unverified/unsigned commits
- Prevent secrets in code
- Regex patterns for commits, branches, files, emails
- Max file size limits

**MR Settings:**
- Merge method (merge/rebase/fast-forward)
- Squash options
- Pipeline/discussion requirements
- Auto-delete source branch
- Merge trains

## Notes

- Single project shows current values; groups/multiple projects show defaults.
- Thus bulk editing means what you see is not related to the existing values in gitlab.
- Requires GitLab API token configured in Jenkins credentials
