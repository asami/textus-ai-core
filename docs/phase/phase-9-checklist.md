# Phase 9 Checklist - Codex JSONL Response and Execution Observations

status=open
phase=[Phase 9](phase-9.md)
current_step=P9-JL
current_slice=P9-JL-01

`[~]` denotes an inherited implementation draft awaiting verification; it does
not mean accepted behavior. Historical R8 results retain their original scope.

## Stage JL-01 - Standalone Scope and Specification

Stage Status:

- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when all checklist items in this Stage are checked.

- [x] Register JSONL handling as a Textus AI feature, independent of sm-workflow integration.
- [x] Correct the R9 specification's current sm-workflow-required wording without erasing history.
- [x] Align the authored paired specification with latest completed-answer selection, failed and
  unfinished turns, irrelevant events, and optional execution observations.
- [x] State compatibility for named JSONL capabilities and the existing unprofiled plain-output route.
- [x] Adopt inherited development metadata at 0.2.3-SNAPSHOT and CNCF 0.5.3-SNAPSHOT without schema or coordinate changes.

Documentation items are authored in P9-JL-01. Stage acceptance remains OPEN
pending the parent-owned focused validation and independent Step review/commit;
these checks do not mark JL-02/JL-03 behavior as verified.

## Stage JL-02 - Provider Adapter and Normalization

Stage Status:

- Current status: IN_PROGRESS
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when the inherited draft satisfies all items below with evidence.

- [~] Fixed named Codex bindings and JSONL decoding return the completed answer without tool-output leakage.
- [~] Failed or unfinished turns do not become successful generation results.
- [~] Missing or ambiguous optional context facts do not reject an otherwise useful completed answer.
- [~] Generate, generateRecord and chat preserve runtime-owned observations through normalization.
- [ ] Confirm existing plain-output compatibility and unchanged managed-process permissions.

## Stage JL-03 - Executable Evidence and Closure

Stage Status:

- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when all required executable evidence and dispositions are recorded.

- [ ] Focused CodexRuntimeProviderSpec examples cover completed, failed and unfinished
  streams, irrelevant events, optional context and caller/semantic-text hints.
- [ ] ComponentFactorySpec verifies the normal provider-owned managed-process
  route using a controlled executable and the fixed JSONL arguments.
- [ ] Affected normalization/profile regressions pass with actual test counts and results recorded.
- [ ] Integrated review addresses response compatibility, meaningful failure boundaries and metadata confidentiality.
- [x] Current docs no longer treat this feature as required sm-workflow producer work.
- [ ] Record P9-JL-ACCEPTANCE and P9-JL-ACCUMULATOR counts/logs, independent Step
  review and the exact local Step acceptance commit.
- [ ] Record one comprehensive Phase review, final full test results and applicable CAR lint.
- [ ] Record Phase 9 closure against this checklist without claiming parent-context
  transfer, actual model computation or sm-workflow acceptance from fixtures,
  and bind closure/checklist/journal evidence to the exact final local
  phase-release commit. Publication, deployment and push are excluded.
