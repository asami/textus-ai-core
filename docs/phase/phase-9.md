# Phase 9 - Codex JSONL Response and Execution Observations

status=closed
created_at=2026-10-11
closed_at=2026-10-11
scope=standalone Textus AI Codex provider enhancement
checklist=[Phase 9 Checklist](phase-9-checklist.md)
current_step=none
current_slice=none

## Goal

Complete and verify the inherited JSONL adapter draft as a Textus AI feature.
Applications continue to use the existing provider-neutral AiRunner operations.
This Phase does not require or implement an sm-workflow connection.

## Ownership and Scope

- Textus AI owns fixed Codex invocation arguments, provider response decoding,
  normalization and safe execution observations.
- CNCF retains managed-process, UnitOfWork and generic AiRunner ownership.
  Consume its existing contracts; this plan grants no new CNCF implementation scope.
- Applications retain task orchestration and acceptance of semantic results.
- Consume the completed agent answer, not command output or the whole JSONL stream.
- Verify failed and unfinished turns, irrelevant events, and optional absent or
  ambiguous context facts through executable specifications.
- Preserve generate, generateRecord, chat and the unprofiled plain-output route.
- Keep observations attributable to the runtime response rather than caller or
  generated-text claims. A child thread ID does not transfer the parent context
  or prove independent review, source edits, or Workflow completion.

These are work targets; behavior contracts belong in the paired Markdown and
executable specifications, not in this progress ledger.

## Non-Goals

- No sm-workflow consumer changes, internal AI dispatch, or Phase 5.1 acceptance gate.
- No skill wiring, parent-context transfer, or attached-context capability.
- No real-time streaming API; the inherited draft decodes output after process exit.
- No new CML operation, unrestricted command arguments, or sandbox relaxation.
- No mandatory paid/live model call solely to test JSONL parsing.

## Inherited Work and Evidence

The 2026-10-11 read-only inventory found existing uncommitted changes in
CodexExecutionBinding, CodexRuntimeProvider, AiExecutionFacts,
CodexRuntimeProviderSpec and ComponentFactorySpec, plus the new untracked
CodexExecutionEvents parser. Dependency/version and CAR metadata changes are
also present. Phase 9 explicitly adopts `project.yaml`, `abi-manifest.json` and
`component-descriptor.json` as development inputs at `0.2.3-SNAPSHOT`, with
CNCF `0.5.3-SNAPSHOT`. Their coordinates and metadata schemas are retained.

[AI execution facts](../spec/ai-execution-facts.md) records R8 focused validation
on 2026-10-08 and initially identified R9 as authored but unverified. R8 evidence does not
establish R9 acceptance. No tests or independent review were run when creating
this Phase; the completion evidence below was obtained afterwards.

Slice P9-JL-01 reconciles R9's current purpose to the standalone provider and
authored the E10-E16/E25 paired examples while preserving that history. Their
subsequent validation and acceptance are recorded below, not inferred from authorship.

## Generation Prerequisite

The first P9-JL-ACCEPTANCE attempt on 2026-10-11 stopped in `cozyGenerate`
before Scala compilation or test execution: Cozy `0.3.2.2` required
`componentStyleCatalog.sha256`, which CNCF `0.5.3-SNAPSHOT` no longer supplies.
The project now selects the already locally published Cozy `0.3.3-SNAPSHOT`,
whose catalog reader uses `schemaVersion`, `resource`, `encoding` and `bytes`.
This is a project-owned generator selection correction, not a Cozy or CNCF
source change or publication. The failed attempt remains recorded; the new
selection and Phase 9 behavior subsequently passed fresh focused validation and
independent review as recorded below.

