# AI Execution Class Resolution

status=accepted
scope=textus-ai execution-class selection
updated_at=2026-07-18

## Contract

`AiRunnerRequirement.executionClass` is an optional caller selector used with
`purpose`. It is a logical execution intent, never a provider, model,
reasoning setting, tool capability, workflow, or provider-local argument.

Textus AI resolves the effective execution class before provider execution:

```text
purpose + executionClass
  -> approved model-profile
  -> provider, mode, engine, model, reasoning, tools, and limits
```

The effective requirement retains the resolved `executionClass`; normalized
response and CallTree metadata publish it as `ai.execution.execution_class`.

## Configuration

The canonical configuration binds each known CNCF execution class to an
operator-managed model profile:

```yaml
textus:
  ai:
    execution-classes:
      standard-work:
        model-profile: openai-standard-work
    generic-purposes:
      software-implementation:
        execution-class: standard-work
```

`textus.ai.execution-classes.<execution-class>.model-profile` is the
canonical key. `textus.ai.generic-purposes.<purpose>.execution-class` supplies
the generic purpose default and constraint. Application purposes inherit that
constraint through `base-purpose`.

The previous `textus.ai.levels.<level>.model-profile` and
`textus.ai.generic-purposes.<purpose>.level` keys remain read-only migration
fallbacks. Their values must name a known `AiExecutionClass`. New configuration
must use `execution-classes` and `execution-class`.

## Resolution And Failure Rules

1. A request-level `executionClass` has priority when the selected purpose
   does not configure a class.
2. A configured purpose class fills an absent request-level selector.
3. When both selectors are present, their values must match. A mismatch is an
   incompatible purpose/execution-class failure before provider selection.
4. The effective class must have a configured canonical binding or a valid
   legacy `levels` fallback. Missing bindings fail structurally.
5. An unknown class value in configuration fails structurally. It never falls
   back to a level, model profile, provider, or another class.
6. A request with only `executionClass` may resolve through the configured
   class binding. A request that requires a purpose still fails when purpose is
   absent.
7. The compatibility path that treats a level name as an implicit purpose is
   retained only for existing callers. New callers use `executionClass`.

No rule in this contract grants a tool, process, network, mutation, credential,
or workflow capability. Normal provider and purpose admission remains in force.

## Executable Evidence

`TextusAiRunnerSpec` covers configured selection, purpose defaulting,
incompatible selection, unconfigured bindings, and legacy fallback.
`AiExecutionFactsSpec` covers metadata publication of the effective class.
