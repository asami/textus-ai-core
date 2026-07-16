# CAR Review AI Runtime Requirements Handoff

status=consideration
updated_at=2026-07-16
tag=textus-ai, ai-runner, car-review, ollama, openai, gemini, provenance, cost

## Context

Textus CBD Support plans to use AI for bounded semantic CAR Review while
keeping deterministic Cozy, sbt, CNCF, catalog, and runtime Evidence primary.
The selected integration model supports local Codex, local Gemma/Ollama, and
commercial OpenAI or Google Gemini execution without making a commercial API
the default path.

This entry records the Textus AI runtime capabilities needed by that design.
It is a non-normative handoff. The corresponding CBD product decision is
recorded in
`textus-cbd-support/docs/journal/2026/07/car-review-ai-integration-modes-2026-07-16.md`.

## Supersession Note

On 2026-07-16, the user clarified that controlled Codex CLI use is a primary
purpose of the Textus AI runtime extension. The original first-design boundary
below is superseded: Codex CLI is now required as a Textus AI `AiRunner`
provider, executed only through a CNCF managed-process capability. The
historical text remains to show the earlier boundary decision.

## Responsibility Boundary

Textus AI does not own CAR Review, Review Runs, Review rules, canonical Review
Reports, quality assessment, or release-gate policy. It provides
provider-neutral AI execution through the CNCF `AiRunner` SPI.

CBD Support owns:

- construction and redaction of bounded Review Evidence;
- semantic rule and prompt-contract selection;
- admission of AI results into `ReviewEvidenceBundle`;
- reconciliation with deterministic Evidence;
- cache, cost, profile, and gate policy; and
- the distinction among candidate Finding, Assurance, Unknown, and limitation.

Textus AI owns:

- provider, mode, engine, model, and purpose resolution;
- structured generation and provider response normalization;
- Gemma/Ollama, OpenAI, and Google Gemini runtime adapters;
- provider-neutral timeout, retry, and limit handling where it belongs at the
  AI-call boundary;
- safe AI execution observability and provenance; and
- explicit structured failure when a requested capability is unavailable.

### Superseded First-Design Boundary

The original first design excluded the Codex Skill and local Codex CLI Review
Provider from Textus AI runtime providers. It treated Codex as a coding agent
with repository tools and its own sandbox/authentication lifecycle, and placed
the Review adapter in CBD Support and the authorized CNCF process-provider
boundary. That boundary is superseded by the preceding note and is retained
only as the historical basis for the Phase 1 correction.

## Existing Capabilities

The current implementation already provides a useful baseline:

- `AiRunner.generate`, `generateRecord`, and `chat`;
- `AiRunnerRequirement` selection by provider, mode, engine, purpose, model,
  and logical tools;
- purpose profiles that avoid hard-coding concrete models in a CAR;
- structured record normalization against a requested schema;
- bounded record-generation retry;
- Gemma/Ollama, OpenAI, and Google Gemini adapters;
- request-level maximum tokens and timeout properties;
- prompt and response confidentiality policy; and
- CallTree records containing effective selection, purpose, model, request
  characteristics, outcome, and provider response metadata.

CAR Review should reuse these surfaces rather than add provider-specific public
component operations.

## Required CAR Review Purpose Profiles

CBD Support should select AI behavior through stable purposes. Candidate first
purposes are:

```text
car-review.semantic-consistency
car-review.domain-terminology
car-review.documentation-clarity
car-review.test-adequacy
car-review.finding-explanation
car-review.observation-grouping
```

The exact purpose vocabulary remains a design decision. A purpose profile must
be able to choose provider, mode, engine, model, maximum tokens, timeout,
retry, and other safe generation settings without changing CBD application
code.

No CAR Review purpose should enable `web_search` or `url_context` by default.
CBD Support supplies the accepted Evidence. External retrieval would require a
separate source, authorization, citation, and cost contract.

## Bounded Structured Generation

