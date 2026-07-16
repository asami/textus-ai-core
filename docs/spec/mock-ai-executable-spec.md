# Mock AI Executable Specification

status=accepted
scope=textus-ai deterministic test fixture
updated_at=2026-07-16

## Purpose

`CarReviewAiRunnerFixture` is a deterministic, test-only implementation of
the CNCF `AiRunner` SPI. It keeps `generate`, `generateRecord`, and `chat`
behavior verifiable without Gemini, OpenAI, Gemma, or Ollama credentials and
without network access.

The fixture returns a stable CAR Review-shaped candidate and supports explicit
limitation, malformed-output, empty-output, unavailable-provider, quota,
timeout, cancellation-boundary, and retry-then-success scenarios through the
`ai.fixture.scenario` request property.

## Scope

The fixture is executable-specification infrastructure, not a production
adapter. The Phase 1 contract matrix and scenario outcomes are normative in
[Phase 1 AI Runner Executable Specification](phase-1-ai-runner-executable-spec.md).
