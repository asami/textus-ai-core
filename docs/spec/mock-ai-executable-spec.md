Mock AI Executable Specification
================================

This spec describes the deterministic mock AI used as a working
specification for the TextusAi runtime component.

Purpose

- Provide a stable, executable contract for `generate` and `chat`
- Keep AI behavior verifiable without depending on Gemini, Gemma, or Ollama
- Serve as a lightweight reference while the real adapter wiring evolves

Behavior

- `generate` returns a deterministic response derived from the prompt
- `chat` returns a deterministic assistant message derived from the latest user message
- When no user message is present, `chat` falls back to a default reply

Notes

- The mock spec is intentionally simple
- It is not a production adapter
- It exists to keep the component behavior observable and testable
