# Documentation Rules

This repository follows the document lifecycle defined by `ai/directive/core`.

## Document Types

- `journal/` records chronological events and observations
- `design/` records stable architectural decisions and boundaries
- `notes/` records exploratory thinking and hypotheses
- `spec/` records executable semantic expectations

## Current Layout

- `docs/strategy/` contains the Textus AI development direction and phase ordering
- `docs/phase/` contains the current engineering dashboard and checklist ledger
- `docs/journal/` contains chronological entries
- `docs/notes/` contains exploratory AI component notes
- `docs/notes/ai-component-basic-design.md` records the current TextusAi runtime idea
- `docs/notes/ai-component-dev-environment.md` records implementation-oriented TextusAi setup notes
- `docs/notes/car-review-ai-runtime-design.md` explores the bounded AI runtime design for CAR Review
- `docs/spec/mock-ai-executable-spec.md` records the deterministic mock AI executable specification

## Rules

- Keep journal entries non-normative
- Keep design documents stable and decision-oriented
- Promote exploratory material from notes to design when it becomes stable
- Add specs only when behavior needs executable semantic coverage
