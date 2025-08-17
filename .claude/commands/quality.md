---
description: Run lint, static analysis, and tests
allowed-tools: Bash(./gradlew:*), Bash(git status:*), Bash(git diff:*)
argument-hint: [optional Gradle args]
---

## Context
- Git: !`git status --porcelain`
- Diff: !`git diff --stat -50`

## Task
- !`./gradlew ktlintCheck detekt test $ARGUMENTS`
- Summarize failures and propose fixes.
