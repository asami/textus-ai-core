# Runtime Profile and Application Purpose Specification

date=2026-07-18
status=accepted
scope=textus-ai configuration and purpose-resolution contract

## Decision

Textus AI separates standard purposes, application purposes, execution
classes, and runtime profiles.

```text
application purpose
  -> standard purpose
  -> execution class
  -> selected runtime profile defaults
  -> merged CNCF configuration overrides
  -> effective provider / model / reasoning / tools / limits
```

An application selects only an application purpose. It does not select a
runtime profile, provider, model, engine, reasoning level, tools, or an
execution class.

## Standard Purposes

Textus AI owns the standard provider-neutral purpose catalog and its default
execution-class mapping. The configuration section is named `purposes`.

```yaml
textus:
  ai:
    purposes:
      structured-extraction:
        execution-class: standard-work
      web-analysis:
        execution-class: deep-consideration
```

These are Textus AI defaults. Operators do not need to recreate the standard
catalog in every CNCF configuration file.

## Application Purposes

An application maps its domain-specific names under
`application-purposes`. Each mapping names one standard purpose and may later
narrow approved bounds, but it cannot choose or replace the concrete AI
execution settings.

```yaml
textus:
  ai:
    application-purposes:
      sanpomap-scenario-generation:
        purpose: structured-extraction
      sanpomap-location-investigation:
        purpose: structured-extraction
      sanpomap-linear-feature-research:
        purpose: structured-extraction
      sanpomap-route-location-recovery:
        purpose: structured-extraction
      sanpomap-gazetteer-location-recovery:
        purpose: structured-extraction
```

Sanpomap passes one of these names as `AiRunnerRequirement.purpose` through
its `aiPurpose` operation input. GeoResolver receives that same value when the
Sanpomap operation delegates geographic AI work.

## Runtime Profiles

A runtime profile is an operator-selected, Textus AI-owned default set. It is
selected by one configuration value.

```yaml
textus:
  ai:
    profile: codex-cli
```

Examples of supplied runtime profile names are `codex-cli` and `gemini`.
Textus AI defines the concrete defaults for every standard execution class in
each supplied profile. A profile may also contain other runtime defaults such
as provider defaults, tool policy, timeout, retry, concurrency, and limit
settings.

The runtime profile is not an application selector and is not named from an
application purpose. It supplies defaults only; ordinary merged CNCF
configuration may override individual values.

```yaml
textus:
  ai:
    profile: codex-cli
    execution-classes:
      standard-work:
        reasoning-level: high
        max-output-tokens: 1200
```

The selected profile provides the omitted values. The effective execution
class is therefore configurable without requiring an application to carry
provider-specific choices.

## Configuration Placement

- Textus AI ships the standard purpose catalog and supplied runtime profile
  defaults.
- `~/.cncf/config.yaml` normally selects `textus.ai.profile` and contains
  credentials or user-specific runtime overrides.
- A project `.cncf/config.yaml` may hold non-secret execution-class overrides
  and application-purpose mappings.
- Application code and Scenario DSL supply neither profile nor provider/model
  settings.

## Replaced Model-Profile Design

The existing `model-profiles`, `model-profile`, and
`execution-classes.<class>.model-profile` mechanism is not this runtime
profile design and is to be removed. It incorrectly places reusable concrete
model selection inside purpose resolution.

The following transitional names are also replaced by the canonical sections
defined here:

- `generic-purposes` becomes `purposes`.
- `base-purpose` becomes `application-purposes.<name>.purpose`.
- `levels` and `generic-purposes.<name>.level` are removed rather than kept as
  a new-integration configuration path.

The profile portion of
[`purpose-profile-workflow-separation-handoff.md`](2026-07-18-purpose-profile-workflow-separation-handoff.md)
and
[`execution-class-terminology-handoff.md`](2026-07-18-execution-class-terminology-handoff.md)
is superseded by this specification.

## Required Implementation Evidence

1. `textus.ai.profile=codex-cli` resolves all standard execution-class
   defaults without a `model-profiles` configuration block.
2. `textus.ai.profile=gemini` resolves the same classes through Gemini
   defaults.
3. User and project configuration override selected profile defaults in normal
   CNCF precedence order.
4. An application purpose resolves through its configured standard purpose;
   an application caller cannot select a profile or concrete provider setting.
5. Legacy `model-profiles`, `model-profile`, `generic-purposes`,
   `base-purpose`, and `levels` configuration is rejected with a structured
   configuration error.
6. Safe execution facts expose the selected runtime profile and effective
   execution class, never a caller-selected model profile.
