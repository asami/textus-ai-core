# textus-ai

TextusAi component development repository.

Purpose:

1. Use Cozy/CNCF features in the AI/LLM domain in a production-like way and identify gaps
2. Extend `sbt-cozy`, `cozy`, or `cloud-native-component-framework` when needed
3. As a secondary goal, produce distributable component artifacts

## Local Dependencies

- build plugin: `/Users/asami/src/dev2026/sbt-cozy`
- model compiler: `/Users/asami/src/dev2025/cozy`
- runtime foundation: `/Users/asami/src/dev2025/cloud-native-component-framework`

Notes:

- `src/main/cozy/ai.cml` uses the `.cml` extension, but its content is written in the Dox style consumed by the `cozy` modeler
- Provider-specific logic must not appear in the DSL; keep it in the adapter layer
- `OPERATION` kinds are usually inferred from `INPUT`. A `TYPE` section is
  only needed when you want to assert the intended kind explicitly and check it
  against the input value object.
- `AI_LLM_MODE=local` allows local Ollama first with optional fallback
- `AI_LLM_MODE=local-first` means local endpoint first, then fallback endpoint
- `AI_LLM_MODE=remote` means the primary endpoint only, without fallback

Example:

```bash
AI_LLM_MODE=remote \
AI_LLM_ENDPOINT=http://llm-server:8080 \
AI_LLM_MODEL=gemma:2b \
./scripts/run-textus-ai.sh command help
```

The script delegates to `CncfMain` and lets the CNCF CLI handle routing,
help, and component discovery.

`textus-ai` is intended to behave as a CNCF component first, so the same
component can be used as an implementation artifact and as a CLI command.

The bootstrap layer follows the `textus-user-account` pattern:

- `TextusAiComponent` is generated from `src/main/cozy/ai.cml`.
- `ComponentFactory` owns the CNCF adapter wiring, including Gemma bindings.
- `GeneratedDomainComponentLoader` provides a stable component creation entry point.
- `TextusAiComponentMain` exposes the component as a runtime-facing entry point.

This keeps generated code separate from adapter setup and mirrors the CNCF
component/factory split used in other Textus repositories.

## Initial Setup

```bash
./scripts/bootstrap-local-deps.sh
# To skip Cozy-related dependencies:
WITH_COZY=false ./scripts/bootstrap-local-deps.sh
```

## Development Flow

```bash
sbt compile
sbt cozyGenerate
```

`cozyGenerate` reads input from `src/main/cozy`. The minimal model starts at
`src/main/cozy/ai.cml`.

## Local Layout

```text
src/
  main/
    cozy/
    resources/
    scala/
  test/
    resources/
    scala/
```

`ai/directive` references the GitHub `ai-directive` submodule.

## Design Notes

- [TextusAi Basic Design](docs/ai-component/basic-design.md)
- [TextusAi Development Environment](docs/ai-component/dev-environment.md)

## AI Directive

Use `ai/directive` as the source of AI execution rules.

- Source: `/Users/asami/src/dev2026/ai-directive`
- Expected mount point: `ai/directive`
- Role: behavioral contract for Codex and ChatGPT Desktop
- Root references: `AGENT.md` and `RULE.md` link to `ai/directive/core`
