# Runtime Profile Application-Purpose Adoption Handoff

status=handoff
scope=Sanpomap and GeoResolver adoption of Textus AI Phase 4
updated_at=2026-07-18
superseded_by=2026-07-18-application-purpose-registration-handoff.md

> Superseded: this record describes the initial Phase 4 configuration-mapping
> approach. Use the [application-purpose registration handoff](2026-07-18-application-purpose-registration-handoff.md)
> for current integration guidance.

## Decision

Sanpomap and GeoResolver call Textus AI with an application purpose only.
They do not select `textus.ai.profile`, provider, model, engine, execution
class, reasoning level, or tools in `AiRunnerRequirement`.

The deployment selects one profile under `textus.ai.profile`; `gemini` and
`codex-cli` are the supplied Phase 4 profiles. The owning deployment maps
each application purpose to a Textus AI standard purpose and may narrow its
policy bounds.

## Suggested Mappings

```yaml
textus:
  ai:
    profile: gemini
    application-purposes:
      sanpomap-scenario-generation:
        purpose: software-implementation
      sanpomap-location-investigation:
        purpose: web-analysis
        max-output-tokens: 240
      georesolver-structured-location-extraction:
        purpose: structured-extraction
        max-output-tokens: 120
```

The exact domain names remain application-owned. They must map to one shipped
standard purpose. A mapping may configure limits, rate schedule, timeout,
retry, concurrency, output-schema identity, or prompt-contract identity; it
cannot select concrete runtime settings.

## Caller Contract

Sanpomap passes the selected domain name as
`AiRunnerRequirement(purpose = Some(name), purposeRequired = true)`. GeoResolver
preserves that contract when it delegates work. Neither application passes
provider/model/tool fields or starts Codex CLI directly.

The caller should inspect `ai.policy.runtime_profile` and
`ai.policy.effective_execution_class` for safe execution attribution. It must
not depend on model-profile, generic-purpose, or logical-level metadata; those
facts were removed in Phase 4.

## Failure Boundary

An unknown mapping, an unsupported runtime profile, a legacy configuration
key, a caller concrete selection, or a broadened application bound fails before
provider/process execution. Applications should surface that structured failure
to their operator and must not fall back to another provider or direct process
execution.

## Adoption Checks

1. Select `textus.ai.profile` in the merged CNCF configuration.
2. Add only `application-purposes.<name>.purpose` mappings and allowed bounds.
3. Execute a deterministic `generate` or `generateRecord` scenario for each
   application purpose.
4. Confirm response/CallTree metadata contains the selected runtime profile
   and effective execution class without credentials or raw prompts.
