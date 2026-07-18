# AI Runner Execution Facts

status=accepted
scope=textus-ai provider-neutral metadata profile
updated_at=2026-07-18

## Decision

Textus AI uses a stable, provider-neutral metadata namespace for AI execution
facts in Phase 3. The existing `AiGenerateResponse`, `AiRecordResponse`, and
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

All normalized facts use the `ai.execution.*`, `ai.policy.*`, `ai.usage.*`,
`ai.accounting.*`, or `ai.limitation.*` namespaces.

### Execution Facts

| Key | Meaning | Absence |
| --- | --- | --- |
| `ai.execution.provider` | Effective provider that executed the request | Unknown or unavailable |
| `ai.execution.mode` | Effective execution mode, such as `local` or `remote` | Not resolved |
| `ai.execution.engine` | Effective provider engine or runtime | Not resolved |
| `ai.execution.model` | Effective model identifier | Provider did not report it |
| `ai.execution.purpose` | Effective caller purpose | No purpose was supplied |
| `ai.execution.execution_class` | Effective logical execution class | No execution class was selected |
| `ai.execution.location` | `local` or `remote` execution location | Cannot classify safely |
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
| `ai.execution.enabled_tools` | Logical tools admitted by the selected runtime capability | No capability-specific tool policy applied |

`ai.execution.provider`, `mode`, `engine`, `model`, and `purpose` describe
effective values. A requested value must not overwrite the value that actually
executed.

### Policy Facts

| Key | Meaning | Absence |
| --- | --- | --- |
| `ai.policy.model_profile` | Approved Codex model-profile selected by the effective purpose | No Codex purpose profile was selected |
| `ai.policy.reasoning_level` | Fixed Codex reasoning level compiled into the admitted capability | The profile did not configure reasoning |
| `ai.policy.generic_purpose` | Generic purpose selected directly or inherited by an application purpose | No generic purpose policy applied |
| `ai.policy.logical_level` | Logical level that selected the approved model-profile | No logical level policy applied |

For Codex, these facts identify the profile policy rather than a caller-supplied
CLI argument. `ai.execution.enabled_tools` records only the logical capability
set, not URLs, search results, or provider transcripts.

Purpose and execution-class policy are resolved before provider selection. A
generic purpose can supply a configured execution-class default; an
application purpose may name it with `base-purpose` only when its effective
provider/model/reasoning policy is identical and its tools and execution bounds
are no broader. The normalized execution class reflects the effective approved
selector, not a provider or model identifier.

### Usage Facts

| Value key | Source key | Meaning |
| --- | --- | --- |
| `ai.usage.input_tokens` | `ai.usage.input_tokens_source` | Input tokens |
| `ai.usage.cached_input_tokens` | `ai.usage.cached_input_tokens_source` | Cached portion of input tokens |
| `ai.usage.output_tokens` | `ai.usage.output_tokens_source` | Output tokens |
| `ai.usage.reasoning_tokens` | `ai.usage.reasoning_tokens_source` | Reasoning-token portion reported by a provider |
| `ai.usage.total_tokens` | `ai.usage.total_tokens_source` | Total tokens |
| `ai.usage.input_payload_bytes` | N/A | UTF-8 byte size charged by a configured input admission estimate |
| `ai.usage.input_envelope_tokens` | N/A | Fixed message-framing units charged by a configured input admission estimate |

Usage values are decimal non-negative integers. A source is either `reported`
when the selected provider supplied the value, or `estimated` when a later
Textus admission policy explicitly supplied it. A provider-reported value wins
over an estimate for the same field. A value and its source are omitted
together when unavailable; they are never represented as `0`, `null`, or a
guess. `0` is valid only when a provider or estimator explicitly reports zero.

Textus AI does not emit monetary cost because price and billing adjustments are
operator policy, not provider-neutral execution facts.

### Accounting Facts

| Key | Meaning | Absence |
| --- | --- | --- |
| `ai.accounting.policy_snapshot_id` | SHA-256 identity of the effective Textus policy facts | No effective policy facts |
| `ai.accounting.rate_schedule_id` | Operator-owned pricing schedule identity | Pricing is not configured or not applied |
| `ai.accounting.provider_request_id` | Safe provider request identifier | Adapter did not expose one safely |

`policy_snapshot_id` is an opaque identity, not a serialized configuration.
`rate_schedule_id` never contains a provider account, price, currency amount,
or billing adjustment. A provider response identifier remains
`ai.execution.response_id`; it must not be relabelled as a request identifier.

### Limitations

`ai.limitation.codes` contains sorted, comma-separated stable limitation
identifiers. It is absent when no limitation applies. Initial identifiers are:

- `input_digest_unavailable`
- `output_digest_unavailable`
- `usage_unavailable`
- `rate_schedule_unavailable`
- `input_token_estimated`
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

Response metadata may contain normalized execution, policy, usage, limitation,
and opaque accounting identities, plus explicitly allowlisted provider-specific
facts. Ordinary CallTree records may additionally contain the same normalized
facts and bounded counts or sizes. A configured rate schedule identity is
CallTree-only when rate accounting is introduced in EA-03; it is not emitted by
EA-01.

Neither response metadata nor CallTree records may contain raw evidence, prompt
text, response text, authorization headers, credentials, account identity,
price or currency amount, provider error bodies, provider-specific metadata, or
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
`ai.limitation.*`, or `ai.policy.*` value.
- A provider adapter may supply a raw provider-specific fact, but only Textus
  AI maps it to a normalized key.
- Values are omitted when unknown rather than synthesized. When no usage field
  is available, `usage_unavailable` records that accounting cannot treat the
  absence as zero. When no rate schedule is applied,
  `rate_schedule_unavailable` records that no price can be derived.
- Comma-separated collections are lower-case, de-duplicated, and sorted.

## Current Implementation

Textus AI currently normalizes effective provider, mode, engine, reported
model, purpose, local/remote location, logical tools, response identity, finish
reason, reported input/cached-input/output/reasoning/total tokens, and record
normalization mode for `generate`, `chat`, and `generateRecord` responses.
Google/Gemini and OpenAI extract cached-input and reasoning values only when
their wire response contains them. Gemma/Ollama exposes only the counts it
reports. Textus AI maps the selected provider's provider-specific facts into
the normalized namespace; it does not infer absent values.

An approved Codex purpose model-profile is compiled into a finite managed
process capability. The normalized policy facts expose its profile and fixed
reasoning level, while `ai.execution.enabled_tools` exposes its admitted
logical tools. Caller fields never select model, reasoning, or CLI arguments.

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

- Add provider request identity extraction where a binding can expose a safe
  request header or equivalent wire field.
- Add policy-owned estimates in EA-02 and rate schedule identity/application in
  EA-03.
- Move cancellation propagation and concurrency enforcement to CNCF Job
  execution when the SPI gains those lifecycle controls.
- Evaluate promotion of proven fields into typed CNCF `AiRunner` response data.
