# textus-ai-runtime

Textus AI runtime development repository.

Purpose:

1. Use Cozy/CNCF features in the AI runtime domain in a production-like way and identify gaps
2. Extend `sbt-cozy`, `cozy`, or `cloud-native-component-framework` when needed
3. As a secondary goal, produce distributable component artifacts

## Local Dependencies

- build plugin: `/Users/asami/src/dev2026/sbt-cozy`
- model compiler: `/Users/asami/src/dev2025/cozy`
- runtime foundation: `/Users/asami/src/dev2025/cloud-native-component-framework`

Notes:

- `src/main/cozy/ai.cml` uses the `.cml` extension, but its content is written in the Dox style consumed by the `cozy` modeler
- Provider-specific logic must not appear in the DSL; keep it in the runtime adapter layer
- `OPERATION` kinds are usually inferred from `INPUT`. A `TYPE` section is
  only needed when you want to assert the intended kind explicitly and check it
  against the input value object.
- `AI_LLM_MODE=local` allows local Ollama first with optional fallback
- `AI_LLM_MODE=local-first` means local endpoint first, then fallback endpoint
- `AI_LLM_MODE=remote` means the primary endpoint only, without fallback
- `AI_LLM_PROVIDER=gemma|openai|google` selects the runtime provider
- `OPENAI_API_KEY` with `AI_OPENAI_MODEL` enables the OpenAI runtime adapter
- `GOOGLE_API_KEY` or `GEMINI_API_KEY` with `AI_GOOGLE_MODEL` enables the Google runtime adapter

Example:

```bash
AI_LLM_MODE=remote \
AI_LLM_ENDPOINT=http://llm-server:8080 \
AI_LLM_MODEL=gemma:2b \
./scripts/run-textus-ai.sh command help
```

The script delegates to `CncfMain` and lets the CNCF CLI handle routing,
help, and component discovery.

Provider-specific endpoints:

- Gemma/Ollama: `AI_LLM_ENDPOINT`, `AI_LLM_FALLBACK_ENDPOINT`, `AI_GEMMA_MODEL`
- OpenAI: `AI_OPENAI_ENDPOINT`, `OPENAI_API_KEY`, `AI_OPENAI_MODEL`
- Google Gemini: `AI_GOOGLE_ENDPOINT`, `GOOGLE_API_KEY` or `GEMINI_API_KEY`, `AI_GOOGLE_MODEL`

Gemma/Ollama, OpenAI, and Google can be configured through CNCF runtime
configuration. The same
configuration keys can be supplied from user-local configuration and from
project-local `conf/cncf/config.yaml`; the split below is an operational rule,
not a different schema. Keep sensitive values in the user-local/private
configuration file and keep ordinary runtime selection in the project
configuration.

User-local/private CNCF runtime configuration (`~/.cncf/config.yaml`):

```yaml
textus:
  ai:
    openai:
      api-key: "sk-..."
      model: "gpt-4.1-mini"
      timeout-seconds: 180
```

Project configuration (`conf/cncf/config.yaml`):

```yaml
textus:
  ai:
    provider: openai
    mode: remote
    engine: gpt

    model-profiles:
      linear-worker:
        provider: openai
        mode: remote
        engine: gpt
        model: gpt-4.1-mini
        role: worker
        quality: standard
        cost: low
        latency: low
      linear-judge:
        provider: openai
        model: gpt-5.5
        role: judge
        quality: high
        cost: high
        latency: high

    purposes:
      linear-feature:
        worker:
          anchor-plan:
            model-profile: linear-worker
          route-validation:
            model-profile: linear-worker
          osm-route-validation:
            model-profile: linear-worker
        judge:
          ambiguity-resolution:
            model-profile: linear-judge
```

For a local Gemma/Ollama runtime, ordinary connection settings can remain in
the project configuration when they are not sensitive:

```yaml
textus:
  ai:
    gemma:
      endpoint: http://127.0.0.1:11434
      model: gemma3:4b
      timeout-seconds: 45
```

`purposes` lets application components pass `AiRunnerRequirement.purpose`
without hard-coding a concrete provider, mode, engine, or model. Direct
request-level provider/mode/engine/model
requirements still win over configured profiles. The performance fields
(`role`, `quality`, `cost`, and `latency`) are operator metadata in this slice;
they document selection intent and prepare later automatic escalation policies.

AI runner tool support is exposed through `AiRunnerRequirement.tools` and
purpose profiles. Use provider-neutral logical tool names:

- `url_context`
- `web_search`

Purpose profiles can enable tools without application code becoming provider
specific:

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-fetch:
        provider: google
        model: gemini-3.5-flash
        tools: url_context, web_search
```

Request-level tools still win over profile tools. The runtime maps logical
tools to provider-specific APIs:

- Google Gemini: `url_context` and `web_search` use the Gemini Interactions API
  and map `web_search` to Google's `google_search` tool.
- OpenAI: `url_context` and `web_search` use the OpenAI Responses API with the
  `web_search` tool.
- Gemma/Ollama: tool requests fail explicitly because local Ollama does not
  provide these provider web tools.

Provider-local parameters should be passed as request `Property` values rather
than as global system properties. Supported property keys include:

- `ai.tools`: comma-separated logical tools for direct request-level use.
- `ai.model`: request-level model override.
- `ai.timeout-seconds`: request-level HTTP timeout.
- `ai.openai.web_search.search_context_size`: OpenAI web search context size.
- `ai.openai.web_search.return_token_budget`: OpenAI web search token budget.
- `ai.openai.reasoning.effort`: OpenAI Responses API reasoning effort.

`textus-ai-runtime` is intended to behave as a CNCF AI runtime first, so the
same component can be used as an implementation artifact and as a CLI command.

The bootstrap layer follows the `textus-user-account` pattern:

- `TextusAiComponent` is generated from `src/main/cozy/ai.cml`.
- `ComponentFactory` owns the CNCF runtime adapter wiring, including Gemma bindings.
- `GeneratedDomainComponentLoader` provides a stable component creation entry point.
- `TextusAiComponentMain` exposes the component as a runtime-facing entry point.

This keeps generated code separate from runtime adapter setup and mirrors the CNCF
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

- [AI Runner Execution Facts](docs/design/ai-runner-execution-facts.md)
- [Mock AI Executable Specification](docs/spec/mock-ai-executable-spec.md)
- [Phase 1 AI Runner Executable Specification](docs/spec/phase-1-ai-runner-executable-spec.md)
- [Phase 1 Dashboard](docs/phase/phase-1.md)

## AI Directive

Use `ai/directive` as the source of AI execution rules.

- Source: `/Users/asami/src/dev2026/ai-directive`
- Expected mount point: `ai/directive`
- Role: behavioral contract for Codex and ChatGPT Desktop
- Root references: `AGENT.md` and `RULE.md` link to `ai/directive/core`
