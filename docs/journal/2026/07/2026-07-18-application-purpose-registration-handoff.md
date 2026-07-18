# Application-Purpose Registration Handoff

status=handoff
scope=Sanpomap, GeoResolver, and other application adoption of Textus AI Phase 4 registration
updated_at=2026-07-18

## Change

The earlier configuration-defined application-purpose mapping is superseded.
An application now publishes its own names through the CNCF `AiRunner` Port at
component bootstrap. The application owns the domain vocabulary and default
policy; Textus AI owns standard-purpose validation and provider resolution.

## Application Registration

An application component publishes this output Port entry during its factory
construction:

```scala
AiRunnerApplicationPurposeRegistration(Vector(
  AiRunnerApplicationPurpose(
    name = "sanpomap-scenario-generation",
    defaultStandardPurpose = "software-implementation"
  ),
  AiRunnerApplicationPurpose(
    name = "sanpomap-location-investigation",
    defaultStandardPurpose = "web-analysis",
    defaultPolicy = AiRunnerApplicationPurposePolicy(
      maxOutputTokens = Some(240),
      maxConcurrent = Some(1)
    )
  ),
  AiRunnerApplicationPurpose(
    name = "georesolver-structured-location-extraction",
    defaultStandardPurpose = "structured-extraction",
    defaultPolicy = AiRunnerApplicationPurposePolicy(maxOutputTokens = Some(120))
  )
))
```

The application caller continues to use:

```scala
AiRunnerRequirement(
  purpose = Some("sanpomap-location-investigation"),
  purposeRequired = true
)
```

It must not provide provider, mode, engine, model, execution class, or tools.

## Deployment Tuning

The deployment selects `textus.ai.profile` and may tune an existing name:

```yaml
textus:
  ai:
    profile: gemini
    application-purposes:
      sanpomap-location-investigation:
        max-output-tokens: 180
        timeout-seconds: 45
```

This configuration does not register the name and cannot supply `purpose`,
provider, model, execution class, or tools. A name without the application's
Port registration fails before provider execution.

## Observability

Applications may use the safe response metadata and CallTree facts
`ai.policy.application_purpose`, `ai.policy.effective_standard_purpose`,
`ai.policy.runtime_profile`, and
`ai.policy.effective_execution_class`. They must not infer or persist raw
provider payloads, credentials, prompts, or a provider/model selection.
