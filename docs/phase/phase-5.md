# Phase 5 - Local Gemma/Ollama Runtime Activation

status=complete
started_at=2026-07-20
completed_at=2026-07-21
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Activate the existing Gemma/Ollama HTTP adapter as a runtime-owned local AI
profile. Applications continue to choose only registered application purposes;
Textus AI resolves the local provider, model, and limits.

## Scope

- Ship a `gemma` runtime profile for all four standard execution classes and
  common simple-work-only Gemma composite profiles.
- Use the existing CNCF HTTP-bound Gemma/Ollama generate and chat services.
- Use native Ollama by default and resolve a component-owned Ollama service
  through CNCF's managed service-container runtime only when selected.
- Permit an explicitly configured Ollama endpoint only for the native runtime.
- Permit operator model tuning only through approved execution-class keys.
- Preserve deterministic structured-record, timeout, and explicit endpoint
  fallback behavior.

## Boundaries

- This phase does not embed model inference in the JVM or expose Docker control
  to application callers.
- The deployment owner must provide native Ollama for the default local
  runtime, or explicitly select the managed Docker runtime.
- No application caller may select Gemma, Ollama, a model, or a local endpoint.
- No local-to-commercial provider fallback is introduced.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| GO-01 | Runtime profile | `gemma` and simple-work-only composite profiles resolve their local Ollama defaults. | complete |
| GO-02 | Container contract | Managed service resolution is profile-owned; an endpoint override disables it. | complete |
| GO-03 | Executable evidence | Deterministic profile, fake-gateway consumer, provider, and opt-in live Gemma provisioning specifications pass. | complete |

## Superseded Operating Policy

As of Jul. 22, 2026, native Ollama at `127.0.0.1:11434` is the default Gemma
runtime. The Docker path documented below remains implemented but requires the
explicit `textus.ai.gemma.runtime: managed-docker` selection. Native connection
failure returns a structured provider failure and never starts Docker as a
fallback. `managed-docker` may not be combined with `textus.ai.gemma.endpoint`.

## Phase 44 Integration Update

The earlier fixed Docker Process Execution binding is superseded by CNCF Phase
44. Textus AI now submits one typed Ollama service definition to the Subsystem
managed service-container runtime. Container lifecycle is no longer represented
as component-owned inspect/start/run process capabilities. After readiness,
profile model installation remains a separate provider-owned HTTP operation
against the resolved Ollama endpoint.

An explicit `textus.ai.gemma.endpoint` constructs no managed service definition
and does not request a lifecycle runtime. `GemmaOllamaLiveSpec` is opt-in and
verifies the Textus AI consumer path through CNCF's Docker runtime, model
installation, and one generation request. It is excluded from ordinary test
runs because it creates a local service and can download a model.

## Live Verification

On 2026-07-21, `TEXTUS_AI_LIVE_GEMMA_TEST=true sbt --batch 'testOnly
org.simplemodeling.textus.ai.GemmaOllamaLiveSpec'` completed successfully
against Docker Desktop with `ollama/ollama:latest` available. The specification
reused the runtime-owned named volume, resolved and started the managed service,
verified the configured `gemma:2b` model through Ollama, generated a component
response through `simple-work`, and stopped the managed container at subsystem
shutdown. The model volume remains retained by the configured cleanup policy.

The same specification is cancelled during ordinary test runs unless the opt-in
environment variable is set. This keeps local Docker and model activity outside
the normal test suite while preserving executable evidence for the managed
runtime path.

## References

- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [Phase 1 AI Runner Executable Specification](../spec/phase-1-ai-runner-executable-spec.md)
- [Gemma Integration Design Note](../notes/gemma-integration-design-note.md)
