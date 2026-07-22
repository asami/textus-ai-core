# AI Runtime Profile Resolution

status=accepted
scope=textus-ai runtime profile and purpose resolution
updated_at=2026-07-22

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
component-instance overrides, invalid-value rejection, and instance isolation.
