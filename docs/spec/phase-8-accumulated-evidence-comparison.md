# Phase 8 Accumulated-Evidence Comparison and Measurement Policy

status=accepted
scope=P8-RS-03

## Comparison Boundary

One completed `ExperimentRun` is the deterministic comparison boundary. Its
parent Experiment fixes the immutable Corpus revision, acceptance operation,
and declared arms. `ExperimentManagement.summarizeExperimentRun` derives the
outcome counts from the run's immutable observations; it neither stores nor
infers a score, winner, provider preference, or cost.

An application may compare the declared arms within that completed run by
using their retained observations and the run summary. It must keep the
Corpus revision, acceptance operation, case coverage, and execution-plan
references visible in its own report. A partial or running run is operational
evidence, but is not a completed comparison result.

## Measurement Semantics

The canonical aggregate contains the total observation count and individual
counts for `accepted`, `repaired`, `confirmed`, `escalated`, `rejected`, and
`failed`. Those counts are facts, not a universal quality score: applications
own the meaning of each outcome and any decision based on it.

`metricReference`, execution evidence, and acceptance evidence remain opaque
references. Sanpomap may retain sanitized workflow measurement facts in its
artifact, but Experiment does not parse them, convert unavailable values to
zero, calculate prices, or merge them into outcome counts.

## Cross-Run Use

Different runs are separate evidence sets. A consumer may place completed-run
reports side by side only after it explicitly establishes compatible Corpus
revision, case coverage, acceptance semantics, and an application-owned
measurement method. This policy supplies no cross-run normalization,
statistical confidence, provider/model ranking, recommendation, billing, or
account-wide accounting.

## Executable Evidence

The Phase 8 assembled scheduler launcher records one provider-backed accepted
observation, consumes its reservation, completes the Experiment run, and then
asserts `summarizeExperimentRun` returns exactly one accepted observation.
The deterministic Experiment component specification separately proves that a
two-arm run derives the complete outcome distribution from three immutable
observations.
