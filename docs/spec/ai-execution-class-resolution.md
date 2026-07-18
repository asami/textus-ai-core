# AI Runtime Profile Resolution

status=accepted
scope=textus-ai runtime profile and purpose resolution
updated_at=2026-07-18

## Contract

`AiRunnerRequirement.purpose` is the application-facing selector. The runtime
resolves it to a shipped standard purpose, execution class, and the selected
`textus.ai.profile` binding. The effective class is published as
`ai.policy.effective_execution_class`; the selected profile is published as
`ai.policy.runtime_profile`.

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
        purpose: structured-extraction
        max-output-tokens: 120
```

The runtime owns provider selection. Class configuration is an operator
override of the selected runtime profile, while an application purpose can
only narrow the resolved policy.

## Failure Rules

1. A configured application request requires a supported `textus.ai.profile`.
2. An application purpose must map to exactly one shipped standard purpose.
3. The caller cannot select provider, mode, engine, model, class, or tools.
4. An application purpose cannot select those settings or broaden a bound.
5. Legacy model-profile, generic-purpose, base-purpose, and level keys fail
   structurally before provider execution.
6. No missing or rejected selection falls back to a default provider.

## Executable Evidence

`AiRuntimeProfileSpec` verifies catalog defaults, override merge, application
mapping, safe facts, and legacy rejection. `TextusAiRunnerSpec` verifies
runtime execution, application policy, tool propagation, and no-provider-call
failure boundaries.