CAR Review needs `generateRecord` as the primary Textus AI operation. The
request will contain a prompt assembled from a versioned prompt contract and a
bounded Evidence representation, plus the expected result schema.

The runtime must preserve these properties:

- a malformed or partial provider response never becomes a successful empty
  Review result;
- record normalization cannot add facts absent from the provider response;
- missing required fields, unknown enum values, or unsupported schema features
  fail with structured diagnostics;
- retries are bounded, attributable, and visible in usage metadata;
- input and output character or byte limits are enforced independently of the
  provider's token limit; and
- provider-specific JSON or error bodies do not leak into the public Review
  contract.

CBD Support remains responsible for the semantic Review output Schema. Textus
AI is responsible for faithfully constraining or validating provider output
against the supplied record schema.

## Selection and Fallback Requirements

Provider selection must remain explicit and attributable.

- `gemma` / `local` / `ollama` is the preferred low-cost automated path.
- `openai` / `remote` and `google` / `remote` are explicitly enabled
  commercial paths.
- A missing local Ollama service or model produces a structured unavailable
  result.
- A local request must not silently fall back to OpenAI or Google unless the
  operator configured that exact fallback and CBD policy admitted it.
- A commercial provider must not silently switch provider or model after a
  refusal, quota error, or unavailable-model response.
- Effective provider, engine, mode, and model must describe what actually ran,
  not only what the caller requested.

Provider fallback and model escalation affect cost and reproducibility, so an
implicit runtime fallback is not acceptable for CAR Review.

## Provenance Requirements

`AiGenerateResponse`, `AiRecordResponse`, or their metadata need a normalized
provider-neutral provenance vocabulary sufficient for CBD Support to build an
attributable Review bundle.

The logical provenance should include, when available:

- effective provider, mode, engine, and model;
- Textus AI and provider-adapter version;
- purpose and prompt-contract identity supplied by the caller;
- request/input digest and raw-response/output digest;
- safe provider request or response identity;
- start time, completion time, and elapsed time;
- attempt and retry count;
- finish or refusal reason;
- normalization mode and output-schema identity; and
- explicit missing-provenance limitations.

The exact Scala type and metadata keys remain to be designed. Stable information
should not be left as unrelated provider-specific strings when the same concept
exists across Gemma, OpenAI, and Google.

No provenance record may contain API keys, bearer tokens, account identity,
credential locations, raw authorization headers, or secrets found in Evidence.

## Usage and Cost Requirements

Commercial cost policy belongs to CBD Support, but Textus AI must expose enough
execution facts to enforce and audit that policy.

Normalized usage should include available values for:

- input tokens;
- cached input tokens;
- output tokens;
- reasoning tokens when the provider reports them;
- total tokens;
- retry and request counts;
- local versus remote execution; and
- provider-reported quota or rate-limit state.

An unavailable measurement must be represented as unknown, not zero. Monetary
cost should not be fabricated by Textus AI when current price data or provider
billing adjustments are unavailable. CBD Support may calculate or classify
cost using a separately versioned operator policy.

The runtime also needs enforceable per-call limits for maximum output tokens,
timeout, retries, and concurrency. Future provider-neutral input-token or total
token budgets should be investigated, but provider tokenization differences
must not be hidden behind a false exact value.

## CallTree and Sensitive-Data Requirements

The existing `AiRunnerTracePolicy` and CallTree instrumentation provide the
starting point, but CAR Review requires a restrictive default.

- Raw bounded Evidence, prompts, and model responses must not enter CallTree by
  default.
- CallTree should retain safe digests, schema and prompt-contract identity,
  provider selection, counts, sizes, duration, outcome, and normalized usage.
- An explicit diagnostic mode may retain protected prompt or response artifacts
  only under a separately authorized storage and retention policy.
- Preview fields must obey the same confidentiality policy as full fields.
- Provider error messages and URLs must be sanitized before entering CallTree.
- Credentials and sensitive CAR content must not appear in logs, exceptions,
  generated reports, or provider metadata.

If the current confidentiality policy cannot express digest-only tracing, a
new trace mode or CAR Review-specific metadata discipline will be required.

