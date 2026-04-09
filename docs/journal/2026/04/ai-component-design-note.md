AI Component with Adapter Architecture (Gemma Integration)
==========================================================

status=draft
updated_at=2026-04-04
tag=cncf, ai, llm, adapter

# Overview

This design note defines a generic AI component architecture for CNCF,
where LLM providers such as Gemma are integrated via adapter abstraction.

The goal is to decouple:

- AI capability (Component / Service / Operation)
- Model implementation (Gemma, OpenAI, etc.)

This enables provider-independent DSL, runtime flexibility,
and alignment with CNCF component and collaborator architecture.

---

# Design Principle

## Separation of Capability and Strategy

The design separates:

- Capability: "what the system does"
- Strategy: "how it is executed"

In this context:

- Capability = AI operations (generate, chat, embed)
- Strategy   = LLM provider (Gemma, OpenAI, etc.)

This corresponds to:

- Component / Service / Operation (CNCF)
- Adapter (Strategy pattern)

---

# Component Structure

## Logical Structure

ai component provides generic LLM capabilities:

ai
 └─ llm
     ├─ generate
     ├─ chat
     └─ embed (optional)

No provider-specific concepts (e.g., Gemma) appear in the DSL.

---

# CML Definition

component ai {

  service llm {

    operation generate {
      input {
        prompt: string
        temperature: float?
        max_tokens: int?
      }
      output {
        text: string
      }
    }

    operation chat {
      input {
        messages: Message[]
      }
      output {
        message: Message
      }
    }

  }

}

---

# Adapter Abstraction

## LlmAdapter Interface

trait LlmAdapter {
  def generate(req: GenerateRequest): IO[GenerateResponse]
  def chat(req: ChatRequest): IO[ChatResponse]
}

Adapters encapsulate provider-specific logic.

---

# Gemma Adapter

[Gemma](chatgpt://generic-entity?number=0) is integrated as an adapter implementation:

class GemmaAdapter(...) extends LlmAdapter {
  def generate(req: GenerateRequest) = ...
  def chat(req: ChatRequest) = ...
}

The adapter may call:

- local inference (gguf / llama.cpp)
- HTTP inference server (Ollama, vLLM, etc.)

---

# Adapter Resolution

## Runtime Selection

The adapter is resolved at runtime via configuration:

Example:

ai:
  llm:
    provider: gemma

or via command:

cncf command ai.llm.generate --provider gemma

---

## ExecutionContext Integration

Adapter selection is resolved through ExecutionContext:

val provider = ctx.config("ai.llm.provider")
val adapter  = adapterRegistry.resolve(provider)

This aligns with:

- ResolvedConfiguration
- FrameworkOptions / RuntimeParameter

---

# Collaborator Integration

Adapters are treated as CNCF collaborators.

## Packaging

component.d/
  ai.car

collaborator.d/
  gemma-adapter.jar
  openai-adapter.jar

---

## Factory

trait LlmAdapterFactory extends CollaboratorFactory {
  def supports(provider: String): Boolean
  def create(...): LlmAdapter
}

---

## Resolution Flow

1. ExecutionContext provides provider name
2. CollaboratorRepository discovers factories
3. Matching factory is selected
4. Adapter instance is created

---

# Operation Implementation

class GenerateOperation extends Operation {

  def execute(ctx: ExecutionContext): Consequence[Response] = {
    val adapter = LlmAdapterResolver.resolve(ctx)
    val prompt  = ctx.arg[String]("prompt")

    adapter.generate(GenerateRequest(prompt))
      .map(r => Response(r.text))
  }
}

---

# Observability

LLM execution should emit structured observation:

- provider (gemma, openai, etc.)
- latency
- token usage
- error taxonomy

This integrates with Observation / Conclusion model:

Observation:
  phenomenon = Failure | Deviation | etc.
  taxonomy   = system.unavailable / value.invalid / etc.

---

# Meta / Introspection

meta.describe should expose provider capabilities:

llm:
  providers:
    - gemma
    - openai

This allows:

- AI agents to discover capabilities
- dynamic routing decisions

---

# Advantages

## Provider Independence

DSL and Component remain stable regardless of provider.

## Runtime Flexibility

Provider can be changed without redeployment.

## CNCF Alignment

- Adapter = Collaborator
- Resolution = ExecutionContext
- Packaging = component.d / collaborator.d

## Testability

Adapters can be mocked:

FakeLlmAdapter for deterministic testing.

---

# Extensions

## Multi-provider Strategy

fallback(gemma, openai)

## Routing

- small prompt → gemma
- complex → external LLM

## Cost-aware Selection

provider selected based on cost constraints.

---

# Future Work

## Adapter DSL

adapter gemma {
  endpoint: ...
  model: ...
}

## RAG Integration

ai.rag.answer operation using:

- embed
- retrieve
- generate

## Agent Component

ai.agent component built on top of llm operations.

---

# Conclusion

This architecture establishes:

- AI as a stable capability layer
- LLM providers as pluggable execution strategies

It aligns fully with CNCF principles and enables:

- composability
- replaceability
- AI-native component integration
