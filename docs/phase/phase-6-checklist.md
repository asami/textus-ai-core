# Phase 6 Checklist - Gemma-First Strategy and Tool Orchestration

status=done
phase=[Phase 6 - Gemma-First Strategy and Tool Orchestration](phase-6.md)

## Stage OS-01 - Profile-Owned Strategy

Stage Status:
- Current status: DONE

- [x] Add explicit `gemma-first-gemini` and `gemma-first-codex-cli` profiles.
- [x] Retain all five strategy kinds for an operator-selected Gemma work
  execution; shipped Gemma-first defaults use only the work-purpose strategies.
- [x] Preserve single-provider behavior for every conventional profile.

## Stage OS-02 - Bounded Runner Execution

Stage Status:
- Current status: DONE

- [x] Attempt Gemma before a configured commercial provider only for a selected
  Gemma work execution; thinking classes select their commercial execution
  directly.
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
- Current status: DONE

- [x] Verify all Sanpomap purposes through deterministic strategy fixtures.
- [x] Verify deterministic bounded repair and one controlled commercial
  escalation through Sanpomap acceptance.
  The controlled escalation is accepted in Sanpomap
  `docs/evidence/phase-2-assembled-live-evidence.yaml`. `TextusAiRunnerSpec`
  uses a deterministic provider stub that returns an incomplete Scenario DSL,
  receives the acceptance `repair` decision, and returns a corrected artifact.
  Sanpomap's live path deliberately uses `max-repairs: 0`: it validates one
  model output deterministically and accepts or rejects it without trying to
  force a stochastic repair.
- [x] Verify one assembled native Gemma primary generation through the
  packaged Sanpomap and Textus AI CARs. The accepted 2026-07-22 record used
  `gemma3:12b`, `provider=gemma`, and `strategy=structured` without Docker.
- [x] Verify native Ollama through the Textus AI `AiRunner` without Docker.
  `TEXTUS_AI_LIVE_NATIVE_GEMMA_TEST=true sbt --batch 'testOnly
  org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'` completed on 2026-07-22
  with `gemma3:4b` in 18 seconds.
  The ComponentFactory-backed profile binding also completed with `gemma3:12b`
  and `gemma-first-codex-cli` / `software-implementation` on the same host.
- [x] Record accepted Textus AI revision `72ed97e` in the Sanpomap Phase 2
  ledger.

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
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after Textus AI has one provider-neutral admitted
  catalog/function-call contract and every execution passes through CNCF limits
  and diagnostics. Provider-native protocol bindings are tracked separately.

- [x] Adapt the CNCF MCP client catalog to provider-neutral function
  definitions with stable tool identities.
- [x] Consume the CNCF internal Operation-tool socket separately and compose
  both sources only inside Textus AI, retaining `component.service.operation`
  and `server/tool` source identities and invocation boundaries.
- [x] Define bounded turns, calls, elapsed time, argument size, and result-size
  behavior with structured failure propagation.
- [x] Record redacted source identities and total/source-specific tool summary
  facts in CallTree and response metadata without endpoint, credential, prompt,
  argument, or raw-result data.

## Stage MO-03 - Local Provider Binding

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after Gemma/Ollama tool-call continuations are
  deterministically verified and no-tool generate/chat behavior is unchanged.

- [x] Emit admitted function definitions through the Ollama chat protocol.
- [x] Execute returned tool calls through the common bounded orchestration path
  and return tool results in the required follow-up messages.
- [x] Reject a selected tool workflow when the resolved local model/provider
  cannot support the required function protocol.

## Stage MO-04 - Commercial Provider Bindings

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after OpenAI, Gemini, and Anthropic Messages
  continue the common MCP tool loop through their documented native function
  formats.

