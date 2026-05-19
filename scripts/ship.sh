#!/usr/bin/env bash
# ship.sh — push current branch → create PR → squash merge → delete branch.
#
# Usage:
#   ./scripts/ship.sh                  # PR base = main (default)
#   ./scripts/ship.sh develop          # PR base = develop
#
# Requirements:
#   - brew install gh
#   - gh auth login        (one-time browser/token authentication)
#   - the current branch must be ahead of <base> and have a clean working tree
#
# Notes:
#   - Uses `gh pr create --fill`, so PR title/body come from the latest commit.
#   - Uses `gh pr merge --squash` to keep main linear. Switch to --merge or
#     --rebase below if you prefer.
#   - On success, the remote branch is deleted but the local branch is kept;
#     you can prune it later with `git branch -d <branch>`.

set -euo pipefail

base="${1:-main}"
branch="$(git symbolic-ref --short HEAD)"

# ── pre-flight ──────────────────────────────────────────────────────────────
if [[ "$branch" == "$base" ]]; then
    printf '\033[31mError:\033[0m already on %s. Run from a feature branch.\n' "$base" >&2
    exit 1
fi

if ! git diff-index --quiet HEAD --; then
    printf '\033[31mError:\033[0m working tree is dirty. Commit first.\n' >&2
    git status -s >&2
    exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
    printf '\033[31mError:\033[0m gh not installed. Run: brew install gh\n' >&2
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    printf '\033[31mError:\033[0m gh not authenticated. Run: gh auth login\n' >&2
    exit 1
fi

# Ensure we have the latest origin/<base> so the PR will fast-merge cleanly
git fetch origin "$base" --quiet

# Verify branch actually has commits to ship
ahead=$(git rev-list --count "origin/${base}..HEAD")
if [[ "$ahead" -eq 0 ]]; then
    printf '\033[31mError:\033[0m no commits ahead of origin/%s.\n' "$base" >&2
    exit 1
fi

# ── push + PR + merge ───────────────────────────────────────────────────────
printf '\033[1m==> push %s\033[0m\n' "$branch"
git push -u origin "$branch"

printf '\033[1m==> create PR (base=%s)\033[0m\n' "$base"
gh pr create --base "$base" --head "$branch" --fill

printf '\033[1m==> squash merge + delete remote branch\033[0m\n'
gh pr merge "$branch" --squash --delete-branch

printf '\n\033[32mDone.\033[0m Remember to pull on your main worktree:\n'
printf "  git -C <main-worktree> pull --ff-only origin %s\n" "$base"
