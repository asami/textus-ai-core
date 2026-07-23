# Phase 8 Comparison Replay Scheduler Specification

status=draft
scope=P8-RS-01

## Reservation Contract

`ExperimentManagement` owns comparison replay reservations. A reservation has
an opaque key, one running experiment run, a positive replay count, a positive
budget in microunits, a component-owned expiry instant, and one lifecycle
state: `Reserved`, `Consumed`, `Cancelled`, or `Expired`.

The reservation key is globally unique within the reservation collection and
is idempotent only when every immutable input is equal. A reuse with different
run, count, or budget is a state conflict.

## Admission

The operator enables the scheduler with the Experiment component configuration.
The configuration provides a positive per-run replay-count ceiling, a positive
per-run budget ceiling, and a non-negative reservation lifetime. A zero
lifetime makes every reservation immediately expire and is useful for safely
disabling execution in a deterministic environment. Admission fails
before provider execution when configuration is absent or invalid, the run is
not running, an input is non-positive, or the sum of current `Reserved` and
`Consumed` reservations would exceed either ceiling.

`Cancelled` and `Expired` reservations do not consume capacity. A `Consumed`
reservation remains in the capacity total for its run because the bounded
comparison work has already occurred.

## Lifecycle

- `reserve`: creates or returns the matching `Reserved` reservation.
- `consume`: requires the reservation's experiment-run identity, changes
  exactly one matching unexpired `Reserved` reservation to `Consumed`, and
  retains an opaque observation reference. A reservation cannot be consumed
  from a different experiment run.
- `cancel`: changes exactly one `Reserved` reservation to `Cancelled` and
  retains an optional safe cancellation reference.
- `expire`: changes every expired `Reserved` reservation selected by the
  component clock to `Expired`.

Terminal reservations cannot be consumed, cancelled, or redefined. Consumption
and cancellation are idempotent only with the same recorded reference.

## Security and Observability

The contract never accepts provider, model, endpoint, credential, tool,
prompt, response, account, or pricing inputs. It retains only experiment and
reservation identities, bounded count/budget values, state, timestamps, and
opaque evidence references. Sanpomap invokes the contract only through an
assembled CNCF component port.

## Validation

Deterministic specifications must demonstrate idempotence, envelope admission,
single consumption, cancellation, expiry, terminal-state rejection, and an
assembled Sanpomap-to-Experiment path.
