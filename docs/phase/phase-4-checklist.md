# Phase 4 Checklist - Runtime Profiles and Application Purposes

status=in-progress
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

## Stage AP-01 - Application-Purpose Resolver

Stage Status:
- Current status: DONE
- Owner: Textus AI maintainers
- Update rule: Mark DONE only when an application caller can select a domain
  purpose without selecting a concrete runtime setting.

- [x] Resolve `application-purposes.<name>.purpose` to exactly one standard
  purpose.
- [x] Permit only validated narrowing of approved execution bounds and caller
  contract identities.
- [x] Reject provider, mode, engine, model, reasoning, tool, profile, and
  execution-class selection from an application purpose or its caller.

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
- [x] Verify merged user/project override precedence and application-purpose
  narrowing.
- [x] Verify caller and legacy-key rejection before provider/process execution.
- [x] Verify safe runtime-profile facts, Codex/Gemini bindings, input/cost
  admission, and no-fallback regression behavior.
- [x] Publish the application-purpose handoff for Sanpomap and GeoResolver.
- [x] Run review, fix actionable findings, validate, and record closure
  evidence.
