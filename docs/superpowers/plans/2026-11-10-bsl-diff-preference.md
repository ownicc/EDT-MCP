# BSL Diff Preference Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this small change inline.

**Goal:** Enable the method-aware MCP BSL review viewer through a saved checkbox.
**Architecture:** Add one default-off Eclipse boolean preference and use it alongside the existing explicit JVM override when a review opens.
**Tech Stack:** Java 17, Eclipse JFace/SWT, JUnit4, Maven/Tycho on JDK25.
**Spec:** ../specs/2026-11-10-bsl-diff-preference-design.md

## Constraints and review focus

- Manual opt-in, no launcher edits or restart; existing reviews keep their viewer.
- Preserve explicit JGit override, Difftastic selection and standard fallback.
- Local branch, then merge into master; no push or release in this task.
- Verify real preference load/save/defaults and viewer selection; avoid source writes.

## Task 1: Preference and viewer selection

- [x] Add a regression for default-off preference and false/true/false viewer selection, plus legacy JVM override; run RED.
- [x] Add preference constant/default, GeneralTab checkbox load/save/reset, localized messages, and viewer predicate.
- [x] Update README and viewer documentation; run focused tests, then the required complete unit build.
- [x] Exercise the settings and actual review controls in an isolated Tycho EDT workbench, without applying a proposal; inspect logs.
- [x] Review the diff and record results; commit on the local branch and merge into master as the finishing step.
