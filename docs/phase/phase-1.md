# Phase 1 - CAR Review AI Execution Foundation

status=open

## Purpose

Phase 1 makes Textus AI a safe, provider-neutral execution dependency for
bounded CAR Review. It builds on the released `0.2.0` runtime without making
Textus AI responsible for Review rules, canonical Review Reports, or release
gates.

## Scope

- Align development dependency coordinates with the active Cozy generator
  contract.
- Define normalized provenance, usage, limitation, and confidentiality facts
  for `AiRunner` responses.
- Add a deterministic AI provider for CAR Review executable specifications.
- Establish restrictive metadata and CallTree behavior for bounded evidence.
- Normalize local and commercial provider failure outcomes needed by CAR Review.
- Define and verify timeout, retry, cancellation, and concurrency boundaries
  at the CNCF execution boundary.

## Boundaries

- CBD Support owns evidence construction/redaction, prompt contracts, review
  policy, result admission, and release-gate decisions.
- Textus AI owns provider resolution, adapter execution, response
  normalization, and safe execution facts.
- CNCF owns the `AiRunner` SPI and Job execution lifecycle mechanisms.
- Web tools remain disabled for CAR Review purposes unless a separately
  authorized source and citation contract is introduced.

## Active Work Stack

- A (DONE): AR-00 - Align the development dependency line with the active Cozy
  generator contract.
- B (IN_PROGRESS): AR-01 - Normalize execution facts.
- C (OPEN): AR-02 - Add deterministic CAR Review provider fixtures.
- D (OPEN): AR-03 - Restrict trace and metadata publication.
- E (OPEN): AR-04 - Verify provider failure and lifecycle behavior.
- F (OPEN): AR-05 - Promote settled contracts and close the phase.

Detailed status and acceptance evidence are recorded in
`phase-1-checklist.md`.

## Completion Conditions

Phase 1 closes only when:

- provenance, usage, limitation, and confidentiality semantics have one
  documented representation;
- deterministic executable specifications cover success, limitation, malformed
  output, unavailable provider, quota, timeout, cancellation, and retry;
- CallTree and response metadata follow the restrictive confidentiality policy;
- Gemma/Ollama, OpenAI, and Gemini outcomes are normalized without implicit
  provider fallback;
- lifecycle limits are bounded and their unsupported cases remain explicit; and
- settled behavior is promoted from notes into design and specification
  documents with corresponding executable evidence.

## Working References

- `docs/strategy/textus-ai-development-strategy.md`
- `docs/notes/car-review-ai-runtime-design.md`
- `docs/journal/2026/07/2026-07-16-car-review-ai-runtime-requirements.md`
- `docs/spec/mock-ai-executable-spec.md`
