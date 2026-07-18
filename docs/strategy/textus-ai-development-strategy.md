# Textus AI Development Strategy

status=draft
scope=internal development strategy
updated_at=2026-07-18

## Purpose

This strategy defines the development direction for Textus AI as the
provider-neutral AI execution runtime in the Textus technical system. It
sequences work without redefining CNCF ownership, CAR Review policy, or
provider-specific product behavior.

Textus AI is responsible for selecting and executing AI providers through the
CNCF `AiRunner` SPI. Applications own their domain policy, admitted evidence,
prompt contracts, and the decision to accept or reject an AI result.

## Development Principles

- Keep the component operation surface provider-neutral.
- Select provider, mode, model, limits, and tools through `AiRunnerRequirement`
  and purpose profiles.
- Keep review policy and provider fallback outside the runtime unless they are
  explicitly configured by the owning application.
- Prefer structured generation for domain integration and fail explicitly when
  output cannot satisfy the requested schema.
- Treat provider execution facts as attributable but never fabricate unknown
  provenance, usage, quota, or cost values.
- Make confidentiality the default: credentials and raw sensitive prompts or
  responses must not appear in public metadata or ordinary CallTree records.
- Promote settled behavior from notes to design and specification before
  declaring a phase complete.

## Existing Baseline

`textus-ai-runtime 0.2.0` provides the baseline on which the first formal
phase builds:

- component operations for `generate`, `chat`, and `generateRecord`;
- purpose-profile and request-level provider/model selection;
- Gemma/Ollama, OpenAI, and Google Gemini adapters;
- provider-neutral logical tools, including `url_context` and `web_search`;
- structured-record normalization and bounded retry; and
- response metadata and CallTree instrumentation.

This baseline predates the phase ledger. Its release history is evidence of
available behavior, not retroactive Phase completion evidence.

## Phase Overview

### Phase 1: CAR Review AI Execution Foundation

Goal: make Textus AI a safe and attributable execution dependency for bounded
CAR Review without absorbing Review policy into the runtime.

Scope:

- align the development dependency coordinates with the active Cozy generator
  contract;
- define normalized execution provenance, usage, limitation, and
  confidentiality vocabulary;
- add deterministic AI-provider fixtures for executable CAR Review scenarios;
- establish restrictive CallTree and metadata publication behavior;
- verify local and commercial structured execution failure semantics; and
- establish the CNCF lifecycle boundary for timeout, retry, cancellation, and
  concurrency.

Non-goals:

- no CAR Review rules, canonical Review Reports, or release-gate policy;
- no implicit local-to-commercial provider fallback;
- no automatic web retrieval for CAR Review purposes;
- no arbitrary process execution outside CNCF's managed-process capability; and
- no fabricated provider billing or unsupported cross-provider token equality.

Artifact: [Phase 1 dashboard](../phase/phase-1.md).

### Phase 2: Purpose Policy Runtime

Goal: make `AiRunnerRequirement.purpose` a strict, provider-neutral policy
boundary for application AI workflows.

Scope:

- require and validate purpose-profile resolution when a caller marks purpose
  as mandatory;
- resolve explicit provider, model, logical tools, and bounded execution policy
  from the selected purpose profile;
- reject absent profiles and unsupported provider/tool combinations rather than
  applying an implicit fallback; and
- propagate effective policy through runtime observability and enforce supported
  maximum-output, timeout, retry, and concurrency limits.

Non-goals:

- ArtScene caller integration, fetch-method fallback, prompts, and result
  schemas; and
- managed research orchestration, input-token budgeting, and cost
  admission/accounting.

Artifacts:

- [Phase 2 Dashboard](../phase/phase-2.md)
- [Phase 2 Checklist](../phase/phase-2-checklist.md)
- [ArtScene AI Fetch Purpose Contract Handoff](../journal/2026/07/2026-07-18-artscene-ai-fetch-purpose-contract-handoff.md)

### Phase 3: Managed Research and Execution Accounting

Goal: execute purpose-selected research as a bounded runtime workflow and make
input-token and cost decisions attributable without inventing unavailable
provider facts.

Scope:

- define stable provider-neutral execution facts for usage, estimation,
  accounting identity, and limitations;
- enforce purpose-policy input-token budgets before execution;
- support operator-owned rate schedules, explicit cost admission, and measured
  accounting where provider usage permits it; and
- orchestrate finite managed-research, source-comparison, and structured
  synthesis steps through resolved purpose policy and admitted CNCF
  capabilities.

Non-goals:

- arbitrary agent process, network, write, credential, or provider-command
  access;
- fabricated usage, price, quota, or monetary-cost values;
- implicit provider/model/tool or application fetch-method fallback; and
- application evidence policy, source authority, or final domain-result
  acceptance.

Artifacts:

- [Phase 3 Dashboard](../phase/phase-3.md)
- [Phase 3 Checklist](../phase/phase-3-checklist.md)

## Phase Ordering

Phase 1 was reopened on 2026-07-16 after the user clarified that Codex CLI is
a primary purpose of the runtime extension. The initial closure checkpoint
remains valid for the completed Gemma/OpenAI/Gemini contract work, but does not
claim Codex CLI support. The controlled Codex CLI provider, its CNCF
managed-process capability, and the sibling-CAR caller-scope integration were
completed on 2026-07-18.

## Process Status

- Phase 2: complete on 2026-07-18.
- Current phase: Phase 3, Managed Research and Execution Accounting.
- Current phase dashboard: `docs/phase/phase-3.md`
- Current phase checklist: `docs/phase/phase-3-checklist.md`
- Current next task: MR-01 execution-fact contract consolidation.
- Exploration input: `docs/notes/car-review-ai-runtime-design.md`
- Historical handoff: `docs/journal/2026/07/2026-07-16-car-review-ai-runtime-requirements.md`

## Document Roles

- `docs/strategy/` defines direction and phase ordering.
- `docs/phase/` is the current engineering ledger and source of phase status.
- `docs/notes/` holds exploratory and replaceable analysis.
- `docs/design/` records stable boundaries and decisions.
- `docs/spec/` records implementation-facing, testable behavior.
- `docs/journal/` records chronological history.
