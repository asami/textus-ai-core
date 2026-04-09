# CNCF Adapter Creation and Binding Registration Guide for textus-ai (2026-04-07)

status=draft
created_at=2026-04-07
tag=textus-ai, cncf, adapter, extension-point, binding, registration

---

# Purpose

This note explains only the practical part `textus-ai` needs right now:

- how to create an adapter
- how to register it into the current CNCF binding model
- how to expose both `generate` and `chat`

It intentionally avoids broader architecture discussion.

---

# Core Rule

In the current CNCF model:

- adapter creation belongs in `ExtensionPoint`
- binding registration belongs in `Component.withBinding(...)`
- provider injection into runtime registry happens through `install_binding(...)`
- `VariationSelection` carries provider / mode / engine routing hints
- `mode=remote` uses the primary endpoint only
- `mode=local` and `mode=local-first` allow fallback endpoint routing

That is the practical flow to follow.

---

# Step 1: Define the Abstract Service Trait

The adapter must implement an abstract service trait for each capability.

Example:

```scala
trait GenerateService {
  def generate(req: GenerateRequest): Consequence[GenerateResponse]
}

trait ChatService {
  def chat(req: ChatRequest): Consequence[ChatResponse]
}
```

This is the type that runtime logic will consume.

---

# Step 2: Create the Concrete Adapter

Implement the concrete adapter as a class that extends the abstract service trait.

Example:

```scala
final class GemmaOllamaGenerateAdapter(
  endpoint: URI,
  model: String
) extends GenerateService {
  def generate(req: GenerateRequest): Consequence[GenerateResponse] = {
    // Ollama HTTP call here
    ???
  }
}

final class GemmaOllamaChatAdapter(
  endpoint: URI,
  model: String
) extends ChatService {
  def chat(req: ChatRequest): Consequence[ChatResponse] = {
    // Ollama HTTP call here
    ???
  }
}
```

This class is the actual adapter.

---

# Step 3: Create an `ExtensionPoint`

`ExtensionPoint` is the place where the adapter is constructed.

Example:

```scala
val localGemmaGenerate = new ExtensionPoint[GenerateService] {
  def supports(
    contract: ServiceContract[GenerateService],
    variation: VariationSelection
  )(using ExecutionContext): Boolean =
    contract.name == "generate-service" &&
      variation.provider.contains("gemma") &&
      variation.mode.contains("local") &&
      variation.engine.contains("ollama")

  def provide(
    contract: ServiceContract[GenerateService],
    variation: VariationSelection
  )(using ExecutionContext): Consequence[GenerateService] =
    Consequence.success(
      new GemmaOllamaGenerateAdapter(
        endpoint = endpoint,
        model = model
      )
    )
}

val localGemmaChat = new ExtensionPoint[ChatService] {
  def supports(
    contract: ServiceContract[ChatService],
    variation: VariationSelection
  )(using ExecutionContext): Boolean =
    contract.name == "chat-service" &&
      variation.provider.contains("gemma") &&
      variation.mode.exists(m => m == "local" || m == "local-first") &&
      variation.engine.contains("ollama")

  def provide(
    contract: ServiceContract[ChatService],
    variation: VariationSelection
  )(using ExecutionContext): Consequence[ChatService] =
    Consequence.success(
      new GemmaOllamaChatAdapter(
        endpoint = endpoint,
        model = model
      )
    )
}
```

This is the canonical adapter creation point.

---

# Step 4: Build the Binding

Create a `PortApi`, a `VariationPoint`, and then wrap them with the extension point.

Example:

```scala
val binding = Component.Binding(
  org.goldenport.cncf.component.Port(
    api = generateapi,
    spi = Vector(localGemmaGenerate),
    variation = generatevariation
  )
)
```

The important point is:

- `spi = Vector(localgemma)`

This is where the adapter-producing extension point is attached.

---

# Step 5: Register the Binding in the Component

Register it under a stable name.

Example:

```scala
component.withBinding("generate", binding)
component.withBinding("chat", chatBinding)
```

This is binding registration.

---

# Step 6: Install the Resolved Adapter into Runtime Registry

At bootstrap time, resolve and inject the adapter.

Example:

```scala
component.install_binding[GenerateRequirement, GenerateService](
  name = "generate",
  req = GenerateRequirement(
    provider = Some("gemma"),
    mode = Some("local"),
    engine = Some("ollama")
  )
)

component.install_binding[GenerateRequirement, ChatService](
  name = "chat",
  req = GenerateRequirement(
    provider = Some("gemma"),
    mode = Some("local"),
    engine = Some("ollama")
  )
)
```

This step resolves the service and installs it into:

```scala
component.port
```

---

# Step 7: Use the Installed Adapter

Runtime logic reads the injected service from `Component.Port`.

Example:

```scala
component.port.get[GenerateService] match {
  case Some(service) =>
    service.generate(req)
  case None =>
    Consequence.failure("generate service is not installed")
}

component.port.get[ChatService] match {
  case Some(service) =>
    service.chat(req)
  case None =>
    Consequence.failure("chat service is not installed")
}
```

---

# Summary

For `textus-ai`, the practical rule is:

1. implement adapter as a service trait implementation
2. create it inside `ExtensionPoint.provide(...)`
3. register the binding with `component.withBinding(...)`
4. install it with `component.install_binding(...)`
5. use it through `component.port.get[T]`
6. treat `mode=remote` as primary-endpoint-only and keep fallback routing out of it

That is the current intended way to create and register adapters.
