# Phase 1 - CAR Review AI Execution Foundation Checklist

This checklist is the authoritative progress ledger for Phase 1. A checklist
item becomes DONE only after its implementation, executable specification,
focused validation, and review evidence exist. The 2026-07-16 closure
checkpoint was reopened when Codex CLI was confirmed as required scope.

## Stage 0 - Development Dependency Alignment

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Keep this stage DONE only while the current Cozy generator and
  declared CNCF/model dependency coordinates compile together.

### AR-00: Align the Development Dependency Line

- [x] Move Textus AI development to `goldenport-cncf 0.5.1-SNAPSHOT` and
  `simplemodeling-model 0.1.8-SNAPSHOT`, matching the active Cozy generator
  contract.
- [x] Recompile the generated `LlmSession` sources and run `sbt --batch test`:
  32 tests succeeded on 2026-07-16.

## Stage 1 - Normalized Execution Facts

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Update the checklist when the contract and its executable
  evidence are both available.

### AR-01: Provenance, Usage, Limitation, and Confidentiality Contract

- [x] Select a stable metadata namespace for normalized execution facts;
  typed CNCF response fields remain deferred until provider semantics are
  proven stable.
- [x] Define provider/model/mode/engine, purpose, response identity, finish,
  and normalization facts. Timing, attempt, and retry remain open.
- [x] Define unknown-value semantics for token and cost-related facts. Quota
  semantics remain open.
- [x] Define safe input/output digest ownership and reconciliation.
- [x] Define which fields are public response metadata, CallTree-only, or
  prohibited from recording.

## Stage 2 - Deterministic Provider and Structured Review Scenarios

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark items DONE only with deterministic executable
  specifications that do not call a live provider.

### AR-02: Deterministic CAR Review Provider

- [x] Add a deterministic `AiRunner` fixture that produces schema-valid
  candidate results and normalized execution facts.
- [x] Add fixtures for Unknown/limitation, malformed output, empty output,
  unavailable provider, quota, timeout, cancellation, and retry-then-success.
- [x] Add a CAR Review-shaped record-schema fixture proving that provider
  substitution does not change the caller contract.

## Stage 3 - Restricted Trace and Metadata Publication

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark items DONE only after redaction behavior is executable and
  reviewed against the intended CallTree surface.

### AR-03: Confidential Execution Observability

- [x] Implement the selected digest-only restrictive trace mode.
- [x] Ensure raw evidence, prompts, provider response bodies, credentials, and
  sensitive provider errors are absent from ordinary metadata and CallTree.
- [x] Add executable specifications for allowed facts, redaction, and missing
  provenance limitations.

## Stage 4 - Provider and CNCF Lifecycle Verification

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark items DONE only after each provider result is normalized
  and the CNCF lifecycle boundary is verified.

### AR-04: Explicit Provider and Lifecycle Outcomes

- [x] Verify Gemma/Ollama structured success and explicit unavailable/model
  failure behavior.
- [x] Normalize OpenAI and Gemini usage, refusal, quota, rate-limit, and safe
  response-identity facts where providers supply them.
- [x] Define timeout, retry, cancellation, and concurrency behavior through the
  CNCF execution boundary, explicitly recording unsupported propagation.
- [x] Prove that no provider or model fallback occurs unless the caller's
  resolved configuration explicitly selected it.

## Stage 5 - Contract Promotion and Closure

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Close the stage only when every Phase 1 completion condition is
  evidenced and no unsettled design claim remains in notes alone.

### AR-05: Promote and Close

- [x] Promote stable execution-fact and confidentiality decisions to
  `docs/design/`.
- [x] Promote testable behavior to `docs/spec/` and executable specifications.
- [x] Record validation and review evidence in the phase documents.
- [x] Update the strategy status and close Phase 1.

Closure evidence:

- `sbt --batch test` passed with 45 successful tests on 2026-07-16.
- Normal CAR lint reported no `FAIL`; its residual warnings are recorded in
  `phase-1.md` as out-of-scope publication and bootstrap follow-up.
- Final metadata-policy review confirmed `normalization_mode` is retained only
  in the `ai.execution.*` namespace on runner responses.

## Stage 6 - Codex CLI Provider

Stage Status:
- Current status: IN_PROGRESS
- Owner: Textus AI and CNCF maintainers
- Update rule: Mark this stage DONE only when a controlled Codex CLI adapter
  executes through the `AiRunner` provider path and its process boundary is
  covered by deterministic executable specifications.

### AR-06: Controlled Codex CLI Execution

- [ ] Extend CNCF's managed-process capability to support bounded stdin,
  bounded output capture, explicit working root, and cancellation without
  direct `ProcessBuilder` use in a CAR.
- [ ] Add a `codex` / `codex-cli` Textus AI provider that invokes `codex exec`
  with read-only sandbox and ephemeral-session defaults.
- [ ] Support schema-constrained `generateRecord` and bounded `generate` /
  `chat` results without recording prompts, output, credentials, or account
  identity in response metadata or CallTree.
- [ ] Add fake-process executable specifications for command construction,
  response parsing, unavailable CLI, non-zero exit, timeout, cancellation,
  and output-limit behavior.
- [ ] Document provider configuration, explicit enablement, authentication
  boundary, and the no-live-Codex-test policy.
