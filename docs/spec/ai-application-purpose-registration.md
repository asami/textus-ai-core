# AI Application-Purpose Registration Specification

status=accepted
scope=bootstrap-time application-purpose registration and runtime resolution
updated_at=2026-07-18

## Contract

1. An application publishes `AiRunnerApplicationPurposeRegistration` through
   its CNCF `Component.Port`.
2. Textus AI consumes registrations through
   `AiRunnerApplicationPurposeRegistrationSocketSet` during bootstrap.
3. Each entry has a unique application-purpose name, a default Textus standard
   purpose, and an optional provider-neutral default policy.
4. `textus.ai.application-purposes.<name>.*` tunes a registered entry only; it
   cannot create the entry or supply its standard purpose.
5. A caller supplies only `AiRunnerRequirement.purpose`; concrete selection
   fields remain forbidden.

## Resolution

For a registered application purpose, Textus AI resolves the registration's
standard purpose through the selected runtime profile and applies policy in
this order:

```text
runtime execution-class policy
  -> application registration default policy
  -> registered-name configuration tuning
```

Each later layer may narrow but not broaden the previous effective policy.
Registration defaults therefore work when no application-purpose configuration
is present.

## Failure Rules

The following fail structurally before a provider or managed process is
invoked:

- application name is not registered;
- registrations contain the same normalized name more than once;
- a registration selects an unsupported standard purpose;
- configuration names an unregistered application purpose;
- caller, tuning, or registration attempts concrete provider/model/tool or
  execution-class selection; or
- an effective policy broadens an inherited bound.

## Executable Evidence

- CNCF `SpiSpec` proves that an application Port registration is collected into
  the AI registration socket set during bootstrap.
- `AiRuntimeProfileSpec` proves registration defaults, configuration tuning,
  duplicate registration rejection, unknown standard-purpose rejection, and
  configuration-only rejection.
- `ComponentFactorySpec` proves that a bootstrap-registered default concurrency
  policy becomes a component-scoped admission boundary.
- `TextusAiRunnerSpec` proves no-provider-call failure boundaries and safe
  CallTree attribution for application purpose, standard purpose, profile, and
  execution class.
