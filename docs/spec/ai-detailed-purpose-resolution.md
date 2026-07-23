# AI Detailed Purpose Resolution

status=accepted
scope=textus-ai phase-7 SP-03 executable resolution contract
updated_at=2026-07-23

## Contract

Textus AI resolves a caller-selected application purpose, or one shipped
standard purpose alias, in this order:

```text
registered application purpose
  -> detailed standard purpose
  -> catalog execution class and required logical tools
  -> selected runtime-profile purpose binding
  -> selected runtime-profile execution-class default
  -> provider admission
```

The caller cannot provide provider, mode, engine, model, execution class, or
tools. An application-purpose configuration cannot select those fields either.

## Detailed Catalog

| Purpose | Execution class | Required provider-standard tool |
| --- | --- | --- |
| `structured-extraction` | `standard-work` | none |
| `grounded-research` | `advanced-thinking` | `web_search` |
| `evidence-synthesis` | `standard-work` | none |
| `candidate-proposal` | `standard-work` | none |
| `candidate-ranking` | `simple-thinking` | none |
| `constrained-planning` | `advanced-thinking` | none |

`url_context` is an optional logical provider-standard capability. It may be
admitted by a runtime binding when the selected provider supports it; it is not
a mandatory substitute for `web_search`.

## Runtime Binding

`textus.ai.purpose-bindings.<standard-purpose>.*` can set `provider`, `mode`,
`engine`, `model`, `reasoning-level`, and `tools`. The binding takes precedence
over `textus.ai.execution-classes.<execution-class>.*`; the detailed catalog
still owns the execution class and required tool set.

When a binding selects a managed CLI provider, Textus AI publishes a distinct
purpose-named managed execution profile. This preserves the runtime-owned
model, reasoning, and tool selection without colliding with the CLI profile
for another purpose in the same execution class.

Unknown binding purposes and unsupported binding fields are configuration
errors. Unknown logical tools in either a purpose binding or an execution-class
binding are configuration errors. A binding of `grounded-research` to a provider
without `web_search` fails provider admission before the provider service starts.

## Executable Evidence

`AiRuntimeProfileSpec` proves:

- the detailed catalog resolves each purpose to its fixed execution class;
- `grounded-research` adds `web_search`, while the supplied-evidence purposes
  add no provider-standard tools;
- an `evidence-synthesis` purpose binding overrides the shared
  `standard-work` class binding without changing `candidate-proposal`; and
- a Codex CLI purpose binding publishes a distinct managed execution profile;
- unrecognized purpose bindings and unknown binding tools are rejected
  structurally.

`TextusAiRunnerSpec` proves a Gemma `grounded-research` request is rejected by
provider admission before any Ollama request. `AiProviderAdmissionSpec` proves
the same logical tool contract is admitted only by supported provider paths.

Run the deterministic evidence with:

```bash
sbt -J-Xmx3G --batch \
  'testOnly org.simplemodeling.textus.ai.runtime.AiRuntimeProfileSpec org.simplemodeling.textus.ai.runtime.TextusAiRunnerSpec'
```
