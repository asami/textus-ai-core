# Phase 9 - Codex JSONL Response and Execution Observations

status=open
created_at=2026-10-11
scope=standalone Textus AI Codex provider enhancement
checklist=[Phase 9 Checklist](phase-9-checklist.md)
current_step=P9-JL
current_slice=P9-JL-01

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
on 2026-10-08 and identifies R9 as authored but unverified. R8 evidence does not
establish R9 acceptance. No tests or independent review were run when creating
this Phase, and no implementation is marked complete.

Slice P9-JL-01 reconciles R9's current purpose to the standalone provider and
authors the E10-E16/E25 paired examples while preserving that history. Authored
examples do not establish verified behavior; JL-02/JL-03 remain pending.

## Generation Prerequisite

The first P9-JL-ACCEPTANCE attempt on 2026-10-11 stopped in `cozyGenerate`
before Scala compilation or test execution: Cozy `0.3.2.2` required
`componentStyleCatalog.sha256`, which CNCF `0.5.3-SNAPSHOT` no longer supplies.
The project now selects the already locally published Cozy `0.3.3-SNAPSHOT`,
whose catalog reader uses `schemaVersion`, `resource`, `encoding` and `bytes`.
This is a project-owned generator selection correction, not a Cozy or CNCF
source change or publication. The failed attempt remains recorded; the new
selection and Phase 9 behavior still require fresh focused validation and
independent review.

The second P9-JL-ACCEPTANCE attempt admitted that Cozy/CNCF pair, then stopped
at `GENERATION_PROVENANCE_OUTPUT_TAMPERED` before compilation or tests.
sbt-cozy `0.1.16` installed 24 generated Scala files under
`src_managed/main/` instead of the expected `src_managed/main/scala/`.
The project now selects the already locally published sbt-cozy
`0.1.18-SNAPSHOT`, whose installer preserves the canonical Scala-root layout.
Both failed attempts remain recorded. No provenance check is disabled and no
generated file is moved by hand; fresh focused validation and independent
review are still required.

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
