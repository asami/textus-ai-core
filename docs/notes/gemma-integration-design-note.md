Gemma Integration Design Note for CNCF TextusAi Component (Recommended Architecture)
=============================================================================

status=draft
updated_at=2026-04-04
tag=cncf, ai, llm, gemma, adapter, docker, ollama

# Overview

This note defines the recommended architecture for integrating Gemma
as a lightweight LLM within a CNCF-based Textus AI runtime component.

The design establishes:

- a stable, provider-independent Textus AI runtime interface
- Gemma as the primary lightweight local LLM backend
- Docker-based deployment using Ollama as the default runtime
- configuration-based switching to remote LLM services

The key architectural decision is:

The default and recommended deployment model is:

CNCF (Scala/JVM) + Docker + Ollama + Gemma

This provides the best balance of:

- simplicity
- portability
- operational isolation
- extensibility

The component ownership model is:

- `Component` owns `VariationPoint` and `ExtensionPoint`
- `Port` is the externally visible execution surface
- adapter construction and backend selection remain internal to the component
- `OPERATION` kinds are usually inferred from `INPUT`. A `TYPE` section is
  only needed when you want to assert the intended kind explicitly and check it
  against the input value object.

---

# Recommended Architecture (Canonical Form)

## Logical View

ai.component
  └─ llm.generate / chat

        ↓ Adapter

GemmaOllamaAdapter

        ↓ HTTP (Docker network)

Ollama Container

        ↓

Gemma Model

---

## Deployment View

Docker Compose:

- cncf container (Scala application)
- ollama container (LLM runtime)

Communication:

- HTTP over internal Docker network

---

## Why This Is the Recommended Default

This architecture is chosen as the canonical starting point because:

1. Clean separation of concerns

   - JVM handles orchestration and business logic
   - Ollama handles model execution

2. No native/JNI complexity

   - avoids embedding inference into JVM
   - avoids GPU binding issues in JVM

3. Operational isolation

   - model runtime isolated in container
   - restart / upgrade independent

4. CNCF alignment

   - Ollama acts as a collaborator-like execution backend
   - Adapter cleanly bridges component and runtime

5. Future extensibility

   - remote LLM can replace Ollama without changing operations
   - multi-provider routing becomes trivial

---

# Mandatory Design Rule

The following rule is introduced as a normative constraint:

LLM execution MUST be abstracted behind an adapter and MUST NOT
be directly embedded into CNCF operation logic.

Additionally:

The JVM MUST NOT be used as the primary LLM execution runtime.

Instead, it MUST delegate inference to:

- same-host runtime (recommended: Ollama)
- or remote inference service

---

# Standard Deployment (Recommended)

## Docker Compose Template

version: "3.8"

services:

  cncf:
    build: .
    depends_on:
      - ollama
    environment:
      AI_LLM_PROVIDER: gemma
      AI_LLM_MODE: local
      AI_LLM_ENDPOINT: http://ollama:11434

  ollama:
    image: ollama/ollama
    volumes:
      - ollama_data:/root/.ollama
    restart: always

volumes:
  ollama_data:

---

## Model Initialization

After container startup, Gemma must be pulled once:

docker exec -it ollama ollama pull gemma:2b

This step prepares the local model for inference.

---

## Runtime Endpoint

The standard local endpoint is:

http://ollama:11434/api/generate

This MUST be treated as the canonical local inference endpoint.

---

# Adapter Design (Updated Recommendation)

## Adapter Role

The adapter MUST:

- translate CNCF request → Ollama API
- normalize response → CNCF response
- hide all provider-specific details

---

## Canonical Adapter

GemmaOllamaAdapter is the default implementation.

class GemmaOllamaAdapter(endpoint: String, model: String) extends LocalLlmAdapter

---

## Adapter Responsibility

The adapter is responsible for:

- JSON request construction
- prompt mapping
- parameter translation
- response extraction
- error normalization

Within CNCF terms, this adapter is realized by a component-owned
`VariationPoint` selecting an `ExtensionPoint`, which then exposes the
execution `Port`.

---

# Configuration Model (Recommended Form)

ai:
  llm:
    provider: gemma
    mode: local
    local:
      engine: ollama
      endpoint: http://ollama:11434
      model: gemma:2b

## Mode Semantics

The `AI_LLM_MODE` setting controls endpoint selection behavior.

- `local`: use the local endpoint first; fallback is allowed when configured
- `local-first`: use the local endpoint first, then try the fallback endpoint
- `remote`: use the primary endpoint only; do not use fallback

---

## Interpretation Rules

- provider selects model family
- mode selects local or remote execution
- local.engine selects runtime (default: ollama)
- endpoint defines connection target
- model defines runtime model identifier

---

# Resolution Flow (Updated)

1. Read provider
2. Read mode
3. Read local.engine
4. Resolve adapter:

   gemma + local + ollama
     → GemmaOllamaAdapter

5. Execute operation via adapter

---

# Scala Implementation Strategy (Normative)

The Scala/JVM layer MUST:

- use HTTP client for inference
- NOT execute model weights directly
- NOT depend on native ML runtime bindings

Recommended client:

- Java HttpClient
- or sttp / http4s

---

## Minimal Implementation Pattern

GenerateOperation
  → resolve adapter
  → call adapter.generate()
  → map response

Adapter
  → call Ollama HTTP API

---

# Resource Management (Important)

Since Ollama runs locally, CNCF MUST control load.

## Required Controls

- max_concurrency
- timeout
- retry policy (optional)

Example:

ai:
  llm:
    timeout: 30s
    local:
      max_concurrency: 2

---

# Observability (Updated)

Every call MUST include:

- provider=gemma
- mode=local
- engine=ollama
- model=gemma:2b
- latency
- success/failure

---

# Fallback and Remote Extension

Although Ollama is the default, remote execution MUST remain supported.

## Remote Example

ai:
  llm:
    provider: gemma
    mode: remote
    endpoint: http://llm-server:8080

Example runtime invocation:

```bash
AI_LLM_MODE=remote \
AI_LLM_ENDPOINT=http://llm-server:8080 \
AI_LLM_MODEL=gemma:2b \
sbt run
```

---

## Future Extension

fallback:
  - local
  - remote

Meaning:

1. try local Ollama
2. fallback to remote server

---

# Deployment Patterns (Final Recommendation)

## Recommended

✔ Docker Compose (CNCF + Ollama)

## Acceptable

- Kubernetes (separate pods)
- remote-only LLM

## Not Recommended

- embedding inference inside JVM
- direct JNI integration as primary path

---

# Key Architectural Insight

This design reinterprets LLM integration as:

Capability (CNCF)
  +
Execution Backend (Ollama)
  +
Adapter (bridge)

This aligns with CNCF principles:

- component = capability
- collaborator = execution resource
- adapter = binding layer

---

# Conclusion

The recommended architecture is:

CNCF (Scala)
  → Adapter
  → Ollama (Docker)
  → Gemma

This approach provides:

- a stable and clean component model
- a practical JVM integration strategy
- strong operational isolation
- immediate usability
- long-term extensibility

This is the default path and SHOULD be adopted as the primary implementation strategy.
