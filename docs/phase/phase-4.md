# Phase 4 - Runtime Profiles and Application Purposes

status=complete
started_at=2026-07-18
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Replace configuration-defined model profiles and generic-purpose inheritance
with Textus AI-owned runtime profiles, a built-in standard-purpose catalog, and
application-purpose mappings. Applications select only a domain application
purpose; provider, model, tools, reasoning, execution class, and limits remain
runtime-owned configuration.

## Scope

- Ship Textus AI-owned `codex-cli` and `gemini` runtime profiles with explicit
  defaults for every standard execution class.
- Ship the standard-purpose catalog under the canonical `purposes` concept and
  its default execution-class mapping.
- Resolve `application-purposes.<name>.purpose` through one standard purpose;
  permit only policy narrowing owned by the application mapping.
- Apply ordinary merged CNCF configuration as overrides to the selected runtime
  profile without allowing application callers to choose concrete AI settings.
- Remove and structurally reject `model-profiles`, `model-profile`,
  `generic-purposes`, `base-purpose`, `levels`, and legacy level aliases.
- Publish safe runtime-profile and effective execution-class facts without a
  caller-selected model-profile identity.
- Preserve provider-neutral `generate`, `chat`, and `generateRecord` CML
  operations and the existing CNCF `AiRunnerRequirement.purpose` contract.

## Boundaries

- An application supplies an application purpose only. It does not supply a
  runtime profile, provider, mode, engine, model, reasoning level, logical
  tools, or execution class.
- Runtime profiles and the standard-purpose catalog are Textus AI code, not
  project configuration that applications must recreate.
- User and project configuration may override operator runtime defaults only
  through approved runtime/execution-class keys after normal CNCF merge.
- Application-purpose mappings may narrow admitted bounds and declare caller
  contract identities. They cannot broaden capabilities or replace concrete
  runtime selection.
- This phase does not add a runtime workflow, a new AI operation, a CML
  operation, provider fallback, or application source/fallback policy.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| RP-01 | Runtime profile catalog | Built-in `codex-cli` and `gemini` profiles define explicit defaults for all standard execution classes. | done |
| SP-01 | Standard purpose catalog | Built-in standard purposes map to their default execution classes without project redefinition. | done |
| AP-01 | Application-purpose resolver | Domain purpose mappings resolve through a standard purpose and enforce caller-selection and narrowing boundaries. | done |
| CM-01 | Strict configuration migration | Legacy purpose/model-profile/level key families are rejected before provider execution. | done |
| RB-01 | Runtime binding and facts | Component defaults, Codex capability compilation, and safe metadata use the selected runtime profile. | done |
| ES-01 | Executable specification and closure | Profile defaults, override precedence, rejection behavior, provider bindings, and downstream contract are verified. | done |

## Exit Criteria

- `textus.ai.profile=codex-cli` resolves every standard execution class from
  supplied defaults without a `model-profiles` block.
- `textus.ai.profile=gemini` resolves the same class set through supplied
  Gemini defaults.
- Merged user/project configuration overrides selected profile defaults in the
  documented CNCF precedence order.
- An application purpose resolves through its configured standard purpose, and
  an application caller cannot select a runtime profile, execution class,
  provider, model, reasoning level, or tool directly.
- Legacy configuration families fail with structured configuration errors even
  when a deprecated key would otherwise be unused.
- Response metadata and CallTree publish the selected runtime profile and
  effective execution class without a model-profile identity, credentials,
  prompt, or raw provider payload.
- Existing provider admission, input/cost accounting, structured generation,
  and no-fallback guarantees remain covered by deterministic tests.

## References

- [Runtime Profile and Application Purpose Specification](../journal/2026/07/2026-07-18-runtime-profile-and-application-purpose-specification.md)
- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [AI Execution Class Resolution](../spec/ai-execution-class-resolution.md)
- [AI Runner Execution Facts](../design/ai-runner-execution-facts.md)
- [Phase 3 Closure](phase-3-closure.md)
- [Phase 4 Closure](phase-4-closure.md)
- [Phase 4 Checklist](phase-4-checklist.md)
