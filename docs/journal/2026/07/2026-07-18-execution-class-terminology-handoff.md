# Handoff: Execution Class Terminology

date=2026-07-18
status=recorded
scope=textus-ai AI request terminology

## Decision

The current request structure composed of `purpose` and `profile` remains
necessary. Rename the `profile` selector to `executionClass` because the
standalone term `profile` is too broad and has repeatedly caused unrelated
concepts to be mixed into the selector.

Use these names for the renamed concept:

- Scala/API field: `executionClass`
- Type name: `AiExecutionClass`
- Configuration key: `execution-class`

## Resolution Model

`purpose` and `executionClass` remain separate first-class selectors. Runtime
resolution uses both to select the effective execution configuration.

```text
purpose + executionClass
  -> engine / model / logical level
```

`purpose` identifies the logical AI purpose. `executionClass` classifies the
requested execution configuration independently of provider-specific names.
Neither selector exposes a concrete engine or model to the caller.

## Naming Rationale

`executionPreset` was considered and rejected. The word `preset` implies that
callers might bypass the preset and directly specify its underlying values.
That is not the intended contract. `executionClass` denotes the supported
classification itself and does not imply an alternate direct-selection route.

The rejected `executionPreset` name must not be introduced into API,
configuration, documentation, or implementation.

## Concept Boundaries

`executionClass` does not define or grant:

- model or provider capabilities;
- CNCF Port or ExtensionPoint capabilities;
- provider tools;
- runtime or application workflows; or
- arbitrary provider-local parameters.

Capability, tool admission, and workflow remain separate concepts. Their
configuration and lifecycle must not be inferred from an execution class.

## Migration Follow-up

Before implementation, audit every existing use of `profile` and classify its
actual role. Rename only the selector that participates with `purpose` in
engine/model/logical-level resolution. Do not mechanically rename unrelated
configuration or metadata concepts without first confirming that they represent
the same selector.

No API, configuration, or implementation migration is performed by this
handoff.
