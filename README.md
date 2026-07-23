# textus-ai-runtime

Textus AI runtime is a provider-neutral CNCF component for `generate`, `chat`,
and `generateRecord`. Applications select a registered domain purpose; Textus
AI resolves the runtime profile, provider binding, model, tools, and limits.

## Local Dependencies

- build plugin: `/Users/asami/src/dev2026/sbt-cozy`
- model compiler: `/Users/asami/src/dev2025/cozy`
- runtime foundation: `/Users/asami/src/dev2025/cloud-native-component-framework`

`src/main/cozy/ai.cml` is the Cozy/CML source. Provider-specific behavior stays
in runtime adapters, not in the CML model.

## Runtime Profiles And Application Purposes

Set one Textus AI runtime profile in merged CNCF configuration:

```yaml
textus:
  ai:
    profile: gemini
    execution-classes:
      standard-work:
        max-output-tokens: 240
```

`textus.ai.profile` is a declared CNCF component initialization parameter.
The runtime resolves its fixed configuration precedence before Textus AI
constructs provider bindings, Ports, or process policy. A named component
instance may therefore select its own profile through assembly component
configuration; an invalid higher-precedence value fails initialization and
never falls back to a lower default.

The supplied profiles are `gemma`, `gemini`, `antigravity-cli`, `openai`, `codex-cli`,
`claude-code`, `anthropic`, `gemma-simple-gemini`, `gemma-simple-codex-cli`,
`gemma-work-gemini`, and `gemma-work-codex-cli`. The `gemma-simple-*` composite
profiles use local Gemma only for `simple-work`. The `gemma-work-*` profiles use
local `gemma:2b` for `simple-work`, local `gemma3:12b` for `standard-work`, and
their named commercial provider for every thinking class. Standard purposes are `software-analysis`,
`software-design`, `software-implementation`, `command-execution`,
`web-analysis`, `structured-extraction`, `grounded-research`,
`evidence-synthesis`, `candidate-proposal`, `candidate-ranking`, and
`constrained-planning`.

A deployment may bind one standard purpose before its shared execution-class
default. This remains runtime-owned and is distinct from application-purpose
policy tuning:

```yaml
textus:
  ai:
    purpose-bindings:
      evidence-synthesis:
        provider: gemma
        mode: local
        engine: ollama
        model: gemma3:12b
```

`grounded-research` always adds the logical `web_search` requirement after
binding resolution. A binding to a provider without that capability fails before
provider execution.

`gemini` selects the remote Google API provider, while `antigravity-cli`
selects a local managed Antigravity process. `openai` selects the remote OpenAI API
provider. `codex-cli` remains a separate local managed-process provider;
selecting one never aliases the other.

An application registers its own domain purposes through its output
`Component.Port` during bootstrap:

```scala
AiRunnerApplicationPurposeRegistration(Vector(
  AiRunnerApplicationPurpose(
    name = "sanpomap-location-investigation",
    defaultStandardPurpose = "web-analysis",
    defaultPolicy = AiRunnerApplicationPurposePolicy(
      maxOutputTokens = Some(240),
      maxConcurrent = Some(1)
    )
  )
))
```

Textus AI consumes this through the CNCF
`AiRunnerApplicationPurposeRegistrationSocketSet`. The application caller uses
only the registered name:

```scala
AiRunnerRequirement(
  purpose = Some("sanpomap-location-investigation"),
  purposeRequired = true
)
```

A deployment may tune an already registered purpose, but configuration does not
create registrations or choose their standard purpose:

```yaml
textus:
  ai:
    application-purposes:
      sanpomap-location-investigation:
        max-output-tokens: 180
        timeout-seconds: 45
```

Caller/provider/model/tools/execution-class selection and configuration-only,
duplicate, or invalid registrations fail before a provider or managed process is
selected. Safe response metadata and CallTree facts include application purpose,
effective standard purpose, runtime profile, and execution class.

## Provider Configuration

Connection credentials and endpoints remain ordinary CNCF configuration. Keep
credentials in user-local configuration and provider-independent runtime policy
in project configuration. The current provider adapters are Gemma/Ollama,
OpenAI, Google Gemini, Anthropic Messages API, and opt-in Codex, Antigravity,
and Claude Code CLI runtimes.

Gemma/Ollama uses the local `gemma` profile. Its default runtime is native
Ollama at `http://127.0.0.1:11434`; Textus AI never starts Docker as a fallback.
If native Ollama is absent or unavailable, the generation or chat request fails
with the normal structured provider failure. Container lifecycle is not exposed
as component Process Execution capabilities; callers cannot provide Docker
arguments, images, or models.

```yaml
textus:
  ai:
    profile: gemma
```

The profile supplies `gemma:2b` by default. Set an execution-class model only
when the deployment needs a different approved local model:

```yaml
textus:
  ai:
    execution-classes:
      standard-work:
        model: gemma3:4b
```

Use an explicit endpoint when native Ollama listens on another host or port:

```yaml
textus:
  ai:
    profile: gemma
    gemma:
      endpoint: http://ollama.example:11434
```

Docker is an explicit, operator-only alternative. Set
`textus.ai.gemma.runtime: managed-docker` and do not set `gemma.endpoint`:

```yaml
textus:
  ai:
    profile: gemma
    gemma:
      runtime: managed-docker
```