- [x] Implement OpenAI Responses function-call output continuation.
- [x] Implement Gemini Interactions function-result continuation.
- [x] Implement Anthropic Messages `tool_use` / `tool_result` continuation.
- [x] Preserve existing logical URL/search tool mapping and no-tool behavior.
- [x] Keep Codex CLI and Claude Code CLI as fixed runtime-owned managed-process
  providers; do not project the Textus AI function catalog or caller-provided
  CLI/MCP settings into either CLI.

## Stage MO-05 - Executable Evidence And Closure

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Close only after fake MCP and provider evidence covers every
  admitted and rejected path; live provider/MCP verification remains explicitly
  optional heavy-test evidence.

- [x] Verify profile and application-purpose resolution admits only the named
  server set. `AiRuntimeProfileSpec` rejects application-purpose MCP selection
  before Port activation; `ComponentFactorySpec` and
  `ToolSourceConsumerSpec` expose only the runtime-owned logical set and its
  admitted catalog.
- [x] Verify fake MCP tool success, rejection, limit exhaustion, and redacted
  observability for the completed Gemma provider binding.
- [x] Verify fake MCP tool failure and native continuation behavior for each
  commercial provider binding. `CommercialToolOrchestrationSpec` runs OpenAI,
  Gemini, and Anthropic through the common MCP invocation boundary, verifies
  their native correlated continuation after success, and verifies that a
  failed invocation stops before a synthetic provider result is sent.
- [x] Verify existing no-tool generate, chat, structured-record, URL/search,
  and provider failure regressions. `TextusAiRunnerSpec` covers plain
  generate/chat bindings, strict and recovered structured records, and
  redacted provider failures; `AiProviderAdmissionSpec` and the Google/OpenAI
  runner specifications cover logical URL/search admission and provider
  mapping.
- [x] Record opt-in tool-capable Gemma/Ollama heavy-test evidence.
  `TEXTUS_AI_LIVE_GEMMA_TEST=true sbt --batch 'testOnly
  org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'` passed on 2026-07-22 in
  7 minutes 37 seconds. It verified profile-owned Docker lifecycle, no-tool
  `gemma:2b` generation, and a `functiongemma` native function-call request.
  The CNCF Streamable HTTP MCP fixture remains deterministic integration
  evidence; live remote MCP is out of scope for Phase 6 closure.

## Stage PC-01 - Execution-Class Profile Calibration

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when every shipped runtime profile has a
  deterministic five-class selection matrix, the direct `gemma-work-*`
  standard-work path has opt-in native evidence, and the Gemma-first thinking
  policy is explicit and tested.

- [x] Replace the four-class `*consideration` vocabulary with the five CNCF
  `AiExecutionClass` values: `simple-work`, `standard-work`,
  `simple-thinking`, `advanced-thinking`, and `deep-thinking`. Do not retain
  compatibility aliases.
- [x] Map `software-design` to `simple-thinking`, `software-analysis` to
  `advanced-thinking`, and `web-analysis` to `deep-thinking`.
- [x] Define the built-in Codex CLI matrix: Luna/medium, Terra/high, then Sol
  medium/high/xhigh for the three thinking classes.
- [x] Add `gemma-work-gemini` and `gemma-work-codex-cli`: `gemma:2b` handles
  simple work, `gemma3:12b` handles standard work, and every thinking class
  selects the named commercial provider directly.
- [x] Add one deterministic matrix specification for every shipped profile and
  every execution class, including model and reasoning selection where the
  provider supports it.
- [x] Add opt-in native `gemma3:12b` heavy evidence through a `gemma-work-*`
  profile for `standard-work`, verifying the assembled component path and a
  bounded response without an LLM repair or acceptance loop.
  `TEXTUS_AI_LIVE_GEMMA_WORK_TEST=true sbt --batch 'testOnly
  org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'` passed on 2026-07-22 in
  21 seconds with native `gemma3:12b` through `gemma-work-codex-cli`.
- [x] Select direct commercial execution for `gemma-first-*` thinking classes;
  test that Gemma is never selected for `simple-thinking`,
  `advanced-thinking`, or `deep-thinking`.
