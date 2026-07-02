# TextusAi Basic Design

## Purpose

Extract TextusAi capabilities as a CNCF component and make the LLM provider swappable through an adapter.

## Design Principles

### 1. Separate Capability and Strategy

- Capability: what the system does
- Strategy: how it is executed

In this design, TextusAi capabilities are represented as component/service/operation,
while providers such as Gemma or OpenAI are handled as adapters.

### 2. Component Owns Binding Policy

The `Component` owns `ExtensionPoint` and `VariationPoint`.

- `ExtensionPoint` creates backend bindings inside the component
- `VariationPoint` selects the backend or routing policy inside the component
- `Port` is the externally visible execution surface

This keeps adapter selection and fallback logic internal to the component.

### 3. Provider-Agnostic DSL

Do not expose provider names or implementation details in the DSL.
Expose only generic APIs such as `ai.llm.generate` and `ai.llm.chat`.

### 4. Runtime Resolution

Resolve the provider from configuration and execution context.

## Logical Structure

```text
ai
 └─ llm
     ├─ generate
     ├─ chat
     └─ embed (optional)
```

## CML Sketch

```cml
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
```

## Domain Model

### GenerateRequest

- `prompt: string`
- `temperature: float?`
- `max_tokens: int?`

### GenerateResponse

- `text: string`

### ChatRequest

- `messages: Message[]`

### ChatResponse

- `message: Message`

### Message

- `role: system | user | assistant`
- `content: string`

## Adapter Boundary

### LlmAdapter

```scala
trait LlmAdapter {
  def generate(req: GenerateRequest): IO[GenerateResponse]
  def chat(req: ChatRequest): IO[ChatResponse]
}
```

The adapter encapsulates provider-specific communication, local inference, and model configuration.

### LlmAdapterFactory

```scala
trait LlmAdapterFactory extends CollaboratorFactory {
  def supports(provider: String): Boolean
  def create(...): LlmAdapter
}
```

## Resolution Flow

1. Read the provider name from `ExecutionContext`
2. Find factories from `CollaboratorRepository`
3. Select the factory that matches the provider
4. Create the `LlmAdapter` and pass it to the operation

This flow should be understood as internal component routing:

- `VariationPoint` selects the route
- `ExtensionPoint` materializes the port
- `Port` performs the invocation

## Operation Usage

```scala
class GenerateOperation extends Operation {
  def execute(ctx: ExecutionContext): Consequence[Response] = {
    val adapter = LlmAdapterResolver.resolve(ctx)
    val prompt  = ctx.arg[String]("prompt")

    adapter.generate(GenerateRequest(prompt))
      .map(r => Response(r.text))
  }
}
```

## Observability

- provider
- latency
- token usage
- error taxonomy

## Design Decisions

- Use `generate` and `chat` as the initial scope
- Defer `embed` as optional
- Keep provider-specific configuration inside the AI runtime
- Collect observation as shared operation metadata
