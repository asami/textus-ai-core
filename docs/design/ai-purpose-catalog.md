# AI Purpose Catalog

status=accepted
scope=textus-ai runtime-owned purpose vocabulary and application-purpose registration
updated_at=2026-07-18

## Decision

Applications register domain application purposes through the CNCF `AiRunner`
Port during component bootstrap. A registration owns a stable name, one default
Textus AI standard purpose, and a provider-neutral default policy. Textus AI
owns the runtime profile and resolves all concrete provider settings.

```text
application Component.Port registration
  -> application purpose catalog
  -> standard purpose
  -> execution class
  -> selected runtime profile defaults
  -> merged CNCF execution-class configuration
  -> registered policy and registered-purpose configuration tuning
  -> provider, mode, engine, model, reasoning, tools, and limits
```

The application caller passes only its registered name as
`AiRunnerRequirement.purpose`. It never selects a runtime profile, provider,
mode, engine, model, execution class, or tools.

## CNCF Registration Contract

The CNCF-owned contract is published on an application's output
`Component.Port`:

```scala
AiRunnerApplicationPurposeRegistration(Vector(
  AiRunnerApplicationPurpose(
    name = "sanpomap-location-investigation",
    defaultStandardPurpose = "web-analysis",
    defaultPolicy = AiRunnerApplicationPurposePolicy(
      maxOutputTokens = Some(240),
      maxConcurrent = Some(1)
    )
  )
))
```

Textus AI publishes an input
`AiRunnerApplicationPurposeRegistrationSocketSet`. CNCF's existing SPI
resolver collects every matching application Port output into that set during
bootstrap. No global registry or configuration-defined application catalog is
used.

## Standard Purposes

| Purpose | Execution class | Intent |
| --- | --- | --- |
| `software-analysis` | `advanced-thinking` | Read broad context, infer behavior and constraints, and assess change impact. |
| `software-design` | `simple-thinking` | Develop boundaries, alternatives, and implementation direction. |
| `software-implementation` | `standard-work` | Produce bounded code and test changes. |
| `command-execution` | `simple-work` | Carry out a bounded command-oriented task. |
| `web-analysis` | `deep-thinking` | Consider a supplied problem using Web information. |
| `structured-extraction` | `standard-work` | Extract or normalize supplied material into a structured result. |

The execution-class identifiers `simple-work`, `standard-work`,
`simple-thinking`, `advanced-thinking`, and `deep-thinking` are explicit built-in direct
purpose aliases. They are catalog entries in Textus AI, not an implicit fallback
for an unregistered application name.

## Runtime Profiles

Textus AI ships `gemma`, `gemini`, `openai`, `codex-cli`, `antigravity-cli`,
`claude-code`, and `anthropic` runtime profiles, plus
`gemma-simple-gemini`, `gemma-simple-codex-cli`, `gemma-work-gemini`, and
`gemma-work-codex-cli`. The `gemma-simple-*` profiles bind only `simple-work`
to local Gemma. The `gemma-work-*` profiles bind `simple-work` to local
`gemma:2b`, `standard-work` to local `gemma3:12b`, and select their named
commercial provider directly for every thinking class. `textus.ai.profile` selects the profile; approved
`textus.ai.execution-classes.<class>.*` settings tune that profile after
ordinary CNCF configuration merge. Provider binding and tools remain
runtime-owned.

`gemini` selects the remote Google API adapter. `antigravity-cli` selects the
separate local managed-process adapter and requires explicit enablement plus an
absolute executable path and an explicit absolute home directory. It does not
inherit ambient `HOME`, and it does not alias or replace the remote Google API.

`openai` selects the direct remote OpenAI API. `codex-cli` selects the local
managed Codex process capability. They share an execution-class model matrix
but remain distinct provider and authentication boundaries.

`anthropic` selects the direct remote Messages API provider. `claude-code`
selects the separate local managed-process provider. They are intentionally not
aliases: the former uses an operator-supplied API key, while the latter uses the
installed CLI's own authentication and execution boundary.

### Codex CLI Defaults

The built-in `codex-cli` profile selects a fixed model and reasoning effort for
each execution class. Callers cannot override either value.

| Execution class | Model | Reasoning effort |
| --- | --- | --- |
| `simple-work` | `gpt-5.6-luna` | `medium` |
| `standard-work` | `gpt-5.6-terra` | `high` |
| `simple-thinking` | `gpt-5.6-sol` | `medium` |
| `advanced-thinking` | `gpt-5.6-sol` | `high` |
| `deep-thinking` | `gpt-5.6-sol` | `xhigh` |

## Registration Defaults And Tuning

Registration defaults apply without an
`textus.ai.application-purposes.<name>.*` configuration block. Configuration is
only a tuning layer for an already registered name:

```yaml
textus:
  ai:
    profile: gemini
    application-purposes:
      sanpomap-location-investigation:
        max-output-tokens: 180
        timeout-seconds: 45
```

The allowed tuning fields are bounds, rate schedule, timeout, retry,
concurrency, output-schema identity, and prompt-contract identity. They may
only narrow the registered effective policy. Configuration cannot define a
standard purpose, register an application name, or select provider/model/tools.

## Validation And Attribution

Before provider execution, Textus AI rejects:

1. an unregistered application purpose;
2. duplicate registrations;
3. a registration whose default standard purpose is unknown;
4. configuration for a name that has no registration;
5. provider/model/tool or execution-class selection from either caller or
   application-purpose tuning; and
6. a policy that broadens its runtime or registered default bound.

Response metadata and CallTree records safely include
`ai.policy.application_purpose`, `ai.policy.effective_standard_purpose`,
`ai.policy.runtime_profile`, and `ai.policy.effective_execution_class`. They do
not include credentials, prompts, raw provider payloads, or a caller-selected
provider identity.
