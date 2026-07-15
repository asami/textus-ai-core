# CAR Review AI Runtime Design

status=draft
updated_at=2026-07-16
tag=textus-ai, car-review, ai-runner, provenance, structured-output

## Purpose

This note explores how Textus AI can support bounded semantic CAR Review
without taking ownership of CAR Review policy. It promotes the runtime design
questions from the related journal handoff into a design-oriented working
document. The journal remains the chronological record of why this work was
opened.

Textus AI provides provider-neutral execution through CNCF `AiRunner`. CAR
Review remains a CBD Support concern: it constructs and redacts evidence,
selects prompts and rules, reconciles AI output with deterministic evidence,
and decides review and release-gate outcomes.

## Design Boundary

The component boundary is intentionally narrow.

Textus AI owns:

- provider, mode, engine, model, and purpose-profile resolution;
- `generate`, `chat`, and structured `generateRecord` execution;
- provider adapter normalization for Gemma/Ollama, OpenAI, and Google Gemini;
- call-level timeout, retry, limits, safe observability, and execution facts;
- explicit structured failure for unavailable or unsupported capabilities.

Textus AI does not own:

- CAR Review rules, canonical review reports, or release policy;
- construction, redaction, or admission of Review Evidence;
- commercial-provider enablement, cost policy, or implicit provider fallback;
- Codex CLI invocation or its sandbox and authorization lifecycle.

CBD Support may use a Codex review adapter at its own authorized process
boundary. That adapter is not a Textus AI provider in the first design.

## Existing Execution Model

CAR Review uses the existing component surface. It must not introduce a
provider-specific operation.

```text
CBD Support
  -> bounded Review Evidence + versioned prompt contract + output schema
  -> AiRunnerRequirement(purpose, provider/model overrides, limits)
  -> TextusAiRunner.generateRecord
  -> selected provider adapter
  -> normalized structured response, metadata, and CallTree facts
  -> CBD Support reconciles candidate result with deterministic Evidence
```

`purpose` is the stable integration surface. It allows a CAR to select a
review behavior without embedding a provider or concrete model name in its
application code. Request values remain higher priority than purpose-profile
defaults.

Candidate purpose names are:

- `car-review.semantic-consistency`
- `car-review.domain-terminology`
- `car-review.documentation-clarity`
- `car-review.test-adequacy`
- `car-review.finding-explanation`
- `car-review.observation-grouping`

These names are provisional. They should become a stable vocabulary only after
the CBD Support review contract is validated.

## Structured Review Output

`generateRecord` is the primary operation for review work. The caller supplies
a bounded evidence representation, a versioned prompt contract, and the
expected record schema.

The runtime must preserve these invariants:

- Empty, malformed, or partial provider output never becomes a successful empty
  review result.
- Normalization does not invent facts absent from the provider response.
- Missing required fields, invalid enum values, and unsupported schema features
  produce structured diagnostics.
- Retry is bounded and visible in response metadata.
- Input and output size limits are independent of a provider token limit.
- Provider-specific JSON and error bodies do not escape into the public review
  contract.

The schema defines a candidate result, not a final review finding. CBD Support
classifies each result as a Finding, Assurance, Unknown, or limitation after
deterministic reconciliation.

## Provider Selection

Provider selection is explicit and attributable.

| Selection | Intended role | Fallback rule |
| --- | --- | --- |
| Gemma/Ollama local | Frequent, low-cost classification and grouping | Missing service or model is unavailable; no implicit remote fallback |
| OpenAI remote | Explicit commercial review execution | No implicit provider or model switch |
| Google Gemini remote | Explicit commercial review execution | No implicit provider or model switch |

Local execution is described as local, not free. It consumes compute, memory,
and operational capacity even when it has no per-call API invoice.

`web_search` and `url_context` are disabled for every CAR Review purpose by
default. Review operates on admitted Evidence; external retrieval needs its own
source, authorization, citation, confidentiality, and cost contract.

