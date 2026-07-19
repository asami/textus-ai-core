# Phase 5 - Local Gemma/Ollama Runtime Activation

status=active
started_at=2026-07-20
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)

## Purpose

Activate the existing Gemma/Ollama HTTP adapter as a runtime-owned local AI
profile. Applications continue to choose only registered application purposes;
Textus AI resolves the local provider, model, and limits.

## Scope

- Ship a `gemma` runtime profile for all four standard execution classes and
  common simple-work-only Gemma composite profiles.
- Use the existing CNCF HTTP-bound Gemma/Ollama generate and chat services.
- Provision a component-owned Ollama Docker container when no endpoint is
  configured, through fixed CNCF managed-process capabilities.
- Prefer an explicitly configured Ollama endpoint over local Docker control.
- Permit operator model tuning only through approved execution-class keys.
- Preserve deterministic structured-record, timeout, and explicit endpoint
  fallback behavior.

## Boundaries

- This phase does not embed model inference in the JVM or expose Docker control
  to application callers.
- The deployment owner must provide Docker access for the profile-owned local
  runtime, or configure an external Ollama endpoint.
- No application caller may select Gemma, Ollama, a model, or a local endpoint.
- No local-to-commercial provider fallback is introduced.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| GO-01 | Runtime profile | `gemma` and simple-work-only composite profiles resolve their local Ollama defaults. | complete |
| GO-02 | Container contract | Docker provisioning is profile-owned; an endpoint override disables it. | complete |
| GO-03 | Executable evidence | Deterministic profile, process binding, and provider specs pass; live Gemma provisioning is a heavy test. | in progress |

## References

- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [Phase 1 AI Runner Executable Specification](../spec/phase-1-ai-runner-executable-spec.md)
- [Gemma Integration Design Note](../notes/gemma-integration-design-note.md)
