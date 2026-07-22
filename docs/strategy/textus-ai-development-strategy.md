# Textus AI Development Strategy

status=draft
scope=internal development strategy
updated_at=2026-07-22

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
- Select provider, mode, model, limits, and tools through Textus AI-owned
  runtime profiles and application-purpose resolution.
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

### Phase 3: Execution Class and Accounting

Goal: introduce caller-selected execution classes alongside purposes and make
input-token and cost decisions attributable without inventing unavailable
provider facts.

Scope:

- define CNCF `executionClass` selection and purpose-plus-class resolution
  without exposing concrete provider/model selection;
- define stable provider-neutral execution facts for usage, estimation,
  accounting identity, and limitations;
- enforce execution-class policy input-token budgets before execution;
- support operator-owned rate schedules, explicit cost admission, and measured
  accounting where provider usage permits it; and
- record the decision boundary for any future runtime workflow without
  implementing application orchestration in this phase.

Non-goals:

- arbitrary agent process, network, write, credential, or provider-command
  access;
- fabricated usage, price, quota, or monetary-cost values;
- implicit provider/model/tool or application fetch-method fallback; and
- application evidence policy, source authority, or final domain-result
  acceptance; and
- managed-research or other multi-step runtime workflow implementation.

Artifacts:

- [Phase 3 Dashboard](../phase/phase-3.md)
- [Phase 3 Checklist](../phase/phase-3-checklist.md)

### Phase 4: Runtime Profiles and Application Purposes

Goal: replace configuration-defined model profiles and generic-purpose
inheritance with Textus AI-owned runtime profiles, standard purposes, and
strict bootstrap-registered application purposes.

Scope:

- supply `codex-cli` and `gemini` runtime profile defaults for all standard
  execution classes;
- supply the standard-purpose catalog and resolve bootstrap application-purpose
  registrations through it;
- apply merged CNCF runtime/execution-class overrides without allowing
  application callers to select concrete AI settings;
- reject the replaced model-profile, generic-purpose, base-purpose, and level
  configuration families structurally; and
- publish safe runtime-profile and effective execution-class facts.

Non-goals:

- no new CNCF `AiRunner` operation or CML operation;
- no application-owned provider/model/tool selector;
- no runtime workflow, scheduler, state store, or application fallback; and
- no implicit compatibility path for the replaced configuration families.

Artifacts:

- [Phase 4 Dashboard](../phase/phase-4.md)
- [Phase 4 Checklist](../phase/phase-4-checklist.md)
- [Runtime Profile and Application Purpose Specification](../journal/2026/07/2026-07-18-runtime-profile-and-application-purpose-specification.md)

### Phase 5: Local Gemma/Ollama Runtime Activation

Goal: expose the existing Gemma/Ollama adapter as a first-class, profile-owned
local runtime for application-purpose execution.

Scope:

- add the built-in `gemma` runtime profile with explicit defaults for every
  standard execution class;
- bind the selected profile to the existing CNCF HTTP adapter without exposing
  provider or model selection to application callers;
- use native Ollama as the default profile-owned runtime and resolve a CNCF
  managed service-container only when the operator explicitly selects
  `managed-docker`;
- document external endpoint precedence, managed-service configuration, and
  execution-class model tuning; and
- add deterministic profile, process-binding, and provider evidence, with an
  optional local Docker smoke verification.

Non-goals:

- no local-to-commercial provider fallback; and
- no CML operation or CNCF `AiRunner` contract change.

Artifacts:

- [Phase 5 Dashboard](../phase/phase-5.md)
- [Phase 5 Checklist](../phase/phase-5-checklist.md)

### Phase 6: Gemma-First Strategy and Provider-Neutral MCP Tool Orchestration

Goal: execute explicit bounded Gemma-first profiles and allow those profiles to
admit a named, operator-owned MCP tool set through the existing `generate` and
`chat` operations.

Scope:

- preserve application-purpose-only requests while a selected profile owns
  Gemma-first structured, tool-grounded, decomposed, validator-repair, and
  candidate-ranking execution;
- permit a second commercial provider only in an explicit Gemma-first profile
  and only for classified availability, timeout, malformed output, domain,
  evidence, or ambiguity outcomes;
- call an operator-configured application acceptance Operation between bounded
  attempts without moving domain rules into Textus AI;
- consume the CNCF MCP client Port and its admitted named server sets;
- keep MCP server selection, endpoint policy, credentials, tool allowlists,
  and transport settings under operator/runtime ownership;
