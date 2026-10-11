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

## R8 — Managed provider execution observations (Phase 5 connection)

Status of this addition: focused verification passed 2026-10-08 (49 tests, three suites).
It does not change the accepted earlier accounting/usage scope above.

The CNCF AI runner SPI declares `AiManagedExecutionMetadata` keys for managed
program identity, provider profile, reasoning effort and elapsed milliseconds.
After successful Codex process termination, TextusAI publishes the actual
`ProcessExecutionResult.safeProgramIdentity` and `elapsedMillis`, together with
the profile/effort selected by its admitted fixed-argument provider binding.
These facts survive generate, structured-record and chat response normalization.
Caller metadata is not the source of these observations. With no selected
provider profile/effort, those facts remain absent rather than fabricated.

Observations are not execution permission, source/provenance integrity, a new
resume gate or a certificate of unchanged content. Program/profile facts alone
do not establish independent executor context, actual model computation, cost
or Workflow continuation. Those require their real runtime connections.

E8 in `CodexRuntimeProviderSpec` consumes a controlled managed-driver result,
rejects no legacy input solely for lacking optional observations, and confirms
that forged request metadata is not returned as actual program/time. It also
covers normalization through generateRecord and chat. This fixture is not an
actual AI model invocation.

E9 extends the existing provider-owned execution example in
`ComponentFactorySpec` (E25): an ordinary Component.Factory-created TextusAI
provider runs a real controlled local executable via managed WorkAreas and
its fixed standard-work profile from a sibling caller scope. Actual fixed argv,
model/effort, program and elapsed-result observations are exercised without
giving the caller process grants. The executable echoes its input; this is
real managed process execution, not paid/provider model execution or sm-workflow
acceptance. Independent integrated Phase 5 review remains open.

## R9 — Provider control context and completed message

Status: authored 2026-10-09; compiled, tested and independently reviewed 2026-10-11.
Reconciled 2026-10-11 for standalone Textus AI Phase 9, Step P9-JL / Slice
P9-JL-01. Focused acceptance passed 42 tests and normalization/profile regressions
passed 100 tests, followed by independent Step and comprehensive Phase review.
Final full validation passed 195 tests in 25 suites; five opt-in live tests were
canceled, not claimed as executed. The version-identity repair passed 3 focused
tests and independent focused re-review. Earlier R8 verification retains its
2026-10-08 scope and does not certify this addition. Phase 9 does not require an
sm-workflow connection or acceptance.

See [Phase 9 completion evidence](../journal/2026/10/2026-10-11-phase-9-completion.md)
for the exact local acceptance/release boundary and evidence limitations.

Named Codex managed capabilities use fixed `exec --json`; the original
unprofiled plain-output capability keeps its existing wire behavior. Consume
the latest completed agent message in the latest turn from the public JSONL
control stream, not tool stdout or the whole event stream. Multiple completed
agent messages in one turn select the last one; a newly started turn discards
the previous turn's answer and completion. A previous completed turn cannot
certify an unfinished latest turn. A distinct newly started turn may recover
with its own completed answer. Later command/tool events and annotations do
not replace the answer. Successful process exit without a completed agent
turn, without an agent message, or with only whitespace does not produce
successful generation. `turn.failed` and a top-level `error` produce structured
failures without exposing private provider errors, tool bodies or discarded
answers. Unknown events and unrelated annotation/tool/error payload metadata
are not forwarded as execution facts because they are not consumed completion
data.
A `thread.started.thread_id` is optional provider-reported context, published
under CNCF `AiManagedExecutionMetadata.contextIdentity`. Semantic text and caller
metadata never supply it or managed program/profile/effort/duration facts.
Missing, empty, control-character or conflicting control context stays absent;
a useful completed response is not rejected only for missing optional facts.
This value does not grant authority, attest model computation or by itself prove
placement, independence, inline reuse or Workflow completion. Any application's
origin/executor/continuation requirements belong to that application's actual
runtime integration and are outside this standalone Phase.

The paired `CodexRuntimeProviderSpec` JSONL group uses the normal named-profile
AiRunner path and controlled driver fixtures:

- E10: generate, generateRecord and chat return completed text/control context,
  reject caller/semantic-text forgery as execution facts, and omit unrelated
  annotation/tool/error metadata.
- E11: missing/conflicting context remains absent; failed/unfinished streams
  fail without exposing private detail.
- E12: the latest completed answer wins within one turn and across successive
  completed turns; late non-answer events cannot replace it.
- E13: a prior completed answer cannot certify the latest unfinished turn;
  a distinct newly started completed turn recovers with its own answer.
- E14: a top-level terminal error fails without exposing private provider detail.
- E15: completed message-less and whitespace-only turns fail.
- E16: empty/control-character optional context is omitted while a useful
  completed answer succeeds.

ComponentFactorySpec E25 retains R8 E9 provenance and pairs with R9: its real
controlled executable emits JSONL for generate, generateRecord and chat through
the provider-owned scope. It exercises fixed model/effort arguments, bounded
schema, empty environment and actual execution observations while the sibling
caller has no process grants. These fixtures do not certify actual AI model
computation or sm-workflow acceptance and introduce no increased AI sandbox
permissions, process grants, cache scope or caller-owned process access.

Protocol source: [OpenAI non-interactive mode](https://developers.openai.com/codex/noninteractive/).
