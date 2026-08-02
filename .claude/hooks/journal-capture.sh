#!/usr/bin/env bash
#
# journal-capture.sh — write a journal entry (kickoff prompt + closing summary)
# from a Claude Code session transcript.
#
# Two modes:
#   Hook mode:     no args; reads the hook's JSON payload on stdin and takes
#                  transcript_path from it. Wire as a SessionEnd (or Stop) hook.
#   Backfill mode: journal-capture.sh <transcript.jsonl> [more.jsonl ...]
#                  processes explicit transcript files, e.g.
#                  journal-capture.sh ~/.claude/projects/<slug>/*.jsonl
#
# Output: journal/<yyyymmdd-hhmm>-<session8>.md (timestamp = transcript mtime,
# so backfilled entries sort in true chronological order).
set -euo pipefail

command -v jq >/dev/null || { echo "journal-capture: jq is required" >&2; exit 0; }

extract() {
  local transcript="$1"
  [ -f "$transcript" ] || return 0

  local sid ts out
  sid="$(basename "$transcript" .jsonl | cut -c1-8)"
  ts="$(date -r "$transcript" +%Y%m%d-%H%M 2>/dev/null || date +%Y%m%d-%H%M)"
  mkdir -p journal
  out="journal/${ts}-${sid}.md"

  # First user message with actual text (skips tool_result-only turns).
  local prompt summary
  prompt="$(jq -rs '
    [ .[]
      | select(.type=="user")
      | .message.content
      | if type=="string" then .
        else ([ .[]? | select(.type=="text") | .text ] | join("\n"))
        end
      | select(length>0)
    ] | first // "«no user prompt found»"' "$transcript")"

  # Last assistant message containing text (the closing summary).
  summary="$(jq -rs '
    [ .[]
      | select(.type=="assistant")
      | [ .message.content[]? | select(.type=="text") | .text ] | join("\n")
      | select(length>0)
    ] | last // "«no assistant summary found»"' "$transcript")"

  {
    echo "# Session ${ts} (${sid})"
    echo
    echo "## Kickoff prompt"
    echo
    echo '```'
    printf '%s\n' "$prompt"
    echo '```'
    echo
    echo "## Closing summary"
    echo
    printf '%s\n' "$summary"
  } > "$out"
  echo "journal-capture: wrote $out" >&2
}

if [ "$#" -gt 0 ]; then
  for t in "$@"; do extract "$t"; done
else
  payload="$(cat)"
  transcript="$(printf '%s' "$payload" | jq -r '.transcript_path // empty')"
  [ -n "$transcript" ] && extract "$transcript"
fi
exit 0
