# AI Runner Execution Facts

status=accepted
scope=textus-ai provider-neutral metadata profile
updated_at=2026-07-16

## Decision

Textus AI uses a stable, provider-neutral metadata namespace for AI execution
facts in Phase 1. The existing `AiGenerateResponse`, `AiRecordResponse`, and
`AiChatResponse` metadata maps remain the transport surface.

Typed CNCF `AiRunner` response fields are deferred. A typed representation is
appropriate only after the namespace has been exercised by Gemma/Ollama,
OpenAI, and Gemini and the meaning of missing or provider-specific values is
proven stable.

This decision preserves the CNCF SPI binary and source contract while avoiding
an incomplete typed model that would imply false equivalence across providers.

## Ownership

CNCF owns the `AiRunner` SPI and its generic response metadata capability.
Textus AI owns this provider-neutral metadata profile and normalizes provider
adapter results into it. Provider adapters may retain provider-specific facts
under their own namespaces, but those facts are not part of this contract.

The profile is a Textus AI design contract. CNCF may later promote validated
facts into typed SPI fields without changing the meaning of the namespace keys.

## Namespace

All normalized facts use the `ai.execution.*`, `ai.usage.*`, or
`ai.limitation.*` namespaces.

### Execution Facts

| Key | Meaning | Absence |
| --- | --- | --- |
| `ai.execution.provider` | Effective provider that executed the request | Unknown or unavailable |
| `ai.execution.mode` | Effective execution mode, such as `local` or `remote` | Not resolved |
| `ai.execution.engine` | Effective provider engine or runtime | Not resolved |
| `ai.execution.model` | Effective model identifier | Provider did not report it |
| `ai.execution.purpose` | Effective caller purpose | No purpose was supplied |
| `ai.execution.location` | `local` or `remote` execution location | Cannot classify safely |
| `ai.execution.request_id` | Safe provider request identity | Provider did not expose one safely |
| `ai.execution.response_id` | Safe provider response identity | Provider did not expose one safely |
| `ai.execution.started_at` | ISO-8601 request start instant | Runtime did not measure it |
| `ai.execution.completed_at` | ISO-8601 completion instant | Runtime did not measure it |
| `ai.execution.elapsed_ms` | Non-negative elapsed milliseconds | Runtime did not measure it |
| `ai.execution.attempt` | One-based successful or final attempt number | Runtime did not retry or report it |
| `ai.execution.retry_count` | Number of retries performed | Runtime did not retry or report it |
| `ai.execution.finish_reason` | Normalized finish, refusal, or stop reason | Provider did not report one |
| `ai.execution.normalization_mode` | Textus response-normalization mode | No normalization occurred |
| `ai.execution.schema_id` | Caller-supplied structured-output schema identity | No structured schema identity was supplied |
| `ai.execution.input_digest` | Safe digest of bounded input, formatted `sha256:<hex>` | Digest was not supplied or measured |
| `ai.execution.output_digest` | Safe digest of bounded output, formatted `sha256:<hex>` | Digest was not supplied or measured |
| `ai.execution.tools` | Effective logical tools as sorted comma-separated IDs | No tools were enabled |

`ai.execution.provider`, `mode`, `engine`, `model`, and `purpose` describe
effective values. A requested value must not overwrite the value that actually
executed.

### Usage Facts

| Key | Meaning |
| --- | --- |
| `ai.usage.input_tokens` | Provider-reported input tokens |
| `ai.usage.cached_input_tokens` | Provider-reported cached input tokens |
| `ai.usage.output_tokens` | Provider-reported output tokens |
| `ai.usage.reasoning_tokens` | Provider-reported reasoning tokens |
| `ai.usage.total_tokens` | Provider-reported total tokens |
| `ai.usage.request_count` | Number of provider requests made |

Usage values are decimal non-negative integers. An unavailable value is omitted;
it is never represented as `0`, `null`, or a guessed estimate. Textus AI does
not emit monetary cost because price and billing adjustments are application
policy, not provider-neutral execution facts.

### Limitations

`ai.limitation.codes` contains sorted, comma-separated stable limitation
identifiers. It is absent when no limitation applies. Initial identifiers are:

- `input_digest_unavailable`
- `output_digest_unavailable`
- `usage_unavailable`
- `provider_identity_unavailable`
- `cancellation_not_propagated`
- `concurrency_not_enforced`
- `output_limit_not_verified`

The value identifies a missing measurement or execution boundary. It must not
contain provider error bodies, credentials, account data, URLs, prompt text, or
response text.

Current Textus AI runner responses always record
`cancellation_not_propagated`: the synchronous provider binding does not yet
propagate a caller cancellation signal. `concurrency_not_enforced` is omitted
when a purpose configures and acquires CNCF scoped concurrency admission.
`output_limit_not_verified` is added when an effective output-token limit is
sent to a provider that omits its output-token measurement. Record generation
retries only empty responses and explicit timeout-like failures, at most three
times, using `ai.record.retry-limit` (default `1`).

## Confidentiality Boundary

Response metadata may contain only normalized execution facts and explicitly
allowlisted provider-specific facts. Ordinary CallTree records may contain only
normalized facts and bounded counts or sizes. They must not contain raw
evidence, prompt text, response text, authorization headers, credentials,
account identity, provider error bodies, provider-specific metadata, or
arbitrary request metadata.

`input_digest` and `output_digest` are permitted only when the digest is
calculated over the bounded payload and uses the declared `sha256:<hex>` form.
The digest does not authorize retention of the original payload.

Provider-specific facts remain under namespaces such as `openai.*`, `google.*`,
or `gemma.*`. They must be explicitly mapped into this profile before an
application may depend on their provider-neutral meaning.

## Precedence and Validation

- Textus AI writes normalized effective values after adapter execution.
- Caller metadata cannot overwrite an `ai.execution.*`, `ai.usage.*`, or
  `ai.limitation.*` value.
- A provider adapter may supply a raw provider-specific fact, but only Textus
  AI maps it to a normalized key.
- Values are omitted when unknown rather than synthesized.
- Comma-separated collections are lower-case, de-duplicated, and sorted.

## Current Implementation

Textus AI currently normalizes effective provider, mode, engine, reported
model, purpose, local/remote location, logical tools, response identity, finish
reason, input/output/total tokens, and record normalization mode for
`generate`, `chat`, and `generateRecord` responses. Google/Gemini, OpenAI, and
Gemma/Ollama adapters extract only wire fields they actually received. Textus
AI maps the selected provider's provider-specific facts into the normalized
namespace; it does not infer absent values.

Reserved normalized namespaces cannot be overwritten by provider metadata,
while only explicitly allowlisted provider-specific metadata remains available
under its own namespace. CallTree capture is digest-only for input and output:
it records SHA-256 digests and character counts, never payload or previews.
Gemma/Ollama total tokens are emitted only when both provider-reported input and
output token counts exist, in which case the total is their exact sum.
HTTP failures are classified without publishing the provider body as
`invalid_request`, `authentication_failed`, `model_unavailable`, `timeout`,
`quota_exhausted`, `rate_limited`, `unavailable`, or `provider_rejected`.
Gemma/Ollama preserves that terminal category. It retries an endpoint only when
the resolved `GemmaRuntimeConfig` explicitly declares `fallbackEndpoint`; it
does not select another provider or model implicitly.

## Deferred Work

- Normalize request identity and timing.
- Move cancellation propagation and concurrency enforcement to CNCF Job
  execution when the SPI gains those lifecycle controls.
- Evaluate promotion of proven fields into typed CNCF `AiRunner` response data.
