# AI Input-Budget Admission

status=accepted
scope=textus-ai phase-3 EA-02
updated_at=2026-07-18

## Decision

Textus AI admits a request before provider or external-tool invocation when the
effective execution class configures `max-input-tokens`. The policy key is:

```text
textus.ai.execution-classes.<execution-class>.max-input-tokens
```

An application-purpose registration or tuning under
`textus.ai.application-purposes.<purpose>` may set `max-input-tokens` only to narrow the resolved
execution-class limit. A missing limit means no input-budget admission is
applied.

## Estimate Contract

The admission estimate is `utf8-byte-upper-bound-v1`:

- `generate` and `generateRecord`: UTF-8 byte length of the supplied prompt.
- `chat`: UTF-8 byte length of the normalized `role: content` messages plus
  sixteen envelope units for each message.

The estimate is intentionally provider-neutral. Every tokenizer must consume
at least one byte for every non-empty byte token, so the byte count is a
conservative upper bound for the caller-supplied text payload. The fixed chat
envelope charges Textus's normalized role framing. It does not claim to count
provider-internal instructions, tool schemas, cached context, or billing
adjustments.

The accepted error boundary is therefore explicit: the estimate is exact for
the admission charge defined above, but it is not a provider-reported usage or
a price input. Providers may report a different input count after execution;
that reported count wins for the normalized response. Cost admission remains
EA-03 work and must not treat this estimate as price data.

## Admission and Failure

Textus resolves purpose and runtime execution class, calculates the estimate, and
checks the effective policy before resolving a `GenerateService` or
`ChatService`. If `estimate > max-input-tokens`, it returns a structured
`operationIllegal` result identifying only the configured limit and estimate.
It does not invoke a provider, tool, fallback model, or alternate purpose.

Invalid zero, negative, or non-numeric policy values are configuration failures
before execution. Execution-class values are validated even when a purpose
supplies a narrower value, so a malformed operator class policy cannot be
hidden by an application override. Application purposes cannot broaden the
standard purpose's input budget.

## Observability

Successful responses record:

- `ai.policy.max_input_tokens` when an effective limit exists;
- `ai.policy.input_budget_basis=utf8-byte-upper-bound-v1` when admission is
  configured;
- `ai.usage.input_tokens` with source `estimated` only when a selected provider
  did not report input usage; and
- `input_token_estimated` in `ai.limitation.codes` when that estimate remains
  the response's input usage value.

The response and CallTree expose only the numeric estimate and effective policy
facts. They never expose prompt text, message content, tokenizer input, tool
schema, credentials, or provider request bodies.

## Non-Goals

- No provider tokenizer is invoked before execution.
- No estimate is represented as measured provider usage.
- No input or total cost is calculated.
- No provider, model, tool, or application fallback is selected after a budget
  rejection.