## Normalized Execution Facts

Provider adapters should expose equivalent facts through a provider-neutral
response model or a documented metadata namespace. The eventual representation
is an open design decision, but the vocabulary is required.

### Provenance

- effective provider, mode, engine, and model;
- Textus AI and adapter version;
- purpose and caller-supplied prompt-contract identity;
- input and output digest;
- provider request or response identity when safe to expose;
- start time, completion time, elapsed time, attempt count, and retry count;
- finish, refusal, normalization, and output-schema identity; and
- explicit limitations when a fact is unavailable.

### Usage

- input, cached-input, output, reasoning, and total tokens when reported;
- request and retry count;
- local versus remote execution; and
- provider-reported quota or rate-limit state.

Unknown measurements must remain unknown. Textus AI must not publish a false
zero or fabricate a monetary cost from incomplete provider information.

## Confidentiality and Observability

CAR Review requires a restrictive tracing default. CallTree records safe
selection, counts, sizes, digests, duration, outcome, and normalized usage.
It must not record raw evidence, prompts, or model responses by default.

Diagnostic retention of protected artifacts requires an explicit authorization,
storage, and retention policy outside this runtime default. Provider error
messages, URLs, and metadata must be sanitized before they reach CallTree or a
public response. Credentials and sensitive CAR content must never be recorded.

The existing trace policy is a starting point. A digest-only trace mode may be
required if it cannot express this policy precisely.

## Failure and Lifecycle Semantics

Textus AI returns an attributable execution outcome. CBD Support decides
whether that outcome fails a Review Run or release gate.

The runtime must distinguish:

- provider unavailable or model incompatible;
- quota exhaustion, rate limiting, and policy refusal;
- timeout and cancellation;
- malformed output and schema-normalization failure; and
- internal defects.

Retries must be bounded for every outcome. Cancellation and concurrency limits
must integrate with the CNCF Job lifecycle where the current synchronous
`Consequence` boundary permits it.

## Deterministic Provider for Executable Specifications

CAR Review integration cannot depend on a live model. A deterministic test
`AiRunner` is needed to exercise the CBD contract independently of an adapter.
It should produce fixed fixtures for:

- schema-valid structured success and candidate findings;
- explicit Unknown and limitation results;
- malformed and empty output;
- timeout, cancellation, quota, and provider-unavailable failures;
- retry-then-success behavior; and
- normalized provenance and usage facts.

This provider proves that the CAR Review contract remains unchanged when the
local or commercial adapter changes.

## Delivery Sequence

1. Define typed or namespaced normalized provenance, usage, confidentiality,
   and limitation facts for `AiRunner` responses.
2. Add the deterministic provider and CAR Review-shaped record-schema fixture.
3. Configure CAR Review purposes with restrictive tracing defaults.
4. Verify Gemma/Ollama structured generation and explicit unavailable results.
5. Normalize OpenAI and Gemini usage, refusal, quota, and response identity.
6. Verify timeout, cancellation, retry, concurrency, and sensitive-data
   behavior through CNCF execution boundaries.
7. Integrate the admitted AI bundle into CBD Support without moving review
   policy into Textus AI.

## Open Decisions

- Should normalized execution facts be typed CNCF `AiRunner` fields or a stable
  metadata namespace?
- Where should input and output digests be calculated and reconciled?
- How should digest-only CallTree tracing be represented?
- Which token and quota values have equivalent meaning across providers?
- How can cancellation cross the synchronous `Consequence` boundary?
- Does local model capability and quality limitation need a standard descriptor?
- Which decisions belong in the CNCF `AiRunner` SPI and which remain adapter
  behavior in Textus AI?

## Related Record

The originating non-normative handoff is
[`2026-07-16-car-review-ai-runtime-requirements.md`](../journal/2026/07/2026-07-16-car-review-ai-runtime-requirements.md).
