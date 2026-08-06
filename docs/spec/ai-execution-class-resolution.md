# AI Runtime Profile Resolution

status=accepted
scope=textus-ai runtime profile and purpose resolution
updated_at=2026-08-05

## Contract

`AiRunnerRequirement.purpose` is the application-facing selector. Applications
register domain selectors through the CNCF `AiRunner` Port; the runtime resolves
the registered selector to a shipped standard purpose, execution class, and the selected
`textus.ai.profile` binding. The effective class is published as
`ai.policy.effective_execution_class`; the selected profile is published as
`ai.policy.runtime_profile`.

`textus.ai.profile` is declared as a typed CNCF component initialization
parameter. CNCF resolves the packaged, assembly, named-instance, runtime, and
test layers before Textus AI constructs provider bindings or Ports. The
resolved immutable value is the only profile selection consumed by the
component factory path. A malformed selected layer fails component
initialization rather than falling back to a lower-precedence value.

`textus.ai.execution-classes.<execution-class>.<leaf>` is likewise a typed
initialization path. It has exactly one bounded dynamic segment,
`<execution-class>`, followed by one finite declared leaf. Textus AI declares
the route; CNCF discovers, validates, resolves, and delivers its immutable
snapshot before provider construction. This is not an arbitrary configuration
lookup surface.

## Configuration

```yaml
textus:
  ai:
    profile: gemini
    execution-classes:
      standard-work:
        model: gemini-2.5-flash
        max-output-tokens: 240
    application-purposes:
      car-review-domain-terminology:
        max-output-tokens: 120
```

The runtime owns provider selection. Class configuration is an operator
override of the selected runtime profile, while application registration
defaults and registered-name configuration tuning can only narrow the resolved
policy. Configuration does not register an application purpose or select its
standard purpose.

### Execution-Class Key Contract

The public flat key is
`textus.ai.execution-classes.<execution-class>.<leaf>`. Nested YAML is a
serialization of that same flat contract. Compatibility prefixes remain
accepted: `textus.ai.executionClasses`,
`textus.runtime.ai.execution-classes`, `cncf.ai.execution-classes`, and
`cncf.runtime.ai.execution-classes`.

The finite optional-string leaves are `provider`, `mode`, `engine`, `model`,
`reasoning-level` (`reasoningLevel`), `tools` (`enabled-tools`),
`mcp-server-set` (`mcpServerSet`), `operation-tool-set` (`operationToolSet`),
`max-input-tokens`, `max-output-tokens`, `max-reasoning-tokens`,
`max-cost-microunits`, `rate-schedule`, `timeout-seconds`,
`record-retry-limit`, `max-concurrent`, `strategy-max-repairs`,
`strategy-max-provider-attempts`, `fallback-provider`, `fallback-mode`,
`fallback-engine`, `fallback-model`, `fallback-reasoning-level`, and
`fallback-rate-schedule`. An unknown leaf or any shape other than the one
dynamic segment and one declared leaf is rejected before provider construction.

CNCF owns precedence, in this fixed order:
`TestOverlay > RuntimeConfiguration > SubsystemInstance > AssemblyDefault > PackagedDefault`.
The highest present spelling wins: a compatibility spelling at a higher layer
outranks a canonical spelling at a lower layer. Textus AI never reorders this
result. It flattens the raw subsystem configuration with direct flat entries
taking precedence over nested equivalents, removes every execution-class route
prefix from the parser input, and overlays only canonical entries projected
from the typed snapshot.

The parser reads execution-class leaves only from that projected
`ResolvedConfiguration.configuration` container. It does not consult runtime
configuration helpers, JVM properties, environment variables, or configuration
file fallback for any execution-class key. Purpose-binding keeps its existing
precedence and lookup semantics, but its execution-class fallback uses the same
configuration-only projected lookup. Application-purpose, selected-profile,
provider, and rate-schedule configuration families retain their established
lookup behavior.

The filtered parser projection deliberately has `ConfigurationTrace.empty`:
its retained values have been filtered, normalized, and overlaid, so raw trace
provenance would be misleading. The original raw `ResolvedConfiguration` and
its trace are unchanged. Authoritative per-leaf provenance remains in CNCF's
typed `ComponentInitializationParameters` snapshot; the parser projection is
not a provenance carrier. `AiProfileConfig` remains the parser and validation
boundary, while provider-specific and unrelated configuration families remain
raw configuration inputs.

## Failure Rules

1. A configured application request requires a supported `textus.ai.profile`.
2. An application purpose must be registered and map to exactly one shipped
   standard purpose.
3. The caller cannot select provider, mode, engine, model, class, or tools.
4. Registration and configuration tuning cannot select those settings or
   broaden a bound.
5. Legacy model-profile, generic-purpose, base-purpose, and level keys fail
   structurally before provider execution.
6. No missing or rejected selection falls back to a default provider.

## Executable Evidence

`AiRuntimeProfileSpec` verifies catalog defaults, registration defaults,
configuration tuning, strict registration validation, safe facts, and legacy
rejection. `TextusAiRunnerSpec` verifies runtime execution, application policy,
tool propagation, and no-provider-call failure boundaries.
`ComponentFactorySpec` verifies initialization-time runtime defaults, named
component-instance overrides, invalid-value rejection, and instance isolation,
plus typed execution-class route projection, every declared canonical leaf and
compatibility spelling, five-layer precedence, parser-only trace isolation,
ambient execution-class property isolation, and structured route rejection
before provider construction.