## Failure, Cancellation, and Partial Result Requirements

CAR Review executes as a CNCF Job, so Textus AI calls must cooperate with its
execution lifecycle.

Required behavior includes:

- bounded timeout with a provider-specific cause retained safely;
- cancellation propagation where the HTTP or local inference driver supports
  it;
- concurrency limits for local model resources and commercial quotas;
- no unbounded retry after timeout, empty response, malformed record, rate
  limit, or provider refusal;
- distinction among unavailable provider, incompatible model, quota exhausted,
  policy refusal, timeout, cancellation, malformed output, and internal defect;
  and
- sufficient limitation metadata for CBD Support to represent the unassessed
  Review subjects as `Unknown`.

Textus AI does not decide whether these outcomes fail a Review Run or a release
gate.

## Local Gemma/Ollama Requirements

The local provider is intended for frequent low-cost tasks such as
classification, summary, terminology comparison, observation grouping, and
escalation-question generation.

The adapter needs:

- explicit model and endpoint identity;
- local availability and model-presence diagnostics;
- bounded concurrency and timeout;
- structured-record parity with remote providers for the supported Schema;
- no dependence on remote web tools; and
- quality limitations that CBD Support can retain with the result.

Local execution has no per-call API invoice but still has compute, memory,
latency, and operational cost. The runtime should describe it as local rather
than free.

## OpenAI and Google Gemini Requirements

Commercial adapters should reuse the same `generateRecord` and purpose-profile
contract as the local provider.

They need:

- normalized provider/model/request identity;
- normalized usage and rate-limit information where available;
- structured refusal and quota handling;
- explicit timeout and bounded retry;
- no automatic web tool enablement;
- credentials resolved through private CNCF configuration; and
- provider response metadata filtered through an allowlist before publication
  or CallTree recording.

CBD Support chooses when a commercial provider is worth the cost. Textus AI
only performs the admitted call and returns attributable execution facts.

## Deterministic Test Provider

CAR Review integration cannot depend on a live model for executable
specifications. Textus AI needs a deterministic test or mock `AiRunner` that
can produce:

- schema-valid structured success;
- semantic candidate Finding output;
- explicit Unknown or limitation output;
- malformed and empty output;
- timeout and cancellation;
- quota or provider-unavailable failure;
- retry then success; and
- deterministic normalized provenance and usage fixtures.

These scenarios should prove that local and commercial adapters can be replaced
without changing the CBD Review contract.

## Candidate Delivery Order

1. Define normalized provenance, usage, confidentiality, and limitation
   vocabulary for `AiRunner` responses.
2. Prove bounded `generateRecord` behavior with a deterministic test provider
   and CAR Review-shaped Schema fixture.
3. Add CAR Review purpose profiles and restrictive CallTree behavior.
4. Validate Gemma/Ollama structured generation and explicit unavailable
   behavior.
5. Normalize OpenAI and Google Gemini usage, refusal, quota, and response
   identity metadata.
6. Validate cancellation, timeout, retry, concurrency, and sensitive-data
   handling through CNCF execution boundaries.
7. Integrate the admitted Textus AI bundle into CBD Support without moving
   Review policy into Textus AI.

## Open Questions and Promotion Targets

The next design work must decide:

- whether normalized AI provenance becomes typed CNCF `AiRunner` data or a
  stable metadata namespace;
- whether input/output digests are calculated by the caller, Textus AI, or both
  and reconciled;
- how digest-only CallTree tracing is represented;
- which token and quota fields can be normalized across all providers;
- how cancellation is propagated through current synchronous `Consequence`
  boundaries;
- whether local model capability and quality limitations require a standard
  descriptor; and
- which parts belong in CNCF `AiRunner` SPI design/spec versus Textus AI
  provider design/spec.

This journal must be promoted into the appropriate CNCF/Textus AI design and
specification documents before implementation claims are made. No source,
configuration, phase ledger, publication, or runtime provider was changed as
part of this handoff.
