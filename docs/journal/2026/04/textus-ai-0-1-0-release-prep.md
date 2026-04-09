TextusAi 0.1.0 Release Prep
===========================

Date: 2026-04-07

## Goal

Prepare TextusAi for the first stable local release line.

## Current decision

- `textus-ai` version remains `0.1.0-SNAPSHOT`
- `goldenport-cncf` dependency remains `0.4.2-SNAPSHOT`
- `sbt-cozy` remains `0.1.2`
- `cozy` is used as the local model compiler delegate

## Remaining work

1. Keep the Mock AI executable specification as the baseline behavioral contract
2. Continue adapter implementation work until the binding story is stable
3. Revisit the non-snapshot release line only after the adapter path is settled

## Notes

- The executable spec currently covers deterministic `generate` and `chat` behavior
- The component surface is intentionally provider-agnostic
- Release alignment should preserve the CNCF binding model already in place, but the project is still on the snapshot line while adapter implementation remains in progress