- resolve MCP tool availability only from the selected runtime profile and
  execution class;
- run bounded provider function-call continuations for Gemma/Ollama, Anthropic
  Messages, OpenAI, and Google Gemini while preserving each provider's built-in
  web tools;
- keep Codex CLI, Antigravity CLI, and Claude Code CLI as profile-fixed managed-process providers
  outside the runtime-owned function loop; and
- publish redacted tool execution facts, limits, and structured failures to
  response metadata and CallTree records.

Non-goals:

- no new CML operation, and no direct application-caller MCP endpoint,
  server, header, or tool selection;
- no implicit fallback in conventional profiles and no fallback for policy,
  authorization, capability, credential, input, admission, or resource-limit
  failures;
- no provider-native remote-MCP pass-through in the initial slice;
- no stdio, SSE, arbitrary subprocess, arbitrary HTTP, or arbitrary
  filesystem MCP transport; and
- no assumption that every Gemma/Ollama model supports tool calling.

Artifacts:

- [Phase 6 Dashboard](../phase/phase-6.md)
- [Phase 6 Checklist](../phase/phase-6-checklist.md)

## Phase Ordering

Phase 1 was reopened on 2026-07-16 after the user clarified that Codex CLI is
a primary purpose of the runtime extension. The initial closure checkpoint
remains valid for the completed Gemma/OpenAI/Gemini contract work, but does not
claim Codex CLI support. The controlled Codex CLI provider, its CNCF
managed-process capability, and the sibling-CAR caller-scope integration were
completed on 2026-07-18.

## Process Status

- Phase 2: complete on 2026-07-18.
- Phase 3: complete on 2026-07-18.
- Phase 4: reopened and reclosed on 2026-07-18 for the application-purpose
  registration correction.
- Phase 4 release follow-up: on 2026-07-19, executable evidence confirmed
  late application Port binding after runtime scope creation retains the
  registered concurrency policy; the phase remains closed.
- Phase 4 runtime compatibility follow-up: on 2026-07-20, Codex profiles for
  the GPT-5.6 model family gained a managed CLI version gate. The phase remains
  closed because this hardens the existing runtime binding without changing the
  runtime-profile, standard-purpose, or application-purpose contracts.
- Phase 4 configuration-boundary follow-up: on 2026-07-20, Gemma, OpenAI, and
  Google provider assembly became dependent only on merged CNCF configuration.
  The phase remains closed because this removes an ambient bootstrap fallback
  without changing its public contracts.
- Phase 5: complete on 2026-07-21. The opt-in `GemmaOllamaLiveSpec` verified
  the managed Docker service, retained model volume, `gemma:2b` generation,
  and stop-on-shutdown cleanup through the component-facing runner path.
- Phase 6: reclosed on 2026-07-22 after PC-01 completed the five-class
  runtime-profile matrix, direct `gemma-work-*` `gemma3:12b` evidence, and the
  Gemma-first thinking policy. Sanpomap deliberately uses one-shot generation
  and deterministic validation; live repair is not a production or closure
  requirement.
- Phase 6 managed-CLI follow-up: on 2026-07-22, the `antigravity-cli` profile added
  an explicitly enabled CNCF managed-process binding for local Antigravity CLI.
  Deterministic specifications cover fixed sandboxed plan-mode headless
  execution, one CNCF-bounded text prompt argument, Web capability admission,
  and safe JSON metadata. Authenticated live evidence confirms the actual `agy`
  JSON response contract while authentication remains operator-owned.
- Phase 6 provider-neutral orchestration follow-up: on 2026-07-22,
  `prompt-grounded` execution added a bounded question/evidence loop for plain
  generation providers. Textus AI resolves and invokes only CNCF-admitted
  Operation/MCP tools, strips provider-native tool selection, and publishes
  counts and digests without prompt, argument, evidence, or provider payloads.
- Current phase ledger: `docs/phase/phase-6.md`
- Current next task: define the next runtime capability phase.
- Exploration input: `docs/notes/car-review-ai-runtime-design.md`
- Historical handoff: `docs/journal/2026/07/2026-07-16-car-review-ai-runtime-requirements.md`

## Document Roles

- `docs/strategy/` defines direction and phase ordering.
- `docs/phase/` is the current engineering ledger and source of phase status.
- `docs/notes/` holds exploratory and replaceable analysis.
- `docs/design/` records stable boundaries and decisions.
- `docs/spec/` records implementation-facing, testable behavior.
- `docs/journal/` records chronological history.
