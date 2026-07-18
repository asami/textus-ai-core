# Phase 3 - Profile Binding and Execution Accounting

status=in-progress
started_at=2026-07-18
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Complete the separation of caller-selected purpose and operator-managed
execution profile, then make input-token and cost decisions attributable. The
runtime must not take ownership of application evidence policy, domain
fallback, or an undecided multi-step workflow.

## Scope

- Define purpose-to-profile binding that resolves the logical level,
  provider/model policy, admitted tools, and execution bounds without exposing
  profiles to ordinary application callers.
- Define the stable execution-fact representation needed for input-token
  estimation, measured provider usage, accounting limitations, and safe
  observability.
- Add profile-policy configuration and admission for input-token budgets.
- Add profile-policy configuration for cost budgets, operator-owned rate data,
  and explicit treatment of unavailable usage or pricing facts.
- Add deterministic executable specifications for profile binding, budget
  admission, accounting limitations, and no-fallback failure behavior.
- Decide, but do not implement, whether a future runtime workflow is needed;
  record the boundary required before such work can enter a later phase.

## Boundaries

- Application callers select a logical purpose and provide admitted domain
  input. They do not select provider wire tools, model arguments, rate data,
  or workflow commands.
- A profile may admit only explicitly configured capabilities. It must not
  grant arbitrary process, network, mutation, or credential capabilities to a
  model.
- ArtScene and other callers retain evidence admission, prompt contracts,
  source authority, result reconciliation, fallback between application
  methods, and final domain decisions.
- Provider-reported usage, provider request identity, and price data remain
  unknown when they are unavailable. The runtime must not fabricate zero usage
  or monetary cost.
- Provider/model fallback remains explicit configuration. Budget rejection or
  unavailable usage must not silently select a different provider, model, tool,
  or application purpose.
- Runtime workflow is not in scope. Application orchestration, evidence
  admission, source comparison, and domain fallback remain application-owned
  until a later design explicitly changes that boundary.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| PB-01 | Profile binding | Purpose resolves to an operator-managed profile; the profile resolves level, model, tools, and bounds. | open |
| EA-01 | Execution facts | Stable provider-neutral usage, estimate, accounting, and limitation facts have defined ownership and safe publication. | open |
| EA-02 | Input-budget admission | Profile policy validates and enforces bounded input-token admission before provider execution. | open |
| EA-03 | Cost accounting and admission | Profile policy can apply an operator-owned rate schedule and reject over-budget or unaccountable execution explicitly. | open |
| WB-01 | Workflow boundary | A separate decision records whether future runtime workflow support is needed and what contract it would require. | open |
| ES-01 | Executable specification | Deterministic specifications, operator documentation, and review evidence close the phase. | open |

## Exit Criteria

- A caller-selected purpose resolves to an operator-managed profile and
  effective budget scope before any provider or external tool invocation.
- Input-token admission uses a documented estimate or measured value and
  exposes its basis and limitations safely.
- Cost admission and accounting use only configured rate data and reported or
  documented estimated usage; missing data remains explicit rather than zero.
- Each execution records safe profile selection, budget, usage, attempt, and
  outcome facts without recording prompts, credentials, raw evidence, or raw
  provider responses.
- A budget, profile, capability, or provider failure produces a structured
  result and never triggers implicit provider or application-method fallback.
- The phase records a workflow-boundary decision rather than implementing a
  multi-step runtime procedure.
- Deterministic tests cover successful bounded execution, profile rejection,
  pre-admission rejection, unknown usage or price handling, and configured
  no-fallback behavior.

## References

- [CAR Review AI Runtime Design](../notes/car-review-ai-runtime-design.md)
- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [Purpose, Profile, and Workflow Separation Handoff](../journal/2026/07/2026-07-18-purpose-profile-workflow-separation-handoff.md)
- [ArtScene AI Fetch Purpose Contract Handoff](../journal/2026/07/2026-07-18-artscene-ai-fetch-purpose-contract-handoff.md)
- [Phase 3 Checklist](phase-3-checklist.md)
