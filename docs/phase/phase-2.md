# Phase 2 - Purpose Policy Runtime

Status: complete
Started: 2026-07-18
Strategy: [Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Goal

Make `AiRunnerRequirement.purpose` a strict, provider-neutral runtime policy
boundary. An application selects an approved AI behavior by purpose; Textus AI
resolves, validates, enforces, and records the associated provider policy.

The ArtScene exhibition-fetch purposes are the first integration driver:

- `artscene-exhibition-extraction-from-source`
- `artscene-exhibition-web-research`
- `artscene-exhibition-managed-research`

This phase does not change ArtScene callers. Its output is the runtime contract
that an ArtScene integration phase will consume.

## Scope

- Define a provider-neutral strict-purpose intent in the CNCF AI runner
  contract.
- Resolve a purpose profile to an explicit provider, mode, engine, model,
  logical tools, and bounded execution policy.
- Define reusable logical AI levels for deep deliberation, standard
  deliberation, standard work, and simple work. Operator configuration binds
  those levels to approved provider/model/reasoning profiles.
- Define reusable generic purposes such as Web research, specification
  analysis, implementation work, and structured extraction. Application
  purposes may explicitly inherit a generic purpose only when they add
  application-specific policy.
- Reject a required purpose with no valid profile. Do not fall back implicitly
  to a provider, model, or different purpose.
- Validate provider and logical-tool compatibility before provider execution.
- Map purpose-resolved Codex CLI Web-tool requests to a finite, admitted Codex
  execution profile; never treat prompt text as a capability grant.
- Carry effective purpose policy through `TextusAiRunner`, provider adapters,
  response metadata, and safe CallTree attributes.
- Define and enforce supported request bounds: maximum output tokens, timeout,
  retry, and concurrency.
- Define configuration and validation boundaries for structured-output and
  prompt policies, without embedding an application record schema in the
  runtime.
- Add deterministic executable specifications for resolution, admission, and
  no-fallback behavior.

## Non-goals

- Implement ArtScene fetch-method mapping, prompt text, result schema, or
  caller fallback. Those belong to the ArtScene integration phase.
- Implement managed-research orchestration, cost admission, cost accounting,
  or input-token budgeting. Those belong to Phase 3.
- Add provider-specific public operations or require provider credentials in
  application code.
- Let application callers supply arbitrary model names, reasoning levels, or
  provider command arguments as request-level overrides.
- Silently downgrade unsupported tools or an invalid purpose policy to plain
  generation.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| PP-01 | Strict purpose contract | CNCF exposes a provider-neutral way to require purpose resolution. | complete |
| PP-02 | Profile policy resolution | Purpose profiles resolve the selected provider/model/tools and bounded policy, with configuration validation. | complete |
| PP-03 | Admission and observability | Runtime rejects invalid selections before execution and records effective safe facts in CallTree/metadata. | complete |
| PP-04 | Bounded execution | Supported maximum-output, timeout, retry, and concurrency policies are enforced consistently. | complete |
| PP-05 | Executable specification | Deterministic tests and operator documentation close the phase. | complete |
| PP-06 | Codex CLI purpose Web tools | Purpose profiles safely select admitted Codex model/reasoning/Web-tool capability without exposing CLI arguments to callers. | complete |
| PP-07 | Generic purpose and level policy | Generic purposes select approved logical AI levels; application purposes explicitly inherit and narrow them. | complete |

## Exit Criteria

- A required purpose without a valid profile produces a structured failure
  before any provider invocation.
- A resolved purpose never selects an implicit fallback provider, model, or
  purpose.
- A caller can select a generic purpose without naming a provider, model, or
  reasoning setting. Application-specific purposes explicitly inherit a generic
  purpose and may narrow, but never broaden, its approved policy.
- A requested logical tool unsupported by the selected provider produces a
  structured failure.
- A Codex Web-research purpose maps only approved profile policy to an admitted
  local execution capability; unsupported Codex Web tools fail structurally and
  never trigger an implicit local-navigation fallback.
- The effective purpose, selected provider/model, logical tools, and bounded
  execution facts are observable without exposing secrets or prompts.
- Maximum-output, timeout, retry, and concurrency behavior is covered by
  deterministic tests for each supported enforcement point.
- Existing requests with no required purpose retain their current compatible
  behavior.
- The ArtScene integration can select its three purposes without provider
  names, model identifiers, or credential handling in application code.

## References

- [ArtScene AI Fetch Purpose Contract Handoff](../journal/2026/07/2026-07-18-artscene-ai-fetch-purpose-contract-handoff.md)
- [Codex CLI Purpose Policy and Web Tools Handoff](../journal/2026/07/2026-07-18-codex-cli-purpose-web-tools-handoff.md)
- [Phase 2 Checklist](phase-2-checklist.md)
