#!/usr/bin/env bash
# PreToolUse guard: block direct access to .tickets/ (including .tickets/archive/)
# outside of git operations. Ticket reads/writes go through the `knot` CLI.
set -euo pipefail

input=$(cat)
tool_name=$(printf '%s' "$input" | jq -r '.tool_name // ""')

MSG='Direct access to .tickets/ is blocked by project convention — use the knot CLI instead (knot show <id>, knot list, knot add-note, knot update, ...). git operations (add/commit/diff/show/log/status/ls-files/stash/checkout/branch) on .tickets/ paths are still allowed.'

deny() {
  jq -n --arg reason "$MSG" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $reason
    }
  }'
  exit 0
}

case "$tool_name" in
  Read)
    file_path=$(printf '%s' "$input" | jq -r '.tool_input.file_path // ""')
    if [[ "$file_path" == *".tickets/"* ]]; then
      deny
    fi
    ;;
  Grep)
    path=$(printf '%s' "$input" | jq -r '.tool_input.path // ""')
    if [[ "$path" == *".tickets"* ]]; then
      deny
    fi
    ;;
  Glob)
    pattern=$(printf '%s' "$input" | jq -r '.tool_input.pattern // ""')
    path=$(printf '%s' "$input" | jq -r '.tool_input.path // ""')
    if [[ "$pattern" == *".tickets"* || "$path" == *".tickets"* ]]; then
      deny
    fi
    ;;
  Bash)
    command=$(printf '%s' "$input" | jq -r '.tool_input.command // ""')
    # Flag cat/grep/head/tail/sed/awk/less/more/strings invoked as a command
    # (start of string, or after ; & | or a newline) whose arguments reach
    # into .tickets/. git subcommands (git grep, git show, git log, ...) are
    # never matched here since "git" — not the bare tool name — starts the
    # command word.
    if printf '%s' "$command" | grep -Pzq '(?ms)(^|[;&|\n])[ \t]*(cat|grep|egrep|fgrep|head|tail|less|more|strings|sed|awk)\b[^;&|\n]*\.tickets/'; then
      deny
    fi
    ;;
esac

exit 0
