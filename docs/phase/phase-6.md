# Phase 6 - Gemma-First Strategy and Tool Orchestration

status=active
planned_at=2026-07-21
started_at=2026-07-21
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Execute bounded Gemma-first profiles and allow selected Textus AI runtime
profiles to use operator-owned internal Operation and remote MCP tool catalogs
through separate CNCF input Ports and one Textus AI-owned provider catalog via
the existing
`generate` and `chat` operations. An application
continues to select only its registered application purpose. Textus AI resolves
the standard purpose, execution class, runtime profile, and admitted MCP server
set before it invokes a provider.

## Scope

- Resolve explicit Gemma-first profiles without changing the application
  purpose-only request contract.
- Execute structured, tool-grounded, decomposed, validator-repair, and
  candidate-ranking strategies with bounded repairs and provider attempts.
- Call an operator-configured application acceptance Operation between attempts
  and admit commercial escalation only for reviewed residual failure classes.
- Publish safe strategy and attempt evidence without raw application/provider
  payloads.
- Account for each provider attempt with its own operator rate schedule; a
  commercial fallback is not admitted under the primary Gemma schedule.
- Consume the CNCF Phase 45 MCP client Port and Phase 46 internal Operation
  tool Port as separate admitted sources.
- Bind MCP server-set selection to Textus AI runtime profile and execution
  class configuration only.
- Convert admitted internal and remote tools into provider-neutral function definitions and
  execute bounded function-call continuation loops.
- Support Gemma/Ollama, OpenAI, Google Gemini, and Anthropic Messages through
  their respective provider bindings while retaining current no-tool and
  built-in web-tool behavior.
- Keep Codex CLI and Claude Code CLI as separate runtime-owned managed-process
  bindings. They may use only their fixed profile capability; they do not
  receive the Textus AI function catalog or caller-provided CLI/MCP options.
- Record application purpose, standard purpose, runtime profile, execution
  class, selected server-set identity, tool names, bounded result summaries,
  and structured failures without exposing endpoint, credential, prompt,
  argument, or raw tool-result payloads.

## Boundaries

- Application callers cannot choose an MCP server, endpoint, transport,
  credential/header, tool, or provider-native MCP option.
- `AiRunnerRequirement` remains the public application contract. Textus AI
  passes only its resolved internal MCP execution plan to provider bindings.
- CNCF owns MCP transport, endpoint admission, credential references, tool
  catalog discovery, policy limits, and transport diagnostics. Textus AI owns
  provider request construction and function-call continuation semantics.
- The initial transport is CNCF-admitted Streamable HTTP only. Stdio, SSE,
  arbitrary process execution, and arbitrary HTTP are out of scope.
- A Gemma/Ollama execution class that selects an MCP or Operation tool set must
  name a member of the runtime-owned `textus.ai.ollama.tool-capable-models`
  catalog. `functiongemma` is the shipped default; an operator may replace the
  catalog with locally tested alternatives. A plain no-tool request retains the
  existing generate/chat path.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| OS-01 | Profile-owned strategy | Explicit Gemma-first profiles resolve all five strategy kinds and bounded attempts. | done |
| OS-02 | Runner execution | Generate executes Gemma first, bounded repair, and classified commercial escalation. | done |
| OS-03 | Acceptance integration | A configured application Operation controls candidate decisions without provider selection. | done |
| OS-04 | Cross-component evidence | Sanpomap deterministic and guarded production evidence verifies the strategy end to end. | active |
| MO-01 | Profile-owned MCP policy | Runtime profiles and execution classes resolve an admitted MCP server set without caller configuration. | done |
| MO-02 | Common orchestration | One bounded, redacted tool catalog and function-call execution path bridges Textus AI to the CNCF MCP client Port. | done |
| MO-03 | Local provider binding | Gemma/Ollama emits and receives tool calls through `/api/chat`; no-tool generation remains unchanged. | done |
| MO-04 | Commercial provider bindings | OpenAI Responses, Gemini Interactions, and Anthropic Messages execute the same admitted MCP tools through their native function continuation formats. | done |
| MO-05 | Executable evidence and closure | Deterministic fake MCP/provider evidence verifies policy, safety, and provider regressions; live tests remain opt-in heavy tests. | done |

## Implementation Status

