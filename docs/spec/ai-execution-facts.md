# AI Execution Facts Specification

status=accepted
scope=textus-ai phase-3 EA-01
updated_at=2026-07-18

## Semantic Expectations

- A provider-reported non-negative usage value is published with source
  `reported`.
- An explicit Textus estimate is published with source `estimated` only when a
  reported value for that field is absent.
- Cached-input and reasoning values are independent optional usage fields.
  Their absence does not imply zero.
- A malformed or negative provider usage value is absent and cannot replace an
  explicit valid estimate.
- If no usage field is known, `ai.limitation.codes` contains
  `usage_unavailable`.
- Effective policy facts have an opaque deterministic
  `ai.accounting.policy_snapshot_id`; the identity changes only when an input
  policy fact changes.
- A rate schedule identity and provider request identity are optional. Their
  absence is not synthesized from a response identifier.
- A response identifier is exposed only as `ai.execution.response_id`.
- CallTree metadata contains only normalized namespaces. Raw provider payloads,
  prompts, responses, credentials, accounts, and monetary amounts are never
  copied into execution facts.

## Executable Evidence

`AiExecutionFactsSpec` covers provider-reported, estimated, unavailable, and
accounting identity fixtures. `TextusAiRunnerSpec` covers extraction from plain
Google, OpenAI, and Gemma provider responses and response-to-CallTree
normalization.
