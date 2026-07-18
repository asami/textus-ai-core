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

### Codex CLI

The Codex CLI provider is explicitly opt-in. It has no environment-variable
bootstrap and never reads a Codex credential, account identity, or shell
setting. Its executable is an explicit Textus AI runtime configuration value,
not an application request property.

```yaml
textus:
  ai:
    provider: codex
    mode: local
    engine: codex-cli
    codex:
      enabled: true
      executable: /Applications/ChatGPT.app/Contents/Resources/codex
      schema-maximum-bytes: 65536
```

`codex-cli` is accepted as a provider-selection alias; response execution
facts are normalized to the `codex` provider name. The optional request
property `ai.codex.timeout-millis` requests a tighter process execution time
limit. It cannot widen the runtime capability limit.

When enabled with an absolute executable path, Textus AI runtime assembly
installs and grants the logical `codex-cli` Process Execution capability in its
component scope. The runtime-owned definition fixes `codex exec --sandbox
read-only --ephemeral --skip-git-repo-check`, finite process limits, and an
empty environment allowlist. It admits only `-` or `--output-schema schema.json
-`; record generation may materialize only the bounded `schema.json` WorkArea
input file. Textus AI installs CNCF's generic local Process Execution driver;
the driver runs every invocation in a managed WorkArea. The CNCF UnitOfWork
owns each WorkArea and process-handle lifecycle; the local driver's launch
workers are shared daemon runtime infrastructure.

For a Codex purpose, model and reasoning selection must come from an approved
named `model-profile`; caller model properties and request-level model
requirements remain invalid. At component startup Textus AI compiles every
configured Codex model profile into a finite Process Execution capability. A
profile capability contains a fixed `--model` value and, when configured, one
of the fixed reasoning levels `minimal`, `low`, `medium`, `high`, or `xhigh`
as `model_reasoning_effort`. Configuration values are therefore not copied into
per-request CLI arguments.

Codex Web research is also purpose/profile controlled. A profile that permits
`web_search` receives a separate fixed capability with global `--search` before
`exec`; `url_context` is supported only together with `web_search`, because the
installed Codex CLI exposes no narrower URL-only command capability. A missing
profile, a direct model override, a URL-only request, or a tool set outside the
profile fails structurally before process execution.

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-web-research:
        model-profile: codex-artscene-research
    model-profiles:
      codex-artscene-research:
        provider: codex
        mode: local
        engine: codex-cli
        model: gpt-5-codex
        reasoning-level: high
        tools: url_context, web_search
```

Codex authentication remains the responsibility of the locally installed
Codex CLI. Textus AI never exposes authentication material in request,
response, metadata, or CallTree data. The executable specification uses the
CNCF deterministic Process Execution test profile and does not invoke a live
Codex CLI, network service, or account. A live `--search` invocation is an
optional operator smoke only after local CLI authentication and Web-search
policy have been approved; it is not part of automated runtime validation.

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

Purpose profiles also supply bounded defaults for provider-neutral execution:

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-web-research:
        provider: google
        model: gemini-3.5-flash
        tools: url_context, web_search
        max-output-tokens: 240
        timeout-seconds: 90
        record-retry-limit: 2
        max-concurrent: 2
```

`max-output-tokens` must be positive, `timeout-seconds` must be positive, and
`record-retry-limit` is an integer from `0` through `3`. `max-concurrent` must
be positive and is an operator-owned per-purpose admission limit; it cannot be
widened by a request property. Invalid values and a
missing named `model-profile` fail as configuration errors before a provider is
invoked. `AiGenerateRequest.maxTokens`, an `ai.timeout-seconds` request
property, and an `ai.record.retry-limit` request property override the
corresponding profile default.

### Generic Purposes And Logical Levels

Logical levels keep application callers independent of provider, model, and
reasoning choices. A level selects one approved `model-profile`; a generic
purpose selects a level; an application purpose may reference that generic
purpose through `base-purpose` and only narrow its tools or execution bounds.

```yaml
textus:
  ai:
    levels:
      deep-consideration: { model-profile: openai-deep }
      standard-consideration: { model-profile: openai-standard }
      standard-work: { model-profile: openai-standard }
      simple-work: { model-profile: local-simple }
    generic-purposes:
      software-analysis:
        level: standard-consideration
      software-design:
        level: deep-consideration
      software-implementation:
        level: standard-work
      command-execution:
        level: simple-work
      web-analysis:
        level: deep-consideration
      structured-extraction:
        level: standard-work
    purposes:
      artscene-exhibition-web-research:
        base-purpose: web-analysis
        max-output-tokens: 240
        max-concurrent: 1
```

`base-purpose` must name a configured generic purpose. The standard catalog is
`software-analysis`, `software-design`, `software-implementation`,
`command-execution`, `web-analysis`, and `structured-extraction`; its rationale and boundaries are in
[`docs/design/ai-purpose-catalog.md`](docs/design/ai-purpose-catalog.md). An application purpose
cannot replace its inherited provider, mode, engine, model-profile, model, or
reasoning setting; it may only select a subset of inherited tools and reduce
maximum-output, timeout, retry, or concurrency bounds. Unknown generic levels,
missing bases, and broadening configuration fail before a provider binding is
selected. Generic purpose names are ordinary purpose values, so a caller may
request `AiRunnerRequirement(purpose = Some("software-analysis"))` without
provider fields.

