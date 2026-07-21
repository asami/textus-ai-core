# Phase 6 Checklist - Gemma-First Strategy and MCP Tool Orchestration

status=active
phase=[Phase 6 - Gemma-First Strategy and MCP Tool Orchestration](phase-6.md)

## Stage OS-01 - Profile-Owned Strategy

Stage Status:
- Current status: DONE

- [x] Add explicit `gemma-first-gemini` and `gemma-first-codex-cli` profiles.
- [x] Resolve all five strategy kinds from an application-purpose-only request.
- [x] Preserve single-provider behavior for every conventional profile.

## Stage OS-02 - Bounded Runner Execution

Stage Status:
- Current status: DONE

- [x] Attempt Gemma before any configured commercial provider.
- [x] Bound repairs to zero through three and provider attempts to one or two.
- [x] Admit fallback only for availability, timeout, malformed output, domain
  validation, evidence, and ambiguity.
- [x] Keep authorization, capability, admission, credential-policy, input, and
  resource-limit failures terminal.
- [x] Record strategy, attempt lineage, repair count, escalation reason, final
  provider, duration, and existing reported usage safely.
- [x] Re-run cost admission for every provider attempt and require a distinct
  operator rate schedule before a commercial fallback can be admitted.

## Stage OS-03 - Application Acceptance Integration

Stage Status:
- Current status: DONE

- [x] Resolve an acceptance Operation from operator configuration rather than
  the application request.
- [x] Execute accept, bounded repair, confirmation/escalation, and rejection
  semantics without moving domain policy into Textus AI.
- [x] Keep prompt, candidate, credential, endpoint, and raw payload content out
  of execution metadata.

## Stage OS-04 - Cross-Component Evidence

Stage Status:
- Current status: OPEN

- [x] Verify all Sanpomap purposes through deterministic strategy fixtures.
- [ ] Verify one assembled local Gemma repair and one controlled commercial
  escalation through Sanpomap acceptance.
  The controlled escalation is accepted in Sanpomap
  `docs/evidence/phase-2-assembled-live-evidence.yaml`; an assembled local
  Gemma repair remains open.
- [ ] Record the accepted Textus AI revision in the Sanpomap Phase 2 ledger.

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
