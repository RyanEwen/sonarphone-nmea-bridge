---
name: commit
description: Review, validate, stage, and commit repository changes when the user asks Codex to create a Git commit.
---

# Commit repository changes

Create one coherent, verified commit from the changes the user placed in scope.

## Workflow

1. Read the repository instructions, inspect staged and unstaged changes, and check recent commit style.
2. Select a coherent scope. Preserve unrelated work, secrets, dependencies, and generated output unless the repository explicitly tracks that output.
3. Review the documentation and agent guidance affected by the change, and update anything that became stale.
4. Run validation proportional to the change, following the repository's documented commands and hardware or environment constraints.
5. Stage intended paths explicitly. Use `git add -A` only after confirming every worktree change belongs in the commit.
6. Use an imperative commit subject. Add a short body only when it explains a non-obvious reason.
7. Commit without another confirmation unless the user asked to review or approve the message first.
8. Follow the repository's branch and push policy. If none exists, push only when the user explicitly requested it.

Use `ryan.ewen@gmail.com` as the commit author email. Do not add AI attribution or co-author trailers.
