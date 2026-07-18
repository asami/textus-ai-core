# Phase 3 - Managed Research and Execution Accounting

status=in-progress
started_at=2026-07-18
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Make purpose-selected managed research a bounded, provider-neutral runtime
workflow. The runtime must account for the inputs, external-tool use, and
measured execution that it admits, without taking ownership of application
evidence policy or final domain decisions.

## Scope

- Define the stable execution-fact representation needed for input-token
  estimation, measured provider usage, accounting limitations, and safe
  observability.
- Add purpose-policy configuration and admission for input-token budgets.
- Add purpose-policy configuration for cost budgets, operator-owned rate data,
  and explicit treatment of unavailable usage or pricing facts.
- Define a managed-research workflow for an application purpose inheriting
  `web-research`, with explicit, bounded research, source-comparison, and
  structured-synthesis steps.
- Bind every managed-research step to the resolved purpose policy, admitted
  logical tools, CNCF execution capability, timeout, retry, concurrency, and
  budget scope.
- Add deterministic executable specifications for budget admission, accounting
  limitations, workflow transitions, and no-fallback failure behavior.

## Boundaries

- Application callers select a logical purpose and provide admitted domain
  input. They do not select provider wire tools, model arguments, rate data,
  or workflow commands.
- Textus AI may orchestrate only explicitly configured, read-only research
  steps. It must not grant arbitrary process, network, mutation, or credential
  capabilities to a model.
- ArtScene and other callers retain evidence admission, prompt contracts,
  source authority, result reconciliation, fallback between application
  methods, and final domain decisions.
- Provider-reported usage, provider request identity, and price data remain
  unknown when they are unavailable. The runtime must not fabricate zero usage
  or monetary cost.
- Provider/model fallback remains explicit configuration. Budget rejection,
  unavailable usage, or a failed research step must not silently select a
  different provider, model, tool, or application purpose.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| MR-01 | Execution facts | Stable provider-neutral usage, estimate, accounting, and limitation facts have defined ownership and safe publication. | open |
| MR-02 | Input-budget admission | Purpose policy validates and enforces bounded input-token admission before provider execution. | open |
| MR-03 | Cost accounting and admission | Purpose policy can apply an operator-owned rate schedule and reject over-budget or unaccountable execution explicitly. | open |
| MR-04 | Managed-research workflow | A configured purpose executes bounded research, comparison, and structured-synthesis steps through admitted capabilities. | open |
| MR-05 | Executable specification | Deterministic specifications, operator documentation, and review evidence close the phase. | open |

## Exit Criteria

- An application managed-research purpose inheriting `web-research` resolves
  to a finite workflow and an effective budget scope before any provider or
  external tool invocation.
- Input-token admission uses a documented estimate or measured value and
  exposes its basis and limitations safely.
- Cost admission and accounting use only configured rate data and reported or
  documented estimated usage; missing data remains explicit rather than zero.
- Each workflow step records safe selection, budget, usage, attempt, and
  outcome facts without recording prompts, credentials, raw evidence, or raw
  provider responses.
- A budget, policy, capability, provider, or workflow-step failure produces a
  structured result and never triggers implicit provider or application-method
  fallback.
- Deterministic tests cover successful bounded execution, pre-admission
  rejection, unknown usage or price handling, partial workflow failure, and
  configured no-fallback behavior.

## References

- [CAR Review AI Runtime Design](../notes/car-review-ai-runtime-design.md)
- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [ArtScene AI Fetch Purpose Contract Handoff](../journal/2026/07/2026-07-18-artscene-ai-fetch-purpose-contract-handoff.md)
- [Phase 3 Checklist](phase-3-checklist.md)
