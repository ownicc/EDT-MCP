# Exact BSL section text diff implementation plan

> Execute inline with superpowers:executing-plans. Track progress in Beads edt-mmh.2.

**Goal:** Return exact source fragments for the addressed BSL section.
**Architecture:** Capture EDT regions in ComparisonView, retain immutable source
coordinates in ComparisonTextDiff.Source, render paired fragments with JGit.
**Tech Stack:** Existing Java 25, EDT comparison API, JFace Document, JGit, JUnit/Tycho.
**Spec:** ../specs/2026-11-12-comparison-section-text.md

## Global constraints

Preserve module/default/preview behavior and EDT verdicts. Use session sides.
No new dependencies or parameters. Preserve 500000 UTF-16 / 20000 line input
limits and global 65536-byte/maxDiffLines output bounds. Work in local branch,
verify, merge locally into master; no PR or working-branch publication.

## Review focus

One-sided fragments; native split signature/body; missing/invalid/overlapping
ranges; CRLF/BOM/partial lines; many small fragments sharing one output budget.

## Task 1: Exact capture and bounded rendering

Files: utils/compare/{ComparisonView,ComparisonTextDiff,ComparisonSectionText}.java;
tests utils/compare/{ComparisonViewTest,ComparisonTextDiffTest}.java;
tools/impl/GetComparisonNodeTool.java and its existing test; docs/tools/get_comparison_node.md.

Interfaces: retain TreeAccess.moduleText(node, side) and ComparisonTextDiff.render.
Extend Source with immutable optional fragment coordinates. ComparisonSectionText
captures own ranges and method signature/body children; it never reads workspace files.

Write tests showing a changed child method does not change region-own text;
method signature/body/comments survive; source line numbers stay absolute across
disjoint and one-sided regions; missing/bad ranges produce unavailable; output
budget spans fragments; module and preview behavior stays compatible.
Run focused Maven/Tycho tests, observe expected failures, implement minimal capture
and formatting, rerun green. Update tool schema/guide. Verify native descriptor
assumptions on installed EDT. Run full Maven/Tycho verify and existing documentation
validator. Review complete diff, commit and merge locally into master. Close Beads
with evidence and report any real limitation.

Focused command (mcp directory, configured JDK/Maven):
`mvn -o verify --batch-mode -T 1 -Dtest=ComparisonTextDiffTest,ComparisonViewTest,GetComparisonNodeToolTest`
Expected after implementation: BUILD SUCCESS, no failures/errors.
Full command: `mvn -o verify --batch-mode -T 1`.
