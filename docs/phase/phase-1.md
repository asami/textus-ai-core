# Phase 1 - CAR Review AI Execution Foundation

status=complete

## Purpose

Phase 1 makes Textus AI a safe, provider-neutral execution dependency for
bounded CAR Review. It builds on the released `0.2.0` runtime without making
Textus AI responsible for Review rules, canonical Review Reports, or release
gates.

## Scope

- Align development dependency coordinates with the active Cozy generator
  contract.
- Define normalized provenance, usage, limitation, and confidentiality facts
  for `AiRunner` responses.
- Add a deterministic AI provider for CAR Review executable specifications.
- Establish restrictive metadata and CallTree behavior for bounded evidence.
- Normalize local and commercial provider failure outcomes needed by CAR Review.
- Define and verify timeout, retry, cancellation, and concurrency boundaries
  at the CNCF execution boundary.
- Add the local Codex CLI as a controlled `AiRunner` provider for bounded,
  schema-constrained CAR Review execution.

## Boundaries

- CBD Support owns evidence construction/redaction, prompt contracts, review
  policy, result admission, and release-gate decisions.
- Textus AI owns provider resolution, adapter execution, response
  normalization, and safe execution facts.
- CNCF owns the `AiRunner` SPI and Job execution lifecycle mechanisms.
- CNCF owns the managed-process capability used to invoke `codex exec`; Textus
  AI must not instantiate `ProcessBuilder` directly.
- Web tools remain disabled for CAR Review purposes unless a separately
  authorized source and citation contract is introduced.

## Active Work Stack

- A (DONE): AR-00 - Align the development dependency line with the active Cozy
  generator contract.
- B (DONE): AR-01 - Normalize execution facts.
- C (DONE): AR-02 - Add deterministic CAR Review provider fixtures.
- D (DONE): AR-03 - Restrict trace and metadata publication.
- E (DONE): AR-04 - Verify provider failure and lifecycle behavior.
- F (DONE): AR-05 - Promote the initial contracts and record the first closure
  checkpoint.
- G (DONE): AR-06 - Add the Codex CLI provider through the CNCF Process
  Execution capability, including sibling-CAR caller execution through the
  provider component scope.

Detailed status and acceptance evidence are recorded in
`phase-1-checklist.md`.

## Completion Conditions

Phase 1 closes only when:

- provenance, usage, limitation, and confidentiality semantics have one
  documented representation;
- deterministic executable specifications cover success, limitation, malformed
  output, unavailable provider, quota, timeout, cancellation, and retry;
- CallTree and response metadata follow the restrictive confidentiality policy;
- Gemma/Ollama, OpenAI, and Gemini outcomes are normalized without implicit
  provider fallback;
- lifecycle limits are bounded and their unsupported cases remain explicit; and
- settled behavior is promoted from notes into design and specification
  documents with corresponding executable evidence.
- Codex CLI execution is available through an explicit `codex` provider
  selection, with a read-only sandbox, explicit workspace, bounded input and
  output, schema-constrained structured generation, and no credential capture.

## Validation Evidence

- `sbt --batch test` passed on 2026-07-16: 45 tests in four suites succeeded,
  with no failures, cancellations, ignored tests, or pending tests.
- `TextusAiRunnerSpec` passed after the final structured-response metadata
  correction: 33 tests succeeded without live provider access.
- Normal CAR lint found no `FAIL` result. Its residual warnings are outside
  Phase 1 closure scope: a CAR artifact was not generated, the existing
  environment-based bootstrap configuration remains to be migrated, and no
  released ABI baseline was supplied.
- `git diff --check` passed for the final metadata correction.
- The post-implementation review found no actionable Phase 1 findings. It
  confirmed the final response metadata exposes
  `ai.execution.normalization_mode` rather than a bare compatibility key.
- AR-06 validation on 2026-07-17: `sbt --batch test` completed with 55
  successful Textus AI tests. CNCF `sbt --batch test` completed with 1,921
  successful tests, including 10 `ProcessExecutionModelSpec` and 5
  `ProcessExecutionWorkAreaSpec` cases. The Codex provider uses only the
  logical `codex-cli` capability; executable location, fixed `codex exec`
  arguments, sandbox, and session policy remain runtime-owned.
- AR-06 final integration validation on 2026-07-18: `sbt --batch test`
  completed with 58 successful Textus AI tests. `ComponentFactorySpec` proves
  `generate`, `generateRecord`, and `chat` execute through Textus AI's
  component-owned driver and admission when the caller is a sibling component
  scope. A Sanpomap command configured with the local `codex` purpose profile
  generated Scenario DSL through the assembled Textus AI Runtime path.

## Closure Correction

The 2026-07-16 closure checkpoint is superseded. The user clarified that
Codex CLI execution is a primary purpose of this runtime extension. The prior
boundary that excluded Codex CLI was therefore incorrect for Phase 1. AR-06
and its cross-component caller-scope validation completed on 2026-07-18, so
the phase is closed with Codex CLI included in its delivered scope.

The following remain deferred after AR-06 unless explicitly promoted:

- normalize safe provider request identity and measured timing;
- add CNCF Job lifecycle support for cancellation propagation and concurrency
  enforcement; and
- evaluate typed CNCF response fields after provider metadata semantics remain
  stable across further use.

## Working References

- `docs/strategy/textus-ai-development-strategy.md`
- `docs/notes/car-review-ai-runtime-design.md`
- `docs/journal/2026/07/2026-07-16-car-review-ai-runtime-requirements.md`
- `docs/spec/mock-ai-executable-spec.md`
- `docs/spec/phase-1-ai-runner-executable-spec.md`
