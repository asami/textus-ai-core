# Phase 8 Closure - Persisted Comparison Replay Scheduling

status=complete
completed_at=2026-07-24
phase=[Phase 8](phase-8.md)

## Outcome

Phase 8 replaces the former application-local replay gate with an
Experiment-owned scheduler. It reserves bounded replay capacity before an
application route runs, retains a terminal audit disposition, and keeps
provider selection outside the scheduling contract. Sanpomap uses the
assembled Experiment Port rather than maintaining a local reservation state.

## Closure Evidence

| Criterion | Evidence |
| --- | --- |
| Durable, idempotent, transition-safe reservation | Experiment `ComponentFactorySpec` exercises immutable reservation-key retry, terminal-state rejection, and single consumption. |
| Per-run capacity envelope | The same Experiment specification admits bounded reservations, counts `Reserved` and `Consumed` work, and rejects over-commitment. |
| Expiry, cancellation, and retained consumption audit | Experiment verifies zero-lifetime expiry and cancellation; the assembled scheduler check observes `Consumed` after persistence and `Cancelled` for an unstarted recovery reservation. |
| Assembled Sanpomap SPI ownership | `scripts/check-phase8-comparison-replay-scheduler.sh` builds current CARs, starts an isolated CNCF runtime, and uses Sanpomap's Experiment Port delegation without local reservation storage. |
| Completed-run accumulated evidence | The Experiment specification re-summarizes a completed two-arm run. The assembled provider-backed path completes its run and returns one accepted observation through `summarizeExperimentRun`. |
| No provider selection in scheduler inputs | The Experiment reservation CML contract and Sanpomap `reserveAiComparisonReplay` expose only run identity, opaque key, bounded count, budget, and safe evidence references. |
| Deterministic and assembled validation | Current full suites and the isolated scheduler launcher passed on 2026-07-24; normal CAR lint found no failures. |

## Validation

- Textus AI: 177 tests passed; 5 opt-in live tests canceled.
- Textus Experiment: 10 tests passed.
- Sanpomap: 81 tests passed.
- `scripts/check-phase8-comparison-replay-scheduler.sh` rebuilt the current
  Textus AI, Experiment, and Sanpomap CARs; its provider-backed consumed path,
  completed-run aggregate, and explicit cancellation recovery passed.
- Normal CAR lint passed for Textus AI, Textus Experiment, and Sanpomap.

## Residual Readiness Notes

- CAR lint still reports the existing missing ABI baseline and development
  `sbt-cozy` SNAPSHOT warnings. Sanpomap's existing host-filesystem and direct
  subsystem-configuration warnings are outside this scheduler slice.
- Rebuilding against the current CNCF 0.5.1-SNAPSHOT emits parameter-alias
  deprecation warnings in Textus AI. The rebuilt deterministic and assembled
  paths pass; alias cleanup is a separate framework-compatibility task.

## Deferred Work

- Broad provider/cost measurement, statistical confidence, optimization,
  recommendation, and cross-run normalization.
- Billing or account-wide/subscription cost accounting.
- New Textus AI operations or caller-owned provider/model/tool selection.
