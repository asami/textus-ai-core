# Phase 8 - Persisted Comparison Replay Scheduling

status=in-progress
started_at=2026-07-23
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
| P8-RS-03 | Accumulated-evidence comparison and measurement policy | pending |

## Current Evidence

- `scripts/check-phase8-comparison-replay-scheduler.sh` builds current local
  CARs, starts and stops an isolated runtime, verifies the provider-backed
  reservation reaches `Consumed`, and verifies a no-provider reservation is
  explicitly recovered as `Cancelled`.

## Closure Criteria

- An Experiment reservation is durable, idempotent, and transition-safe.
- Capacity admission includes outstanding and consumed reservations within the
  same run budget envelope.
- Expired and cancelled reservations no longer reserve capacity; consumed
  reservations remain auditable and counted.
- Sanpomap has executable assembled-SPI evidence and retains no independent
  reservation state.
- No Textus AI provider/model/tool selection enters the scheduler contract.
- Deterministic component tests, relevant assembled tests, and CAR lint pass
  without a new failure.
