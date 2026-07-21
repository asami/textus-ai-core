# Gemma-First Operational Profile

status=active
version=2026-07-21

## Contract

An operator selects a defaults bundle with `textus.ai.profile`. An application
request selects only its registered `AiRunnerRequirement.purpose`. The profile
resolves the execution class, Gemma primary execution, optional commercial
execution, attempt bounds, and safe evidence fields.

`operational-strategy` is not a provider profile. It identifies the bounded
algorithm used inside the selected runtime profile:

- `structured`
- `tool-grounded`
- `decomposed`
- `validator-repair`
- `candidate-ranking`

The shipped `gemma-first-gemini` and `gemma-first-codex-cli` profiles map all
standard purposes to one of these strategies. Existing profiles retain their
single-provider, no-fallback behavior.

## Operator Configuration

```yaml
textus:
  ai:
    profile: gemma-first-codex-cli
    application-purposes:
      sanpomap-scenario-generation:
        operational-strategy: structured
        acceptance-operation: Sanpomap.Evaluation.evaluateAiCandidate
      sanpomap-location-investigation:
        operational-strategy: tool-grounded
        acceptance-operation: Sanpomap.Evaluation.evaluateAiCandidate
    execution-classes:
      standard-work:
        strategy-max-repairs: 1
        strategy-max-provider-attempts: 2
        rate-schedule: gemma-standard
        fallback-rate-schedule: codex-standard
      deep-consideration:
        strategy-max-repairs: 1
        strategy-max-provider-attempts: 2
        mcp-server-set: admitted-research
```

Application-purpose configuration may refine the high-level strategy and name
an application acceptance Operation. It cannot select a provider, model,
engine, endpoint, server set, credential, or concrete tool. Execution-class
configuration remains operator-owned and may override profile defaults.

When an execution class enables cost accounting with `rate-schedule`, a
two-provider strategy must also define `fallback-rate-schedule`. Admission and
final accounting are recalculated for the provider used by every attempt; a
Gemma rate schedule is never reused for a commercial fallback.

## Attempt Rules

- Gemma is always the first provider for a Gemma-first profile.
- Repairs are limited to `0..3`; provider attempts are limited to `1..2`.
- A configured acceptance Operation returns `accept`, `repair`, `confirm`,
  `escalate`, or `reject` through a Record response.
- `repair` may invoke Gemma again only while the repair bound remains.
- Commercial escalation is admitted only for availability, timeout, malformed
  output, domain validation, evidence, or ambiguity outcomes.
- Authorization, capability, admission, credential-policy, input, and resource
  limit outcomes are terminal and never select the commercial execution.
- A conventional profile never gains fallback behavior implicitly.

## Evidence

Successful strategy responses expose only bounded facts: strategy, attempt
lineage, repair count, escalation reason, final provider, duration, and the
provider-reported usage facts already admitted by the execution-facts contract.
Attempt lineage contains only ordinal, provider identity, stage, and outcome.
Prompt text, candidate text, validation payloads, credentials, endpoints, and
raw provider or tool payloads are prohibited from response metadata and
CallTree attributes.

## Acceptance Operation Shape

Textus AI calls the configured operation through the assembled subsystem with
`purpose`, `candidate`, `candidateFormat`, `repairCount`, and `maxRepairs`.
The operation returns `decision`, canonical bounded `diagnostics`,
`allowedRepairPaths`, and optional `escalationReason`. Textus AI does not
interpret application geography or domain rules itself.
