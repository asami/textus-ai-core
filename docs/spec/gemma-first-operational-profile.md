# Gemma-First Operational Profile

status=active
version=2026-07-22

## Contract

An operator selects a defaults bundle with `textus.ai.profile`. An application
request selects only its registered `AiRunnerRequirement.purpose`. The profile
resolves the execution class, Gemma primary execution, optional commercial
execution, attempt bounds, and safe evidence fields.

`operational-strategy` is not a provider profile. It identifies the bounded
algorithm used inside the selected runtime profile:

- `structured`
- `tool-grounded`
- `prompt-grounded`
- `decomposed`
- `validator-repair`
- `candidate-ranking`

The shipped `gemma-first-gemini` and `gemma-first-codex-cli` profiles use these
strategies only for standard purposes in a Gemma work class. The three thinking
classes select their named commercial execution directly and do not acquire a
Gemma attempt or an operational strategy implicitly. Existing profiles retain
their single-provider, no-fallback behavior.

## Operator Configuration

```yaml
textus:
  ai:
    profile: gemma-first-codex-cli
    application-purposes:
      sanpomap-scenario-research:
        operational-strategy: prompt-grounded
      sanpomap-location-investigation:
        operational-strategy: tool-grounded
        acceptance-operation: Sanpomap.Evaluation.evaluateAiCandidate
    execution-classes:
      standard-work:
        strategy-max-repairs: 1
        strategy-max-provider-attempts: 2
        rate-schedule: gemma-standard
        fallback-rate-schedule: codex-standard
        operation-tool-set: builtin-tools
        mcp-server-set: admitted-research
```

Application-purpose configuration may refine the high-level strategy and name
an application acceptance Operation. It cannot select a provider, model,
engine, endpoint, server set, credential, or concrete tool. Execution-class
configuration remains operator-owned and may override profile defaults.

When an execution class enables cost accounting with `rate-schedule`, a
two-provider strategy must also define `fallback-rate-schedule`. Admission and
final accounting are recalculated for the provider used by every attempt; a
Gemma rate schedule is never reused for a commercial fallback.

## Attempt Rules

- Gemma is the first provider only when the selected execution class is
  `simple-work` or `standard-work`. `simple-thinking`, `advanced-thinking`,
  and `deep-thinking` select the named commercial execution directly.
- Repairs are limited to `0..3`; provider attempts are limited to `1..2`.
- A configured acceptance Operation returns `accept`, `repair`, `confirm`,
  `escalate`, or `reject` through a Record response.
- `repair` may invoke Gemma again only while the repair bound remains.
- Commercial escalation is admitted only for availability, timeout, malformed
  output, domain validation, evidence, or ambiguity outcomes.
- Authorization, capability, admission, credential-policy, input, and resource
  limit outcomes are terminal and never select the commercial execution.
- Strategies other than `prompt-grounded` require a Gemma-primary work
  execution. `prompt-grounded` may use any generate-capable runtime profile
  because CNCF tool execution remains outside the provider protocol.
- A thinking-class Gemma-first strategy configuration is rejected rather than
  causing an implicit Gemma attempt.
- A conventional profile never gains fallback behavior implicitly.

## Evidence

Successful strategy responses expose only bounded facts: strategy, attempt
lineage, repair count, escalation reason, final provider, duration, and the
provider-reported usage facts already admitted by the execution-facts contract.
Attempt lineage contains only ordinal, provider identity, stage, and outcome.
Prompt text, candidate text, validation payloads, credentials, endpoints, and
raw provider or tool payloads are prohibited from response metadata and
CallTree attributes.

## Tool-Grounded Execution

`tool-grounded` is available only when the resolved runtime execution names an
operator-owned `operation-tool-set`, `mcp-server-set`, or both. The application
request and application-purpose registration cannot select an Operation,
endpoint, transport, credential, server, or individual tool. Textus AI obtains
the separately admitted sources only through the assembled CNCF
`OperationToolSocket` and `McpClientSocket`, and invokes each selected tool only
through its owning CNCF invocation scope.

Textus AI exposes admitted tools under deterministic runtime-owned
`operation_<sha256-prefix>` and `mcp_<sha256-prefix>` function names. It never
collapses their source identities, exposes provider-native MCP configuration,
or accepts a caller-supplied function name.
Tool results are returned to the continuation as bounded text. Safe execution
facts distinguish total tool calls and turns from source-specific MCP and
Operation call counts, and may include only logical source identity and catalog
digests. They never include arguments, results, endpoints, or provider payloads.

The common loop is deliberately bounded: four model turns, eight total tool
calls, 32 catalog tools, 4 KiB per projected function definition, 16 KiB per
tool argument payload, 16 KiB per tool result, and 120 seconds elapsed time.
Exhausting any bound is a terminal resource-limit outcome, not a reason to use
a commercial fallback. Cost admission uses the full worst-case continuation
envelope: every possible model turn, catalog projection, bounded tool results,
and accumulated assistant continuation output. Reported provider usage is
aggregated across completed turns for final accounting.

## Prompt-Grounded Execution

`prompt-grounded` is the provider-neutral alternative to native function
calling. It accepts the same operator-owned `operation-tool-set` and
`mcp-server-set` identities, but uses only the selected provider's plain
generation service. The first generation returns one bounded list of factual
questions together with admitted catalog routing and typed arguments. Textus
AI validates the plan, invokes each admitted CNCF tool outside the provider,
and sends the resulting evidence to a second plain generation call.

Provider-native tools are removed from both prompt-loop calls so one request
cannot mix native Web execution with CNCF evidence execution. The model never
receives an endpoint, credential, transport configuration, or unadmitted tool.
The final response records only call counts, source counts, catalog digest,
and the usual normalized execution facts.

Unlike native `tool-grounded` execution, a Gemma prompt loop does not require
an Ollama model with function-calling support. A normal model such as
`gemma3:12b` is valid because it emits strict research-plan JSON and final
semantic output rather than Ollama tool calls. The same loop is valid for
OpenAI, Gemini, and Anthropic API profiles when CNCF tools are needed. Their
provider-native Web tools remain a separate profile capability.

## Acceptance Operation Shape

Textus AI calls the configured operation through the assembled subsystem with
`purpose`, `candidate`, `candidateFormat`, `repairCount`, and `maxRepairs`.
The operation returns `decision`, canonical bounded `diagnostics`,
`allowedRepairPaths`, and optional `escalationReason`. Textus AI does not
interpret application geography or domain rules itself.
