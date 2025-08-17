---
description: Auto-fix Kotlin style with ktlint and list changed files
allowed-tools: Bash(./gradlew:*), Bash(git:*)
---

## Task
- !`./gradlew ktlintFormat`
- !`git status --porcelain`
- !`git diff --stat -200`
