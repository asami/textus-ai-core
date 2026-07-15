Mock AI Executable Specification
================================

This spec describes deterministic AI fixtures used as working specifications
for the Textus AI runtime component.

Purpose

- Provide a stable, executable contract for `generate`, `generateRecord`, and
  `chat`
- Keep AI behavior verifiable without depending on Gemini, Gemma, or Ollama
- Serve as a lightweight reference while the real adapter wiring evolves

Behavior

- `generate` returns a deterministic response derived from the prompt
- `chat` returns a deterministic assistant message derived from the latest user message
- When no user message is present, `chat` falls back to a default reply
- `CarReviewAiRunnerFixture` implements the CNCF `AiRunner` contract directly
  and returns a stable CAR Review-shaped record candidate
- Fixture scenarios explicitly cover unknown/limitation, malformed output,
  empty output, unavailable provider, quota, timeout, cancellation, and
  retry-then-success without live provider access

Notes

- Fixtures are test-only and are not production adapters
- `ai.fixture.scenario` selects a deterministic outcome in executable specs
- The fixture proves caller-contract substitution without Gemini, OpenAI, or
  Gemma/Ollama credentials or network access
