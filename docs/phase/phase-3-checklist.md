# Phase 3 Checklist - Managed Research and Execution Accounting

status=in-progress
phase=[Phase 3 - Managed Research and Execution Accounting](phase-3.md)

## Stage MR-01 - Execution Facts

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when the representation, ownership, safe
  publication policy, and deterministic evidence are available.

- [ ] Define provider-neutral facts for estimated and measured input, output,
  cached-input, and reasoning usage, including unavailable-value semantics.
- [ ] Define safe execution and accounting identities: policy snapshot, rate
  schedule identity, provider request identity, and accounting limitation.
- [ ] Specify response-metadata, CallTree-only, and prohibited fact placement.
- [ ] Add deterministic fixtures for reported, estimated, and unavailable facts.

## Stage MR-02 - Input-Budget Admission

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when input admission occurs before execution and
  every unsupported estimate path returns a structured outcome.

- [ ] Add validated purpose-policy configuration for maximum input tokens.
- [ ] Define provider-neutral input estimation and its accepted error bounds.
- [ ] Reject requests exceeding the effective input budget before provider or
  external-tool invocation.
- [ ] Record the selected budget basis and limitation without exposing input.

## Stage MR-03 - Cost Accounting and Admission

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when all budget decisions can be attributed to
  configured rate data and observed or documented estimated usage.

- [ ] Define operator-owned rate schedule configuration and stable identity.
- [ ] Add purpose-policy cost budgets and pre-execution admission semantics.
- [ ] Account for actual provider usage when it is reported; retain explicit
  unknown or estimate limitations otherwise.
- [ ] Reject missing accounting prerequisites and over-budget execution
  structurally, without implicit fallback.

## Stage MR-04 - Managed-Research Workflow

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when every workflow transition is bounded by the
  resolved purpose policy and deterministic specifications prove its failures.

- [ ] Define the provider-neutral managed-research workflow contract and
  finite step vocabulary for an application purpose inheriting `web-research`.
- [ ] Bind research, source-comparison, and structured-synthesis steps to
  purpose policy, logical tools, and CNCF capabilities.
- [ ] Scope timeout, retry, concurrency, input budget, and cost budget across
  the entire workflow and each step.
- [ ] Return an attributable partial-workflow failure without raw evidence or
  implicit provider, model, tool, or application-method fallback.

## Stage MR-05 - Executable Specification and Closure

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Close only when every prior checklist item has deterministic
  evidence, operator documentation, review, and validation evidence.

- [ ] Add deterministic specifications for admission success, input-budget
  rejection, unknown usage and price, cost-budget rejection, and accounting
  summaries.
- [ ] Add deterministic specifications for managed-research success, bounded
  partial failure, cancellation, and configured no-fallback behavior.
- [ ] Document operator configuration and safe observability for budgets,
  rates, and workflow facts.
- [ ] Run review, fix actionable findings, validate the phase, and record
  closure evidence.
