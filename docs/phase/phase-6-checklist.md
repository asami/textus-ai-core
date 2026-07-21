# Phase 6 Checklist - Provider-Neutral MCP Tool Orchestration

status=active
phase=[Phase 6 - Provider-Neutral MCP Tool Orchestration](phase-6.md)

## Stage MO-01 - Profile-Owned MCP Policy

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after MCP server-set selection is resolved from
  runtime profile/execution class and no caller-facing configuration can select
  a server, endpoint, credential, or tool.

- [x] Define the runtime-profile and execution-class MCP server-set policy.
- [x] Resolve only the selected policy through a registered application purpose.
- [x] Reject caller and application-purpose configuration attempts to select
  MCP connectivity or a concrete tool.

## Stage MO-02 - Common Orchestration

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after every provider binding receives the same
  admitted catalog and all tool calls pass through CNCF limits and diagnostics.

- [ ] Adapt the CNCF MCP client catalog to provider-neutral function
  definitions with stable tool identities.
- [ ] Define bounded turns, calls, elapsed time, argument size, and result-size
  behavior with structured failure propagation.
- [ ] Record redacted MCP server-set/tool/summary facts in CallTree and response
  metadata without endpoint, credential, prompt, argument, or raw-result data.

## Stage MO-03 - Local Provider Binding

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after Gemma/Ollama tool-call continuations are
  deterministically verified and no-tool generate/chat behavior is unchanged.

- [ ] Emit admitted function definitions through the Ollama chat protocol.
- [ ] Execute returned tool calls through the common bounded orchestration path
  and return tool results in the required follow-up messages.
- [ ] Reject a selected tool workflow when the resolved local model/provider
  cannot support the required function protocol.

## Stage MO-04 - Commercial Provider Bindings

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after OpenAI and Gemini continue the common MCP
  tool loop through their documented native function formats.

- [ ] Implement OpenAI Responses function-call output continuation.
- [ ] Implement Gemini Interactions function-result continuation.
- [ ] Preserve existing logical URL/search tool mapping and no-tool behavior.

## Stage MO-05 - Executable Evidence And Closure

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Close only after fake MCP and provider evidence covers every
  admitted and rejected path; live provider/MCP verification remains explicitly
  optional heavy-test evidence.

- [ ] Verify profile and application-purpose resolution admits only the named
  server set.
- [ ] Verify fake MCP tool success, tool failure, limit exhaustion, and
  redacted observability for every provider binding.
- [ ] Verify existing no-tool generate, chat, structured-record, URL/search,
  and provider failure regressions.
- [ ] Record opt-in live MCP and tool-capable Gemma/Ollama heavy-test evidence.
