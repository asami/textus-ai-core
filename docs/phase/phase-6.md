# Phase 6 - Provider-Neutral MCP Tool Orchestration

status=active
planned_at=2026-07-21
started_at=2026-07-21
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Allow selected Textus AI runtime profiles to use an operator-owned MCP tool
catalog through the existing `generate` and `chat` operations. An application
continues to select only its registered application purpose. Textus AI resolves
the standard purpose, execution class, runtime profile, and admitted MCP server
set before it invokes a provider.

## Scope

- Consume the CNCF Phase 45 MCP client Port and named server-set catalog.
- Bind MCP server-set selection to Textus AI runtime profile and execution
  class configuration only.
- Convert the admitted MCP tools into provider-neutral function definitions and
  execute bounded function-call continuation loops.
- Support Gemma/Ollama, OpenAI, and Google Gemini through their respective
  provider bindings while retaining current no-tool and built-in web-tool
  behavior.
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
- A runtime profile that selects local Gemma/Ollama must name a tool-capable
  model. A plain no-tool request retains the existing generate/chat path.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| MO-01 | Profile-owned MCP policy | Runtime profiles and execution classes resolve an admitted MCP server set without caller configuration. | done |
| MO-02 | Common orchestration | One bounded, redacted tool catalog and function-call execution path bridges Textus AI to the CNCF MCP client Port. | planned |
| MO-03 | Local provider binding | Gemma/Ollama emits and receives tool calls through `/api/chat`; no-tool generation remains unchanged. | planned |
| MO-04 | Commercial provider bindings | OpenAI Responses and Gemini Interactions execute the same admitted MCP tools through their native function continuation formats. | planned |
| MO-05 | Executable evidence and closure | Deterministic fake MCP/provider evidence verifies policy, safety, and provider regressions; live tests remain opt-in heavy tests. | planned |

## Implementation Status

MO-01 is complete. An execution class may select one logical
`mcp-server-set`; the selected runtime profile publishes the distinct logical
requirements through a CNCF `McpClientSocket` input Port. Application-purpose
configuration cannot select MCP connectivity, and the component sees neither
Codex MCP source configuration nor endpoint, transport, or credential data.
Provider-neutral catalog adaptation and function-call continuation begin in
MO-02.

## Dependencies

- CNCF Phase 45 must define and implement the MCP client Port, transport
  ExtensionPoint, server-set admission, bounded calls, and redacted diagnostics.
- Phase 5 remains active independently for its optional live Gemma/Ollama
  service provisioning evidence.

## References

- CNCF Phase 45: `cloud-native-component-framework/docs/phase/phase-45.md`
- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [Gemma Integration Design Note](../notes/gemma-integration-design-note.md)
- [Phase 5 Dashboard](phase-5.md)