The second P9-JL-ACCEPTANCE attempt admitted that Cozy/CNCF pair, then stopped
at `GENERATION_PROVENANCE_OUTPUT_TAMPERED` before compilation or tests.
sbt-cozy `0.1.16` installed 24 generated Scala files under
`src_managed/main/` instead of the expected `src_managed/main/scala/`.
The project now selects the already locally published sbt-cozy
`0.1.18-SNAPSHOT`, whose installer preserves the canonical Scala-root layout.
Both failed attempts remain recorded. No provenance check is disabled and no
generated file was moved by hand; fresh focused validation and independent
review subsequently accepted the canonical 24-file Scala-root generation.

## Work Order

1. Reconcile the specification and inherited draft to the standalone adapter scope.
2. Preserve the inherited decoder and normalization; add the frozen completion,
   failure and optional-context examples under Step P9-JL / Slice P9-JL-01.
3. Run P9-JL-ACCEPTANCE (CodexRuntimeProviderSpec and ComponentFactorySpec) and
   P9-JL-ACCUMULATOR (AiExecutionFactsSpec, AiExecutionObservationSpec,
   AiRuntimeProfileSpec and TextusAiRunnerSpec), then obtain independent Step
   review and an exact local acceptance commit. Controlled executables provide
   process evidence, not evidence of actual model computation.
4. Obtain one comprehensive Phase review, final full tests and applicable CAR
   lint, then record closure/checklist/journal evidence in the final local
   phase-release commit. Publication, deployment and push remain excluded.

## Closure Criteria

- JSONL completion and failure behavior is documented and executable.
- Normal generate, generateRecord and chat paths consume the decoded response.
- Plain-output compatibility and existing profile/process boundaries remain covered.
- Optional observations remain optional and contain no fabricated context or completion claims.
- Affected executable specifications and regressions have recorded results and
  integrated review findings are resolved or explicitly dispositioned.
- Current documentation presents this as a standalone Textus AI capability,
  without requiring sm-workflow integration.
- Independent Step review/acceptance commit, one comprehensive Phase review,
  final full tests, applicable CAR lint and the exact final local phase-release
  commit are recorded before closing Phase 9.

## Completion Evidence

Step P9-JL / Slice P9-JL-01 is accepted in local commit
`42c14dcb3bb53532f7b75e9f426078d4cbcca05e`, retaining original base
`1409bddb0448d831d0b5a85ab20deb6ac3e87cc3`. P9-JL-ACCEPTANCE passed 42 tests
in two suites; P9-JL-ACCUMULATOR passed 100 tests in four suites. Independent
Step review and the one comprehensive Phase review both returned PASS.

The first executable full-suite run exposed a stale runtime-identity expectation
for `0.2.2` after the admitted development metadata selected `0.2.3-SNAPSHOT`.
Repair P9-FINAL-001 changed only the three version expectations and latest spec
header. Its focused identity suite passed 3/3; independent focused re-review
returned PASS, retaining the original full-review lineage and repair cycle 1.

Final full validation on 2026-10-11 passed 195 tests in 25 suites, with zero
failures or aborted suites. Five opt-in live-provider tests were canceled;
paid/live execution was not required or claimed. SBT and wrapper exited 0 and
the shared serial lock was released. CAR lint has no FAIL; its missing released
ABI baseline and intentionally selected sbt-cozy version remain disclosed warnings.

Six accepted nonblocking Hygiene records remain once in their canonical
[journal](../journal/2026/10/2026-10-11-phase-9-hygiene-follow-up.md). There are
no open Current Phase Blockers, Development Candidate records or unpersisted
accepted findings. Runtime help execution remains unverified; static CAR
documentation/help navigation was inspected without claiming runtime evidence.

The [completion journal](../journal/2026/10/2026-10-11-phase-9-completion.md)
records exact commands, logs, review identities, retained failures and the
shared-runner prerequisite recovery. This closed projection becomes effective
only in the verified final local phase-release commit carrying
`Phase-Closure-Binding: textus-ai-phase9-closure-20261011@1`.
No successor, sm-workflow integration, product deployment, publication or push
is included. Development coordinates remain unchanged.
