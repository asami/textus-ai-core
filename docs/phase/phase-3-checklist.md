# Phase 3 Checklist - Execution Class and Accounting

status=in-progress
phase=[Phase 3 - Execution Class and Accounting](phase-3.md)

## Stage EC-01 - Execution Class

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when the CNCF selector, Textus AI resolution,
  migration, and deterministic evidence are available.

- [x] Define CNCF `AiExecutionClass` and
  `AiRunnerRequirement.executionClass` without exposing engine or model names.
- [x] Define `execution-class` configuration precedence with purpose and
  operator model-profile configuration.
- [x] Migrate implicit level-purpose aliases and transitional generic-purpose
  level selection without weakening current no-fallback guarantees.
- [x] Add deterministic specifications for configured, unconfigured, and
  incompatible purpose/execution-class selection.

## Stage EA-01 - Execution Facts

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when the representation, ownership, safe
  publication policy, and deterministic evidence are available.

- [x] Define provider-neutral facts for estimated and measured input, output,
  cached-input, and reasoning usage, including unavailable-value semantics.
- [x] Define safe execution and accounting identities: policy snapshot, rate
  schedule identity, provider request identity, and accounting limitation.
- [x] Specify response-metadata, CallTree-only, and prohibited fact placement.
- [x] Add deterministic fixtures for reported, estimated, and unavailable facts.

## Stage EA-02 - Input-Budget Admission

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when input admission occurs before execution and
  every unsupported estimate path returns a structured outcome.

- [x] Add validated execution-class policy configuration for maximum input
  tokens.
- [x] Define provider-neutral input estimation and its accepted error bounds.
- [x] Reject requests exceeding the effective input budget before provider or
  external-tool invocation.
- [x] Record the selected budget basis and limitation without exposing input.

## Stage EA-03 - Cost Accounting and Admission

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when all budget decisions can be attributed to
  configured rate data and observed or documented estimated usage.

- [x] Define operator-owned rate schedule configuration and stable identity.
- [x] Add execution-class policy cost budgets and pre-execution admission
  semantics.
- [x] Account for actual provider usage when it is reported; retain explicit
  unknown or estimate limitations otherwise.
- [x] Reject missing accounting prerequisites and over-budget execution
  structurally, without implicit fallback.

## Stage WB-01 - Workflow Boundary

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Mark DONE only after a design decision records whether runtime
  workflow support is needed and a later phase owns any implementation.

- [ ] Determine whether any caller needs a Textus AI runtime workflow rather
  than application orchestration.
- [ ] If needed, define the required public contract, ownership, admission,
  lifecycle, budget scope, failure semantics, and executable specification.
- [ ] Record workflow implementation as a later phase; do not implement it in
  this phase.

## Stage ES-01 - Executable Specification and Closure

Stage Status:
- Current status: OPEN
- Owner: Textus AI maintainers
- Update rule: Close only when every prior checklist item has deterministic
  evidence, operator documentation, review, and validation evidence.

- [ ] Add deterministic specifications for admission success, input-budget
  rejection, unknown usage and price, cost-budget rejection, and accounting
  summaries.
- [ ] Add deterministic specifications for execution-class binding and
  configured no-fallback behavior.
- [ ] Document configuration and safe observability for execution classes,
  budgets, rates, and workflow-boundary facts.
- [ ] Run review, fix actionable findings, validate the phase, and record
  closure evidence.