OS-01 through OS-03 are complete. Sanpomap deterministic replay covers all
registered application purposes. The first assembled guarded record exercises
a classified Gemma availability failure, one Codex CLI fallback, and the
Sanpomap acceptance operation before final Scenario DSL adoption. The accepted
record is maintained by Sanpomap at
`docs/evidence/phase-2-assembled-live-evidence.yaml`. The accepted Textus AI
release revision is `72ed97e`. OS-04 remains active until an assembled local
Gemma repair is recorded.

On 2026-07-22, the bounded local acceptance path was remeasured with the
generic `gemma3:4b` model. The assembled Sanpomap command completed without a
Scenario DSL artifact. Direct, isolated Ollama probes for both `gemma3:4b` and
the shipped `gemma:2b` model, including a `num_ctx=1024` `gemma:2b` probe,
returned no bytes within the same 360-second envelope. This is not evidence of
a provider-contract regression: the Textus AI live heavy test continues to
verify bounded no-tool generation and native function calling. It is evidence
that this host has not yet demonstrated a local-Gemma Scenario DSL repair.

MO-01 is complete. An execution class may select one logical
`operation-tool-set`, `mcp-server-set`, or both; the selected runtime profile
publishes distinct logical requirements through CNCF `OperationToolSocket` and
`McpClientSocket` input Ports. Application-purpose configuration cannot select
Operation or MCP connectivity, and the component sees neither runtime policy,
Codex MCP source configuration, endpoint, transport, nor credential data.
Provider-neutral catalog adaptation and function-call continuation begin in
MO-02.

MO-02 and MO-03 are complete. `ToolOrchestrator` projects catalogs admitted by
the assembled CNCF `OperationToolSocket` and `McpClientSocket` into
deterministic runtime function identifiers, validates returned arguments
against the original typed schema, and invokes each source only through its
own CNCF scoped invocation. The loop is
bounded by turn, call, catalog, projected-definition, argument, result, and
elapsed-time limits. Gemma/Ollama implements the common `ToolCallingChatService`
through `/api/chat`; ordinary generate/chat requests do not use that protocol.
The admission estimate covers the maximum continuation envelope, and reported
Gemma usage is aggregated across turns. Anthropic Messages uses the same
function catalog through `tool_use` / `tool_result` continuation. OpenAI
Responses uses `function_call` / `function_call_output` continuation and keeps
the logical web-search mapping alongside Textus-owned function definitions.
Google Gemini Interactions uses `function_call` / `function_result` continuation
with its runtime-private `previous_interaction_id`; only the latest tool results
are sent to each resumed interaction. Existing logical URL/search mappings and
no-tool paths remain verified. Codex CLI and Claude
Code CLI remain fixed managed-process providers rather than participants in
this runtime-owned function loop.

MO-04 is complete. MO-05 confirms named-server-set admission: profile and
execution-class policy publish the logical requirement, application-purpose
MCP selection fails before Port activation, and the installed catalog excludes
transport-reported tools outside the admitted set. `CommercialToolOrchestrationSpec` exercises each
commercial binding against the common fake MCP boundary: OpenAI Responses,
Gemini Interactions, and Anthropic Messages continue only with native
correlation after an admitted result and stop before another provider request
when the MCP invocation fails.

The deterministic regression baseline is complete: `TextusAiRunnerSpec`
verifies plain generate/chat, structured-record normalization and failure
handling, and redacted provider failures. `AiProviderAdmissionSpec` verifies
logical URL/search admission and rejection; the Google and OpenAI runner
specifications verify their corresponding provider mappings. On 2026-07-22,
the opt-in `TEXTUS_AI_LIVE_GEMMA_TEST=true sbt --batch 'testOnly
org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'` heavy test passed both
managed local `gemma:2b` generation and a `functiongemma` native function-call
request. The test completed in 7 minutes 37 seconds without retaining a
container. The live result contains no prompt, tool argument, or tool-result
payload in phase documentation. CNCF Streamable HTTP MCP transport remains
covered by the deterministic assembled MCP fixture; live remote-MCP execution
is intentionally not a Phase 6 closure requirement.

## Dependencies

- CNCF Phase 45 must define and implement the MCP client Port, transport
  ExtensionPoint, server-set admission, bounded calls, and redacted diagnostics.
- Phase 5 is complete and supplies the profile-owned local service provisioning
  contract used by this phase.

## References

- CNCF Phase 45: `cloud-native-component-framework/docs/phase/phase-45.md`
- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [Gemma-First Operational Profile](../spec/gemma-first-operational-profile.md)
- [Gemma Integration Design Note](../notes/gemma-integration-design-note.md)
- [Phase 5 Dashboard](phase-5.md)