The managed-service defaults are image `ollama/ollama:latest`, named volume
`textus-ai-ollama`, and logical Ollama API port `11434`. Operators may override
the image, volume, readiness timeout, and model-install timeout with
`textus.ai.gemma.service.image`, `volume-name`, `startup-timeout-seconds`, and
`model-install-timeout-seconds`. `managed-docker` and `gemma.endpoint` together
are an invalid configuration. The runtime owns provider-specific container
identity and host-port selection. CNCF Phase 44 verifies the generic Docker
transport; the Textus AI consumer path is verified separately as an opt-in
heavy test rather than an ordinary executable specification.

Run the Textus AI consumer-path heavy test only on a machine with Docker, an
available `ollama/ollama:latest` image, and enough capacity to download the
`gemma:2b` model. The current CNCF service-container gateway resolves available
images; image acquisition remains a deployment provisioning step:

```bash
TEXTUS_AI_LIVE_GEMMA_TEST=true \
  sbt --batch 'testOnly org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'
```

The test starts the runtime-owned service, installs the profile model, and
submits one generation request. It stops the service on completion but retains
the managed model volume for reuse.

Run the native path independently when Ollama is already running locally. This
does not start or contact Docker and uses `gemma3:4b` for the live check:

```bash
TEXTUS_AI_LIVE_NATIVE_GEMMA_TEST=true \
  sbt --batch 'testOnly org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'
```

Set `TEXTUS_AI_NATIVE_GEMMA_MODEL` to validate another locally installed model
without changing the runtime profile, for example `gemma3:12b`.
`TEXTUS_AI_NATIVE_GEMMA_PROFILE` and `TEXTUS_AI_NATIVE_GEMMA_PURPOSE` may
select an installed runtime profile and its standard purpose for a live profile
binding check.

Codex CLI requires explicit enablement and an absolute executable path:

```yaml
textus:
  ai:
    profile: codex-cli
    codex:
      enabled: true
      executable: /Applications/ChatGPT.app/Contents/Resources/codex
      schema-maximum-bytes: 65536
```

Textus AI compiles the profile into CNCF managed-process capabilities. Callers
do not invoke a shell or pass Codex model, reasoning, or Web flags directly.
Profiles selecting `gpt-5.6-sol`, `gpt-5.6-terra`, or `gpt-5.6-luna` require
Codex CLI `0.144.0` or later. Textus AI verifies that requirement through a
separate managed `codex --version` capability before it submits a prompt.

Antigravity CLI is an opt-in local managed-process provider distinct from the
remote `gemini` API profile. Install and authenticate the executable outside
Textus AI, then configure its absolute path:

```bash
brew install --cask antigravity-cli
agy
```

```yaml
textus:
  ai:
    profile: antigravity-cli
    antigravity-cli:
      enabled: true
      executable: /opt/homebrew/bin/agy
      home: /Users/example
```

The built-in profile uses Antigravity CLI's automatic model selection. An
operator may set an execution-class `model` to compile a fixed `--model` argument. The
runtime uses headless JSON and sandboxed plan mode. CNCF admits exactly one
bounded text argument as the `--print` prompt; callers cannot supply CLI options
or environment variables. Operators must configure an absolute
`textus.ai.antigravity-cli.home`; the runtime supplies only that fixed `HOME` so
`agy` can read its keyring-backed profile, and no ambient environment is
inherited. Profile-owned `web_search` and `url_context` tools use Antigravity's
built-in Web facilities; the Textus AI CNCF function catalog is not injected
into this managed CLI process.

Antigravity CLI `1.1.5` requires the headless prompt as a process argument
rather than stdin. CNCF bounds that argument and prevents option injection, but
the prompt can still be visible to same-host process inspection. Do not route
confidential prompts through this profile until Antigravity provides a stdin
contract or Textus AI adds an SDK transport.

Google ended individual free, Pro, and Ultra Gemini CLI service on 2026-06-18
and moved those terminal workflows to Antigravity CLI. Textus AI therefore
uses `agy` for the local Google managed-process profile. Enterprise or API-key
Gemini CLI remains outside this profile; use the remote `gemini` API profile
when direct API execution is required.

Claude Code is also opt-in and requires an absolute executable path. Textus AI
uses Claude Code non-interactive print mode with JSON output and a profile-owned
`sonnet` or `opus` model alias. It does not install, authenticate, or execute
Claude Code during runtime assembly:

```yaml
textus:
  ai:
    profile: claude-code
    claude:
      enabled: true
      executable: /usr/local/bin/claude
```

Anthropic Messages API is a separate remote provider from Claude Code. It uses
an API key configured by the operator; the selected runtime profile resolves
the model, and selecting `anthropic` never starts a local CLI process:

```yaml
textus:
  ai:
    profile: anthropic
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

Provider-local request options use `Property` values. Runtime-owned provider
selection and logical tools remain resolved from the selected profile for an
application purpose. See the [AI Purpose Catalog](docs/design/ai-purpose-catalog.md)
and [application-purpose registration specification](docs/spec/ai-application-purpose-registration.md).

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
- [Phase 4 Dashboard](docs/phase/phase-4.md)
- [AI Application-Purpose Registration Specification](docs/spec/ai-application-purpose-registration.md)

## AI Directive

Use `ai/directive` as the source of AI execution rules.

- Source: `/Users/asami/src/dev2026/ai-directive`
- Expected mount point: `ai/directive`
- Role: behavioral contract for Codex and ChatGPT Desktop
- Root references: `AGENT.md` and `RULE.md` link to `ai/directive/core`
