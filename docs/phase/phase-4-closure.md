# Phase 4 Closure - Runtime Profiles and Application Purposes

status=complete
completed_at=2026-07-18
reopened_at=2026-07-18
reclosed_at=2026-07-18
phase=[Phase 4](phase-4.md)

## Outcome

Textus AI now resolves application AI requests through runtime-owned profiles,
standard purposes, execution classes, and bootstrap-registered application
purposes. Phase 4 was reopened after the initial closure because configuration
mappings did not establish application ownership. The runtime-profile,
standard-purpose, execution-class, and provider-binding work remains intact;
this closure adds the registration correction without reverting it.

## Evidence

| Criterion | Evidence |
| --- | --- |
| Supplied profile matrices | `AiRuntimeProfileCatalog` and `AiRuntimeProfileSpec` resolve all four classes for `codex-cli` and `gemini`. |
| Standard-purpose catalog | `AiRuntimeProfileCatalog.standardPurposes` maps the six shipped purposes to their runtime-owned classes. |
| Registration contract | CNCF `AiRunnerApplicationPurposeRegistration` and its socket set collect application Port outputs during bootstrap. |
| Late scope binding | `TextusAiRunnerSpec` verifies a purpose registered through its Port after the runtime scope is established is resolved from the live catalog and enforces its own concurrency admission. |
| Application isolation | `AiRuntimeProfileSpec`, `ComponentFactorySpec`, and `TextusAiRunnerSpec` verify registration defaults, tuning, policy narrowing, and rejection of caller/application concrete selection. |
| Strict migration | `AiProfileConfig` rejects canonical and alias legacy key families before provider execution. |
| Runtime binding | `ComponentFactorySpec` and `CodexRuntimeProviderSpec` verify profile-derived defaults and managed Codex capability compilation. |
| Safe attribution | `AiExecutionFactsSpec` and `TextusAiRunnerSpec` verify application-purpose, effective-standard-purpose, runtime-profile, and effective-class facts without model-profile identity. |
| Downstream adoption | [Application-purpose registration handoff](../journal/2026/07/2026-07-18-application-purpose-registration-handoff.md) defines Sanpomap and GeoResolver boundaries. |

## Validation

- `sbt --batch test`: 90 Textus AI tests passed.
- `sbt --batch test` in `cloud-native-component-framework` passed with the new
  AiRunner registration SPI specification.
- CAR lint completed without `FAIL` entries.
- CAR lint residual warnings are pre-existing direct ambient-environment access
  in Gemma/Google/OpenAI providers and an absent ABI baseline; they are outside
  this Phase 4 migration slice.
- The Jul. 19 release follow-up strengthened executable evidence for the
  bootstrap ordering boundary. It does not reopen Phase 4 or change the
  application-purpose contract.

## Deferred Work

- Add further runtime profiles only through a new profile catalog design and
  executable matrix.
- Define a later phase before introducing a runtime workflow, application
  fallback, or new provider-neutral operation.
