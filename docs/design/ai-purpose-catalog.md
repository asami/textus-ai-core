# AI Purpose Catalog

status=accepted
scope=textus-ai runtime-owned purpose vocabulary and application-purpose mapping
updated_at=2026-07-18

## Decision

An application selects an `AiRunnerRequirement.purpose` only. Textus AI then
resolves the effective provider execution through this fixed sequence:

```text
application purpose
  -> standard purpose
  -> execution class
  -> selected runtime profile defaults
  -> merged CNCF configuration overrides
  -> provider, mode, engine, model, reasoning, tools, and limits
```

The caller never selects a runtime profile, provider, mode, engine, model,
execution class, or tool set. Those are runtime-owned settings. A direct
standard purpose and an execution-class name are accepted as implicit
purposes for runtime and operator use; application integrations should use a
descriptive application purpose.

## Standard Purposes

| Purpose | Execution class | Intent |
| --- | --- | --- |
| `software-analysis` | `standard-consideration` | Understand existing software and assess change impact. |
| `software-design` | `deep-consideration` | Develop boundaries, alternatives, and implementation direction. |
| `software-implementation` | `standard-work` | Produce bounded code and test changes. |
| `command-execution` | `simple-work` | Carry out a bounded command-oriented task. |
| `web-analysis` | `deep-consideration` | Consider a supplied problem using Web information. |
| `structured-extraction` | `standard-work` | Extract or normalize supplied material into a structured result. |

The four execution classes are `simple-work`, `standard-work`,
`standard-consideration`, and `deep-consideration`. They indicate the expected
work/consideration level, not a provider capability or workflow grant.

## Runtime Profiles

Textus AI ships two runtime profiles:

| Profile | Simple / standard work | Standard / deep consideration |
| --- | --- | --- |
| `codex-cli` | Codex CLI, `gpt-5-codex`, `minimal` / `low` reasoning | Codex CLI, `gpt-5-codex`, `high` / `xhigh` reasoning |
| `gemini` | Gemini, `gemini-2.5-flash` | Gemini, `gemini-2.5-pro` |

`textus.ai.profile` selects one profile. CNCF configuration may override an
execution class's provider binding, model, reasoning level, tools, and policy
bounds. Such overrides remain operator-managed.

## Application Purposes

An application maps its domain name to one standard purpose and may narrow the
inherited policy:

```yaml
textus:
  ai:
    profile: gemini
    execution-classes:
      deep-consideration:
        max-output-tokens: 480
    application-purposes:
      artscene-exhibition-web-research:
        purpose: web-analysis
        max-output-tokens: 240
        max-concurrent: 1
```

Allowed application-purpose fields are bounds, rate schedule, timeout, retry,
concurrency, output-schema identity, and prompt-contract identity. Provider,
model, execution class, reasoning, and tools are rejected. A broader bound is
rejected before provider execution.

## Migration Rules

`model-profiles`, `model-profile`, `generic-purposes`, `base-purpose`,
`levels`, and their aliases are removed. They fail structurally rather than
acting as compatibility fallbacks. Standard purposes are runtime-owned and may
not be configured under `textus.ai.purposes`.

## Growth Rule

Add a standard purpose only for a recurring provider-neutral intent that needs
a distinct execution-class baseline. Provider, model, CLI, transport, and
one-off application workflow names are not standard purposes.
