# AI Input-Budget Admission Specification

status=accepted
scope=textus-ai phase-3 EA-02
updated_at=2026-07-18

## Semantic Expectations

- A positive execution-class `max-input-tokens` limit applies before a provider
  service is resolved or invoked.
- The same configured class limit applies to every purpose resolved through
  that class.
- An application purpose may narrow, but cannot broaden, its standard purpose's
  input limit.
- Generate and record estimates are the UTF-8 byte length of the prompt.
- Chat estimates include the normalized role/content text and sixteen envelope
  units per message.
- A request whose estimate exceeds its configured limit fails structurally and
  records no provider call or fallback.
- A provider-reported input count replaces an admission estimate in the
  response. Otherwise the response exposes the estimate as `estimated` and
  records `input_token_estimated`.
- Invalid policy values fail configuration resolution and do not invoke a
  provider.

## Executable Evidence

`AiInputTokenEstimatorSpec` verifies deterministic generate, record, and chat
estimates. `TextusAiRunnerSpec` verifies class-policy resolution, successful
estimated usage publication, pre-provider rejection for all operations, and
the no-fallback boundary.
