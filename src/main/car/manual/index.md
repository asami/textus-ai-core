# Textus AI Runtime Reference Manual

## Scope

`textus-ai-runtime` is a CNCF CAR component that provides provider-neutral AI
generation capabilities to Textus applications. The component exposes stable
`generate`, `chat`, and `generateRecord` runtime behavior while resolving
provider choice, model choice, tools, limits, and local process policy through
runtime profiles and registered application purposes.

The CML model source is `src/main/cozy/ai.cml`. Provider-specific behavior is
implemented in runtime adapters and is intentionally excluded from the component
operation surface.

## Component

Component name: `textus-ai-runtime`

Component class: `TextusAi`

Scala package: `org.simplemodeling.textus.ai`

The component is loaded as a CAR through CNCF metadata. The generated
`component-descriptor.json` declares the component identity, version, and
component name used by the CNCF runtime.

## Runtime Profile Contract

One runtime profile must be selected through CNCF configuration:

```yaml
textus:
  ai:
    profile: codex-cli
```

The profile is resolved during component initialization. An absent, unknown, or
invalid profile fails before provider execution.

Built-in profiles include local CLI, local Ollama, remote API, and composite
profiles. Composite profiles such as `gemma-work-codex-cli` use local Gemma for
work classes and a commercial provider for thinking classes.

## Application Purpose Contract

Applications register domain-specific purposes at bootstrap through the CNCF
`AiRunnerApplicationPurposeRegistrationSocketSet`. Textus AI consumes the
registered catalog and resolves each request from:

- application purpose
- standard purpose
- runtime profile
- execution class
- provider binding

Configuration may tune a registered application purpose, but configuration alone
does not create an application purpose. Duplicate registrations, unregistered
application purpose names, and invalid default standard purposes fail before
provider execution.

## Provider Boundary

Applications do not select providers, models, tool wire names, CLI flags,
Docker parameters, or API endpoints per request. Those values are operator-owned
configuration and runtime-profile policy.

Supported provider families are:

- Gemma through Ollama
- Google Gemini API
- OpenAI API
- Anthropic Messages API
- Codex CLI
- Antigravity CLI
- Claude Code CLI

Provider-local request options may be passed as `Property` values only where the
provider adapter accepts them. Runtime-owned provider selection and logical tool
resolution remain outside application caller control.

## Local Process Boundary

CLI providers use CNCF managed-process execution. Textus AI compiles profile
policy into admitted process capabilities and does not expose shell command
construction to application callers.

Codex CLI requires explicit runtime configuration:

```yaml
textus:
  ai:
    profile: codex-cli
    codex:
      enabled: true
      executable: /Applications/ChatGPT.app/Contents/Resources/codex
      schema-maximum-bytes: 65536
```

Gemma uses native Ollama by default. Docker-backed Ollama is available only when
explicitly selected by the operator and is not used as an implicit fallback.

## Tools And MCP

Logical tools such as web search are resolved from standard purposes and runtime
profiles. CNCF-owned Operation/MCP capability is applied by CNCF policy outside
provider-specific request surfaces.

Provider standard tools and CNCF internal tools are intentionally separate:
provider APIs and CLIs may expose their own built-in Web features, while CNCF
Operation tools are handled by the Textus AI prompt-loop/runtime boundary.

## Observability

Textus AI records safe runtime facts in CallTree attributes, including
application purpose, effective standard purpose, runtime profile, execution
class, provider family, model family where safe, tool usage summary, and cost or
budget observations when available. Secret values and raw credentials are never
recorded.

## Failure Semantics

The component rejects structural configuration problems before provider
execution. Examples include:

- missing or unsupported `textus.ai.profile`
- unregistered required application purpose
- duplicate application purpose registration
- invalid default standard purpose
- provider/tool incompatibility
- disallowed caller provider, model, tool, or execution-class selection
- disabled or unconfigured local CLI provider
- unavailable native Ollama for a Gemma profile

Provider errors, process failures, and timeout outcomes are returned as
structured failures through the CNCF consequence model.

## Related Specifications

- `docs/spec/ai-application-purpose-registration.md`
- `docs/spec/ai-detailed-purpose-resolution.md`
- `docs/spec/ai-execution-class-resolution.md`
- `docs/spec/ai-execution-facts.md`
- `docs/spec/gemma-first-operational-profile.md`
- `docs/design/ai-purpose-catalog.md`
- `docs/design/ai-runtime-workflow-boundary.md`
