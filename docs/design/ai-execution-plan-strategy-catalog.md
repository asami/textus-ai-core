# AI Execution-Plan Strategy Catalog

status=accepted
scope=textus-ai phase-7 SP-05 observation identity
updated_at=2026-07-23

## Decision

An Experiment arm identifies an execution plan, not merely a provider. The
plan reference and strategy reference are immutable artifact URIs supplied by
the application when it records an observation. Textus AI continues to expose
the effective runtime profile, execution class, provider attempt count, and
operational strategy as safe facts.

The initial Sanpomap strategy identities are:

| Strategy reference suffix | Shape | Intended evidence |
| --- | --- | --- |
| `one-shot-semantic-v1` | One admitted AI call over already bounded evidence | Baseline call, token, latency, and acceptance data. |
| `staged-question-list-v1` | Bounded question steps followed by semantic synthesis | Repeated context, total calls/tokens, and acceptance compared with one-shot. |
| `local-gemma-work-v1` | One local `gemma3:12b` standard-work step | Local availability and quality without fabricated per-call API cost. |
| `managed-cli-thinking-v1` | One admitted Codex or Antigravity CLI thinking step | Subscription plan/quota facts, latency, and acceptance. |
| `provider-grounded-research-v1` | One provider-native Web research step | Provider-standard tool calls/context and API/plan cost state. |
| `cncf-evidence-plus-synthesis-v1` | CNCF Operation/MCP evidence preparation followed by synthesis | Separate CNCF evidence counts and AI synthesis cost. |

The plan name never contains a credential, endpoint, raw prompt, model output,
or account identity. The detailed purpose remains a separate safe identity.

## Measurement Rules

- Record every AI step's response facts and one application workflow summary.
- Preserve provider-standard tool calls separately from CNCF Operation/MCP
  evidence calls.
- Do not infer that staged prompts cost less: compare total calls, repeated
  input, output, latency, acceptance, and any measured or estimated cost.
- A provider failure records its selected route outcome. It does not select an
  alternative route unless an independently admitted application execution
  plan explicitly does so.

## Current Contract

`AiExecutionObservation` already captures effective purpose/profile/class,
provider calls, tool/CNCF counts, token/cost states, and the runtime operational
strategy. Sanpomap `recordAiExecutionObservation` requires sanitized execution
plan and strategy artifact references. A future multi-step driver adds the
whole-workflow aggregate without changing individual AI response facts.
