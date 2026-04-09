# TextusAi Development Environment

This workspace does not yet contain implementation code, so define the minimum structure needed to move from design to implementation.

## Assumptions

- This component follows the CNCF component/service/operation model
- The LLM provider is switched through an adapter
- Provider-specific implementation must not appear in the DSL

## Development Units

1. `component` layer
   - Define the public API of the `ai` component
   - Start with `llm.generate` and `llm.chat`
2. `adapter` layer
   - Define the `LlmAdapter` interface
   - Make provider implementations such as `GemmaAdapter` swappable
3. `runtime` layer
   - Resolve the provider from configuration
   - Emit runtime observations

## Recommended Directory Layout

```text
docs/
  notes/
    ai-component-basic-design.md
    ai-component-dev-environment.md
src/
  main/
    cozy/
    resources/
    scala/
  test/
    resources/
    scala/
```

## Implementation Order

1. Place the DSL and operation boundary in `src/main/cozy`
2. Add the provider abstraction inside `src/main/scala`
3. Connect configuration resolution and observability inside `src/main/scala`

## Implementation Notes

- Do not hard-code `gemma` as a special concept from the start
- Keep request/response types provider-agnostic
- Treat token usage, latency, and error taxonomy as shared metadata
