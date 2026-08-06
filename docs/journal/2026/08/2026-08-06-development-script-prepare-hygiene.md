# Development Script Prepare Separation Hygiene

Status: Open hygiene

## Finding

The development runtime flow does not yet use the ArtScene prepare model.
`scripts/run-textus-ai.sh` starts the runtime through top-level SBT, while
`scripts/update-runtime-classpath.sh` is only a classpath refresh helper. There
is no explicit aggregate prepare entry point or digest-backed preparation
manifest that a runtime-only check can validate.

## Required direction

- Add one bounded prepare entry point routed through the shared serialized SBT
  runner for required build, CAR, dependency, and runtime-evidence work.
- Keep development runtime and HTTP verification scripts top-level-SBT-free.
- Record enough artifact and input digest evidence to reject missing or stale
  preparation with an actionable diagnostic.
- Preserve the repair loop: compile and restart must not rebuild unrelated
  artifacts when prepared dependency/runtime evidence is still current.

## Completion evidence

- Static inventory finds no top-level SBT in the maintained runtime path.
- Prepare succeeds through the serialized runner.
- Runtime succeeds from current prepared evidence and rejects missing or stale
  evidence without repairing it implicitly.
