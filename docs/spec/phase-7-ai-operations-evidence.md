# Phase 7 AI Operations Evidence Specification

status=accepted
scope=textus-ai phase-7 executable operational evidence contract
updated_at=2026-07-23

## Preconditions

1. The application registers an application purpose through the CNCF
   `AiRunner` Port.
2. The purpose resolves to a shipped detailed purpose and its fixed execution
   class.
3. The runtime profile resolves the provider binding. A purpose-specific
   binding takes precedence over the execution-class default.
4. Required logical provider tools are admitted before provider execution.
5. Any CNCF Operation/MCP evidence is admitted and executed by CNCF before the
   AI request is made.

An unknown purpose/binding/tool, caller-selected runtime field, unsupported
provider tool, or failed CNCF evidence policy is a structured pre-execution
failure. It does not select an implicit provider, model, tool, or fallback.

## Required Behavior

### Selected Route

- The application supplies one registered application purpose, never a concrete
  provider/model/tool selection.
- Textus AI records the effective detailed purpose, runtime profile, execution
  class, provider, and logical provider-standard tools as separate safe facts.
- The Phase 7 CNCF evidence path supplies bounded ordinary input and leaves
  `AiRunnerRequirement.tools` empty for CNCF Operation/MCP tools.

### Deterministic Boundary

- AI produces bounded semantic content only.
- The application owns YAML/DSL serialization, coordinates, schema validation,
  domain validation, and final acceptance.
- Malformed, uncited, or domain-invalid semantic content is rejected; Phase 7
  does not use a stochastic repair loop as acceptance.

### Observation Recording

- `Evaluation.recordAiExecutionObservation` accepts only sanitized
  `ai.observation.*` facts, application assessment values, and opaque artifact
  references.
- It validates that the case belongs to the named immutable Corpus revision.
- It invokes `ExperimentManagement.recordObservation` through assembled CNCF
  SPI, preserving the caller's child execution context.
- The persisted metric artifact excludes prompts, model output, provider
  payloads, credentials, endpoints, request identifiers, and arbitrary
  metadata.

### Comparison Replay

- `Evaluation.admitAiComparisonReplay` is disabled unless the operator enables
  it and supplies positive replay-count and budget limits.
- The command rejects malformed or over-limit declarations and accepts no
  provider/model/endpoint/tool selection.
- The assembled evaluation driver calls this gate before its bounded comparison
  replay. Ordinary operation does not invoke a duplicate route.

## Executable Evidence

| Claim | Evidence |
| --- | --- |
| Detailed-purpose and purpose-binding precedence | `AiRuntimeProfileSpec` |
| Required tool admission before provider execution | `TextusAiRunnerSpec`, `AiProviderAdmissionSpec` |
| Safe runtime observation projection | `AiExecutionObservationSpec` |
| Application-purpose ownership and CNCF evidence boundary | Sanpomap `ComponentFactorySpec` |
| Replay gate and observation validation | Sanpomap `ComponentFactorySpec` |
| Current-CAR assembled path | `textus-sanpomap/scripts/check-phase7-ai-observation-assembly.sh` |

The assembled specification publishes an immutable Corpus revision, selects
`scenario-simple`, defines an Experiment arm/run, invokes one deterministic
Gemini Interactions fixture through the selected `grounded-research` route,
records the sanitized observation, and re-reads it through
`ExperimentManagement`.
