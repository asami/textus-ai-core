# Phase 4 Checklist - Runtime Profiles and Application Purposes

status=complete
phase=[Phase 4 - Runtime Profiles and Application Purposes](phase-4.md)

## Stage RP-01 - Runtime Profile Catalog

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when every standard execution class has explicit,
  tested defaults in each supplied runtime profile.

- [x] Define the `AiRuntimeProfile` catalog and exact class-default matrices for
  `codex-cli` and `gemini`.
- [x] Resolve `textus.ai.profile` and reject an absent or unknown profile
  structurally where an application purpose is required.
- [x] Merge approved execution-class overrides over selected profile defaults.

## Stage SP-01 - Standard Purpose Catalog

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when standard purposes are runtime-owned and do
  not require project configuration definitions.

- [x] Define the shipped standard-purpose to execution-class mapping.
- [x] Resolve every standard purpose through the selected runtime profile.
- [x] Reject configuration that attempts to replace a standard purpose's
  concrete runtime selection.

## Stage AP-01A - Application-Purpose Registration Contract

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when application Component.Port output is bound
  to Textus AI's CNCF registration socket set during bootstrap.

- [x] Define `AiRunnerApplicationPurposeRegistration`, its default standard
  purpose, and provider-neutral default policy in the CNCF AiRunner protocol.
- [x] Publish the Textus AI registration input socket set and collect
  application Port outputs through the existing CNCF SPI resolver.
- [x] Pass the live registration catalog from `ComponentFactory` to
  `AiProfileConfig`.

## Stage AP-01B - Registration Defaults And Configuration Tuning

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when registered entries execute from their
  defaults and configuration cannot define application-purpose identity.

- [x] Resolve a registered default standard purpose and default policy without
  `textus.ai.application-purposes.<name>.*` configuration.
- [x] Apply allowed configuration as a narrowing tuning layer over the
  registered effective policy.
- [x] Include registered `max-concurrent` defaults in component-scoped CNCF
  admission setup.

## Stage AP-01C - Strict Registration And Configuration Validation

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when every invalid catalog or configuration
  condition fails before provider/process resolution.

- [x] Reject duplicate registrations and unknown registration standard purposes.
- [x] Reject configuration-only names and remove configuration-defined
  application-purpose mappings.
- [x] Reject unregistered application callers without interpreting the name as
  an implicit execution-class fallback.
- [x] Preserve rejection of caller/provider/model/tool and application tuning
  concrete runtime selection.

## Stage CM-01 - Strict Configuration Migration

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when deprecated configuration is rejected rather
  than preserved as a new-integration compatibility path.

- [x] Remove `AiModelProfile` and configuration-defined `model-profiles` from
  purpose resolution.
- [x] Reject `model-profiles`, `model-profile`, `generic-purposes`,
  `base-purpose`, `levels`, and legacy level aliases structurally.
- [x] Remove stale configuration examples and documentation without rewriting
  completed Phase 1-3 historical records.

## Stage RB-01 - Runtime Binding and Facts

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when provider bindings and safe execution facts
  derive from the selected runtime profile.

- [x] Replace bootstrap provider selection with selected runtime-profile
  defaults.
- [x] Compile Codex managed-process capabilities from the resolved profile and
  execution class, not a model-profile name.
- [x] Replace model-profile/generic-purpose/logical-level facts with safe
  runtime-profile and effective-class facts.

## Stage ES-01 - Executable Specification and Closure

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Close only when every migration and precedence claim has direct
  deterministic evidence and affected application integrations have an
  explicit handoff.

- [x] Verify `codex-cli` and `gemini` defaults for every standard class without
  a `model-profiles` block.
- [x] Verify bootstrap registration, registration defaults, configuration
  tuning, and application-purpose narrowing.
- [x] Verify caller and legacy-key rejection before provider/process execution.
- [x] Verify safe runtime-profile facts, Codex/Gemini bindings, input/cost
  admission, and no-fallback regression behavior.
- [x] Publish the application-purpose registration handoff for Sanpomap and
  GeoResolver.
- [x] Run review, fix actionable findings, validate, and record closure
  evidence.
