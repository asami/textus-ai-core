# Handoff: Ollama Managed Instance Scope

Date: 2026-07-21

## Context

Textus AI Phase 5 activates Gemma through an Ollama Docker service managed by
the CNCF service-container runtime. The live verification confirmed that the
runtime can reuse a retained named model volume while it starts and stops its
runtime-owned container.

The term "shared Docker image" must not be conflated with a shared Ollama
instance. Docker images are immutable layers cached by one Docker daemon. A
single available `ollama/ollama:latest` image can therefore back many
containers without duplicating its image layers. The architectural choice is
the scope of the running service container and its mutable model volume.

## Current Textus AI Behavior

The current managed Gemma path declares:

- image `ollama/ollama:latest` by default;
- logical service ID `ollama` owned by `textus-ai-runtime`;
- named volume `textus-ai-ollama` mounted at `/root/.ollama`;
- `CreateOrReuse` service-container reuse policy; and
- `Stop` cleanup policy at subsystem shutdown.

Consequently, the Docker daemon image cache is shared, and a matching managed
service definition can reuse its model volume. A stopped managed container can
also be restarted by the service-container runtime. This is a runtime-owned
convenience default, not an application-level promise that every application
on a host shares one live Ollama process. The effective sharing boundary is
determined by the CNCF service-container identity, owner, registry/runtime
scope, and volume name.

An explicit `textus.ai.gemma.endpoint` remains a separate pattern: Textus AI
does not create or own a container or volume, and the endpoint operator owns
all lifecycle decisions.

## Instance Patterns

| Pattern | Image layers | Service container | Model volume | Appropriate use |
| --- | --- | --- | --- | --- |
| Shared managed service | Shared per Docker daemon | One reusable logical Ollama service | Shared named volume | A controlled host or development runtime where model storage and concurrency are intentionally common. |
| Component-scoped managed service | Shared per Docker daemon | One service per component runtime identity | One named volume per component | Isolation between independently deployed components with local lifecycle ownership. |
| Application-scoped managed service | Shared per Docker daemon | One service per application identity | One named volume per application | Application-specific model sets, upgrades, quotas, or lifecycle policy. |
| External endpoint | Shared or remote, outside Textus AI | External owner | External owner | A platform-managed Ollama cluster or pre-existing local service. |

Only the first three patterns are managed-service variants. They should share
the Docker image cache but must not accidentally share mutable service state.

## Design Direction

Treat service-instance scope as runtime/operator policy, never as an
application caller or `AiRunnerRequirement` field. A caller continues to name
only its registered application purpose. The selected runtime profile resolves
the Gemma provider, while bootstrap configuration resolves the managed-service
scope.

A future scope contract should make these values explicit:

- `shared`: a stable host/runtime-wide service and volume identity;
- `component`: an identity derived from the Textus AI component deployment;
- `application`: an identity derived from the registered application owner; and
- `external`: no managed definition, selected by explicit endpoint.

The contract must derive the service-container owner, service identity, volume
name, reuse policy, and cleanup policy together. Configuring only a shared
volume name is insufficient because it can attach unrelated containers to the
same mutable model store.

## CNCF Dependency

Textus AI should not invent Docker naming or registry semantics. It needs CNCF
service-container support for a stable, inspectable instance key that controls
container reuse independently of the immutable image identity. CNCF should
also define how two owners requesting the same shared key coordinate readiness,
stop behavior, failure reporting, and concurrent resolution.

If CNCF exposes this as a generic service-instance scope or key, Textus AI can
map its Gemma runtime profile to that contract. If it does not, the required
extension belongs in CNCF before Textus AI exposes scope selection as a
supported configuration feature.

## Operational Trade-offs

- Shared services minimize model download, disk use, and startup work, but
  share queueing, memory pressure, model upgrades, and shutdown impact.
- Isolated services permit independent model selection and lifecycle controls,
  but duplicate model volumes and consume more RAM when concurrently active.
- An external endpoint gives an infrastructure owner full control, but Textus
  AI cannot guarantee local availability or model installation.
- `Stop` is safe for an instance that has no other active owner. Shared scope
  needs reference-aware or lease-aware stop semantics; one subsystem shutdown
  must not terminate another active consumer's service.

## Required Evidence Before Enabling Scope Selection

- Deterministic specifications for instance-key derivation and volume naming
  for every scope.
- Two-owner tests proving shared resolution converges on one ready endpoint and
  does not stop it while another owner remains active.
- Isolation tests proving component and application scopes do not share model
  volumes or container identities.
- External-endpoint precedence tests proving no Docker service is resolved.
- Live Docker verification for one shared and one isolated configuration.

## Non-Goals

- This handoff does not change the current Phase 5 default.
- It does not make image pulling an AI caller responsibility or add Docker
  arguments to component operations.
- It does not require every Textus AI application to run an individual Ollama
  container.
