# Phase 4 Closure - Runtime Profiles and Application Purposes

status=complete
completed_at=2026-07-18
phase=[Phase 4](phase-4.md)

## Outcome

Textus AI now resolves application AI requests through runtime-owned profiles,
standard purposes, execution classes, and application-purpose mappings. The
legacy model-profile/generic-purpose/level resolver was removed rather than
retained as a compatibility path.

## Evidence

| Criterion | Evidence |
| --- | --- |
| Supplied profile matrices | `AiRuntimeProfileCatalog` and `AiRuntimeProfileSpec` resolve all four classes for `codex-cli` and `gemini`. |
| Standard-purpose catalog | `AiRuntimeProfileCatalog.standardPurposes` maps the six shipped purposes to their runtime-owned classes. |
| Application isolation | `TextusAiRunnerSpec` verifies mapping, policy narrowing, and rejection of caller/application concrete selection. |
| Strict migration | `AiProfileConfig` rejects canonical and alias legacy key families before provider execution. |
| Runtime binding | `ComponentFactorySpec` and `CodexRuntimeProviderSpec` verify profile-derived defaults and managed Codex capability compilation. |
| Safe attribution | `AiExecutionFactsSpec` and `TextusAiRunnerSpec` verify runtime-profile/effective-class facts without model-profile identity. |
| Downstream adoption | [Application-purpose adoption handoff](../journal/2026/07/2026-07-18-runtime-profile-application-purpose-adoption-handoff.md) defines Sanpomap and GeoResolver boundaries. |

## Validation

- `sbt --batch test`: 88 tests passed.
- CAR lint completed without `FAIL` entries.
- CAR lint residual warnings are pre-existing direct ambient-environment access
  in Gemma/Google/OpenAI providers and an absent ABI baseline; they are outside
  this Phase 4 migration slice.

## Deferred Work

- Add further runtime profiles only through a new profile catalog design and
  executable matrix.
- Define a later phase before introducing a runtime workflow, application
  fallback, or new provider-neutral operation.
