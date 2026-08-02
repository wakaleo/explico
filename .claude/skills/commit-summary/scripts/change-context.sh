#!/usr/bin/env bash
#
# Print the change context for the current branch: base + current branch names,
# changed files, commits ahead of base, and the full diff. Used by the
# commit-summary skill via a !`…` injection, so its stdout lands in the model's
# context before the summary is written.
#
# Resolves the base branch instead of hardcoding "main", so it works on master
# repos and degrades gracefully when run while ON the base branch.
#
# Covers ALL pending work, not just tracked+unstaged: staged changes
# (diff --cached), unstaged changes (diff), and untracked files (rendered as
# new-file diffs via `git diff --no-index`), so the summary never silently
# omits brand-new files.
set -euo pipefail

# Everything sitting in the working tree that a commit would (or could) pick up.
show_worktree_changes() {
  local staged unstaged untracked
  staged="$(git diff --cached --name-only)"
  unstaged="$(git diff --name-only)"
  untracked="$(git ls-files --others --exclude-standard)"

  if [ -z "$staged$unstaged$untracked" ]; then
    echo "Working tree clean — no uncommitted changes."
    return 0
  fi

  echo "Uncommitted files (staged):"
  echo "${staged:-<none>}"
  echo
  echo "Uncommitted files (unstaged):"
  echo "${unstaged:-<none>}"
  echo
  echo "Untracked files:"
  echo "${untracked:-<none>}"
  echo
  echo "Uncommitted diff:"
  git diff --cached
  git diff
  # Untracked files have no diff against the index; render each as a new-file
  # diff against /dev/null. --no-index exits 1 when the files differ (they
  # always do here), so neutralise it under `set -e`.
  if [ -n "$untracked" ]; then
    git ls-files --others --exclude-standard -z \
      | while IFS= read -r -d '' f; do
          git diff --no-index -- /dev/null "$f" || true
        done
  fi
}

# Base branch: prefer the remote's default (origin/HEAD), then main, then master.
base="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's@^origin/@@' || true)"
if [ -z "${base:-}" ]; then
  if git rev-parse --verify --quiet main >/dev/null; then
    base="main"
  elif git rev-parse --verify --quiet master >/dev/null; then
    base="master"
  fi
fi

branch="$(git branch --show-current)"

echo "Base branch:    ${base:-<none found>}"
echo "Current branch: ${branch:-<detached HEAD>}"
echo

# If we couldn't resolve a base, or we're sitting on it, there's no range to
# diff — but the working tree is still what's about to be committed, so show it.
if [ -z "${base:-}" ]; then
  echo "No base branch found — cannot compute a diff range."
  echo
  show_worktree_changes
  exit 0
fi
if [ "$branch" = "$base" ]; then
  echo "Currently on the base branch ($base) — no branch range to summarize."
  echo
  show_worktree_changes
  exit 0
fi

echo "Changed files (committed on this branch):"
git diff --name-only "$base...HEAD"
echo
echo "Recent commits:"
git log --oneline "$base..HEAD"
echo
echo "Code diff (committed on this branch):"
git diff "$base...HEAD"
echo

# Committed-on-branch is not the whole story at commit time — include whatever
# is pending in the working tree as well.
show_worktree_changes
