# AI Execution Observation Contract

status=implemented-incrementally
since=2026-07-23
phase=[Phase 7](../phase/phase-7.md)

## Purpose

`AiExecutionObservation` is the public, provider-neutral projection of a
completed `AiGenerateResponse` for application-owned evidence artifacts. It
lets an application retain comparable execution facts and hand only artifact
references to Textus Corpus and Textus Experiment. It is not an execution log
and it is not a replacement for application acceptance.

The contract is implemented in
`org.simplemodeling.textus.ai.runtime.AiExecutionObservation`. The initial
projection is intentionally limited to generate responses; chat can gain the
same projection when a Phase 7 application needs it.

## Input Boundary

The projection accepts:

- a completed `AiGenerateResponse` and its normalized metadata;
- application-owned schema/evidence/acceptance assessment;
- safe identities of CNCF evidence sources; and
- six sanitized artifact references: execution plan, prompt contract, strategy,
  execution evidence, acceptance evidence, and metric.

Artifact references must be opaque, non-Web scheme-based identifiers without a
query or fragment. This prevents references from becoming an alternate
transport for prompt text, private URLs, credentials, or provider payloads.
`textus-plan://...`, `textus-contract://...`, and
`sanpomap-evidence://...` are valid examples.

The projection deliberately does not accept or retain:

- prompt text, model output, request/response payloads, provider request IDs,
  endpoints, credentials, or account identities;
- arbitrary response metadata; or
- raw CNCF Operation/MCP result text.

It uses only the normalized Textus AI keys required for comparison. The
execution and output digests are retained as stable correlation facts rather
than the corresponding contents.

## Recorded Facts

The safe fact map contains these groups:

- route identity: provider, mode, engine, model, purpose, application purpose,
  runtime profile, execution class, location, strategy, policy snapshot, and
  rate schedule identity;
- operations: elapsed milliseconds, provider-attempt count, normalized token
  quantities, and API monetary cost;
- provider-standard tools: requested and explicitly admitted logical tools,
  plus observed provider Web/Search or URL-context call/result counts and
  tool-specific charge-basis state when the adapter reports them;
- CNCF evidence: safe source identities and runner-reported Operation/MCP call
  counts. These are not provider tool facts;
- acceptance: application outcome, schema validity, evidence validity, failure
  classification, and stable reason codes; and
- evidence references and normalized limitation codes.

`reported`, `estimated`, `unavailable`, and `not-applicable` are separate
measurement states. A missing quantity has no numeric value. Local execution
records API monetary cost as `not-applicable`; subscription-backed Codex,
Antigravity, and Claude Code execution records it as `unavailable` unless an
API-billed measured schedule is supplied. An admission upper bound is retained
as `estimated`, not as a measured API cost.

Provider adapters do not yet expose a priced tool ledger. The initial contract
therefore records each requested tool's charge basis as `unavailable`, rather
than silently treating provider-standard tool use as free.

## Ownership And Use

Sanpomap owns the application assessment and the lifecycle/retention of the
three evidence artifacts. It stores the `safeFacts` projection in its
sanitized metric artifact, then passes the three artifact identifiers to
`ExperimentManagement.recordObservation`. Textus AI does not import Corpus or
Experiment implementation classes and does not persist observations itself.

SP-02 will connect one selected Sanpomap execution to this contract through
the assembled Corpus and Experiment SPI path. This contract does not perform
provider fan-out or create additional paid AI requests.
