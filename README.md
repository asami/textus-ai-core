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

The supplied profiles are `gemini` and `codex-cli`. Standard purposes are
`software-analysis`, `software-design`, `software-implementation`,
`command-execution`, `web-analysis`, and `structured-extraction`.

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
OpenAI, Google Gemini, and an opt-in Codex CLI runtime.

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