The current `generic-purposes` configuration is transitional: it binds logical
levels and may also constrain tools beside the purpose name. The accepted
design introduces an explicit `executionClass` selector alongside `purpose`.
Operator model-profile configuration remains separate. See
[`docs/design/ai-purpose-catalog.md`](docs/design/ai-purpose-catalog.md).

Each configured logical level is also available as an implicit purpose. For
example, `AiRunnerRequirement(purpose = Some("standard-work"))` resolves the
configured `standard-work` level without exposing its model profile. A model
profile name is not an implicit purpose. This is a compatibility path until
the CNCF `executionClass` contract is available.

Textus AI maps an effective output-token limit to Google `generateContent` and
Interactions requests, OpenAI Chat Completions and Responses requests, and
Gemma/Ollama `options.num_predict`. It rejects an effective output-token limit
for Codex CLI because the admitted CLI capability has no equivalent token-limit
control. A provider-reported output count above the effective limit is a
structured failure. When a provider omits output usage, the request remains
bounded at its provider boundary and reports
`ai.limitation.codes=output_limit_not_verified` rather than claiming an
independent measurement. HTTP providers receive the effective timeout through
the CNCF HTTP UnitOfWork; Codex CLI converts it to a managed-process execution
limit.

Purpose profiles may require caller-owned structured-output and prompt
identities without storing an application schema or prompt text:

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-extraction-from-source:
        output-schema-id: artscene.exhibitions.v1
        prompt-contract-id: artscene.exhibition.extract.v1
```

`output-schema-id` permits only `generateRecord`; the caller must provide its
concrete `Record` schema and pass the matching `ai.output-schema-id` property.
`prompt-contract-id` requires the matching `ai.prompt-contract-id` property on
every operation. IDs use letters, digits, `.`, `_`, and `-`; they do not carry
prompt text, system instructions, source restrictions, or provider-specific
prompt options. Those remain application-owned contracts until CNCF defines a
provider-neutral prompt-contract model. Profile keys such as `prompt`,
`system-instruction`, `source-restrictions`, and `output-constraints` fail
explicitly instead of being applied or ignored.

Successful AI responses and their CallTree entries publish safe effective policy
facts under `ai.policy.*`: maximum output tokens, timeout, record retry limit,
maximum concurrency, output-schema ID, prompt-contract ID, and Codex
model-profile/reasoning-level when configured. Codex profile resolution also
publishes `ai.execution.enabled_tools`; it does not publish URLs, prompts, or
raw CLI output.
When a purpose configures `max-concurrent`, Textus AI uses CNCF's runtime-owned
scoped admission and returns a structured saturation failure without selecting
another provider or invoking the provider binding. Tool-enabled Google
and OpenAI responses also publish `ai.execution.tool_result_summary` with only
provider-reported numeric counters. Neither surface contains raw prompts,
schemas, URLs, provider payloads, or credentials.

Request-level tools still win over profile tools. The runtime maps logical
tools to provider-specific APIs:

- Google Gemini: `url_context` and `web_search` use the Gemini Interactions API
  and map `web_search` to Google's `google_search` tool.
- OpenAI: `url_context` and `web_search` use the OpenAI Responses API with the
  `web_search` tool.
- Gemma/Ollama: tool requests fail explicitly because those runtimes do not
  provide these provider web tools.
- Codex CLI: a selected purpose model profile maps `web_search` (and optional
  paired `url_context`) to its fixed global `--search` capability. Other Codex
  tool selections fail explicitly.

Before a provider binding is resolved, `TextusAiRunner` validates the effective
logical tools and known provider-local model constraints. The Codex provider
then resolves the selected profile to its fixed admitted capability. This
prevents an unsupported tool or Codex model override from binding a provider
and never downgrades a tool-enabled request to plain generation.

Provider-local parameters should be passed as request `Property` values rather
than as global system properties. Supported property keys include:

- `ai.tools`: comma-separated logical tools for direct request-level use.
- `ai.output-schema-id`: caller-owned structured output identity.
- `ai.prompt-contract-id`: caller-owned prompt contract identity.
- `ai.model`: request-level model override.
- `ai.timeout-seconds`: request-level HTTP timeout.
- `ai.openai.web_search.search_context_size`: OpenAI web search context size.
- `ai.openai.web_search.return_token_budget`: OpenAI web search token budget.
- `ai.openai.reasoning.effort`: OpenAI Responses API reasoning effort.
- `ai.codex.timeout-millis`: tighter bounded timeout for a Codex CLI request.

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
- [Phase 2 Dashboard](docs/phase/phase-2.md)
- [Phase 2 Checklist](docs/phase/phase-2-checklist.md)

## AI Directive

Use `ai/directive` as the source of AI execution rules.

- Source: `/Users/asami/src/dev2026/ai-directive`
- Expected mount point: `ai/directive`
- Role: behavioral contract for Codex and ChatGPT Desktop
- Root references: `AGENT.md` and `RULE.md` link to `ai/directive/core`
