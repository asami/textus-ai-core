# Execution Class Selector Audit

status=complete
scope=textus-ai pre-implementation terminology audit
updated_at=2026-07-18

## Decision Input

The confirmed target contract has two independent caller selectors:

```text
purpose + executionClass
  -> engine / model / logical level
```

`executionClass` is the Scala/API name, `AiExecutionClass` is the type name,
and `execution-class` is the configuration key. `executionPreset` is rejected
terminology and must not be introduced.

## Audit Results

| Existing concept | Current role | Migration decision |
| --- | --- | --- |
| `AiRunnerRequirement` | CNCF caller contract with `purpose`, but no `profile` selector. | Add the new `executionClass` selector in CNCF; Textus AI cannot rename a field that does not yet exist. |
| `AiModelProfile` / `model-profile` | Operator-managed provider/model configuration. | Retain. It is not the caller selector. |
| `AiPurposeProfile` | Textus AI configuration mapping and policy record. | Retain pending EC-01 design; do not mechanically rename it. |
| `CodexExecutionProfile` | Codex adapter's fixed managed-process capability manifest. | Retain. It is provider-internal capability configuration. |
| `ai.policy.model_profile` metadata | Safe execution fact. | Retain. It is not a selector. |
| `simple-work` et al. as implicit purposes | Current Textus compatibility resolution through `levels.<name>`. | Migrate new integrations to `executionClass`; retain only as a compatibility path until EC-01 defines removal or deprecation. |

## Required Migration Boundary

The new selector is not a model-profile alias. It does not grant a provider,
tool, Port, ExtensionPoint, managed-process capability, workflow, or
provider-local parameter. Textus AI resolves it with `purpose` to an approved
operator configuration; normal admission still validates every requested
capability.

The standard initial execution-class values are:

- `simple-work`
- `standard-work`
- `simple-thinking`
- `advanced-thinking`
- `deep-thinking`

The standard semantic purposes remain independent values, including
`software-analysis`, `software-design`, `software-implementation`,
`command-execution`, `web-analysis`, and `structured-extraction`.

## Follow-up

The current contract defines the five `AiExecutionClass` values,
`execution-class` configuration placement and precedence, purpose-plus-class
conflict behavior, metadata publication, and deterministic specifications.
The migration intentionally provides no API, configuration, or runtime
compatibility aliases for the replaced `*consideration` identifiers.
