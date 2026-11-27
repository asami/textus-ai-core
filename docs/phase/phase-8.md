# Phase 8 - Persisted Comparison Replay Scheduling

status=complete
started_at=2026-07-23
completed_at=2026-07-24
scope=Textus AI operational evidence ecosystem

## Goal

Replace the Phase 7 stateless comparison-replay admission gate with a durable,
auditable scheduler contract. The contract must reserve bounded replay capacity
before a comparison route runs and retain its terminal disposition afterward.

## Ownership

| Repository | Responsibility |
| --- | --- |
| `textus-experiment` | Owns persisted replay reservations, capacity accounting, lifecycle transitions, and audit queries. |
| `textus-sanpomap` | Uses the assembled Experiment SPI to reserve, consume, cancel, and inspect replay work for its evaluation flows. |
| `textus-ai` | Continues to execute the selected provider route and publish safe observations. It neither schedules replay nor selects comparison arms. |
| CNCF | Preserves context for assembled component calls. |

## Scope

- Reserve an explicitly bounded replay count and budget against one running
  Experiment run.
- Keep reservation state durable and idempotent by an application-supplied,
  opaque reservation key.
- Consume a reservation exactly once after its accepted observation is
  persisted; support cancellation before consumption.
- Expire outstanding reservations using the component clock and configured
  non-negative lifetime; zero expires before provider execution.
- Enforce a configured per-run count and budget envelope across both active
  and consumed reservations, so parallel callers cannot overcommit it.
- Record only opaque references and scheduling facts. Provider, model, tool,
  endpoint, credential, prompt, and response data are excluded.

## Non-Goals

- Provider execution, provider fallback, automatic fan-out, or billing.
- Broad provider/cost measurement, optimization, recommendation, or ranking.
- A new public Textus AI operation or caller-owned provider selection.
- Cross-run, account-wide, or subscription cost accounting.

## Work Items

| ID | Work item | Status |
| --- | --- | --- |
| P8-RS-01 | Persisted reservation lifecycle through Experiment and assembled Sanpomap SPI | complete |
| P8-RS-02 | Operational replay scheduler integration and recovery procedure | complete |
| P8-RS-03 | Accumulated-evidence comparison and measurement policy | complete |

## Current Evidence

- `scripts/check-phase8-comparison-replay-scheduler.sh` builds current local
  CARs, starts and stops an isolated runtime, verifies the provider-backed
  reservation reaches `Consumed`, and verifies a no-provider reservation is
  explicitly recovered as `Cancelled`.
- The same assembled path completes the provider-backed Experiment run and
  verifies its immutable `summarizeExperimentRun` aggregate. The accepted
  policy defines that completed run as the comparison boundary without adding
  provider ranking, pricing, or cross-run normalization.
- [Phase 8 closure](phase-8-closure.md) maps every closure criterion to its
  current deterministic, assembled, and CAR-lint evidence.

## Closure Criteria

- An Experiment reservation is durable, idempotent, and transition-safe.
- Capacity admission includes outstanding and consumed reservations within the
  same run budget envelope.
- Expired and cancelled reservations no longer reserve capacity; consumed
  reservations remain auditable and counted.
- Sanpomap has executable assembled-SPI evidence and retains no independent
  reservation state.
- A completed provider-backed run has executable evidence for its deterministic
  accumulated outcome summary, and the comparison policy preserves opaque
  measurement evidence without inferring a score or cost.
- No Textus AI provider/model/tool selection enters the scheduler contract.
- Deterministic component tests, relevant assembled tests, and CAR lint pass
  without a new failure.

## AI Audit correlation direction

Experiment scheduling and observation remain implementation-neutral. An Experiment arm does not require special behavior from Textus AI. CNCF ExecutionContext carries experiment/run/arm correlation through the assembled call path.

If the selected arm executes AI, CNCF AI Audit records its normal AI Interaction and automatically captures that inherited Experiment correlation. The Experiment Observation may reference AIInteractionId/evidenceRef. If the arm is deterministic or otherwise non-AI, no AI Audit record is created solely because it is an Experiment.

Experiment remains authoritative for comparison arms, runs, observations, reservations and summaries. AI Audit remains authoritative for detailed AI interaction evidence.
