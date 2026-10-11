# Phase 9 Checklist - Codex JSONL Response and Execution Observations

status=closed
phase=[Phase 9](phase-9.md)
current_step=none
current_slice=none

The inherited draft is now accepted by the Phase 9 evidence below. Historical
R8 results retain their original scope; they were not promoted into R9 acceptance.

## Stage JL-01 - Standalone Scope and Specification

Stage Status:

- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when all checklist items in this Stage are checked.

- [x] Register JSONL handling as a Textus AI feature, independent of sm-workflow integration.
- [x] Correct the R9 specification's current sm-workflow-required wording without erasing history.
- [x] Align the authored paired specification with latest completed-answer selection, failed and
  unfinished turns, irrelevant events, and optional execution observations.
- [x] State compatibility for named JSONL capabilities and the existing unprofiled plain-output route.
- [x] Adopt inherited development metadata at 0.2.3-SNAPSHOT and CNCF 0.5.3-SNAPSHOT without schema or coordinate changes.

Stage acceptance is recorded in Step commit `42c14dc`, following focused
validation and independent Step review, with the final Phase evidence below.

## Stage JL-02 - Provider Adapter and Normalization

Stage Status:

- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when the inherited draft satisfies all items below with evidence.

- [x] Fixed named Codex bindings and JSONL decoding return the completed answer without tool-output leakage.
- [x] Failed or unfinished turns do not become successful generation results.
- [x] Missing or ambiguous optional context facts do not reject an otherwise useful completed answer.
- [x] Generate, generateRecord and chat preserve runtime-owned observations through normalization.
- [x] Confirm existing plain-output compatibility and unchanged managed-process permissions.

## Stage JL-03 - Executable Evidence and Closure

Stage Status:

- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when all required executable evidence and dispositions are recorded.

- [x] Focused CodexRuntimeProviderSpec examples cover completed, failed and unfinished
  streams, irrelevant events, optional context and caller/semantic-text hints.
- [x] ComponentFactorySpec verifies the normal provider-owned managed-process
  route using a controlled executable and the fixed JSONL arguments.
- [x] Affected normalization/profile regressions pass with actual test counts and results recorded.
- [x] Integrated review addresses response compatibility, meaningful failure boundaries and metadata confidentiality.
- [x] Current docs no longer treat this feature as required sm-workflow producer work.
- [x] Record P9-JL-ACCEPTANCE and P9-JL-ACCUMULATOR counts/logs, independent Step
  review and the exact local Step acceptance commit.
- [x] Record one comprehensive Phase review, final full test results and applicable CAR lint.
- [x] Record Phase 9 closure against this checklist without claiming parent-context
  transfer, actual model computation or sm-workflow acceptance from fixtures,
  and bind closure/checklist/journal evidence to the exact final local
  phase-release commit. Publication, deployment and push are excluded.

Evidence: focused acceptance 42/42 (two suites), accumulator 100/100 (four
suites), identity repair 3/3 (one suite), and final full suite 195/195 (25
suites, five opt-in live tests canceled). Independent Step/full/focused reviews
are PASS. CAR lint has no FAIL, with two disclosed warnings. Six Hygiene
records are persisted and no accepted finding remains unpersisted.

See the [completion journal](../journal/2026/10/2026-10-11-phase-9-completion.md).
Closure is effective only in the verified local phase-release commit carrying
`Phase-Closure-Binding: textus-ai-phase9-closure-20261011@1`.
