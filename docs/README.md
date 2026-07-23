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
- `docs/design/` contains stable AI runtime decisions and boundaries
- `docs/journal/` contains chronological entries
- `docs/notes/` contains exploratory AI component notes
- `docs/notes/ai-component-basic-design.md` records the current TextusAi runtime idea
- `docs/notes/ai-component-dev-environment.md` records implementation-oriented TextusAi setup notes
- `docs/notes/car-review-ai-runtime-design.md` explores the bounded AI runtime design for CAR Review
- `docs/notes/ai-provider-feature-cost-evaluation.md` explores the Phase 7 cross-route evaluation evidence model
- `docs/notes/sanpomap-ai-usage-profiles.md` classifies Sanpomap AI steps into reusable detailed purposes and provider-neutral profiles
- `docs/spec/ai-execution-class-resolution.md` records execution-class selection and migration behavior
- `docs/spec/ai-execution-facts.md` records source-qualified usage and safe accounting metadata behavior
- `docs/spec/ai-input-budget-admission.md` records execution-class input admission behavior
- `docs/spec/ai-cost-accounting-admission.md` records operator rate and cost admission behavior
- `docs/spec/ai-phase-3-executable-evidence.md` maps Phase 3 exit criteria to deterministic specifications
- `docs/design/ai-runtime-workflow-boundary.md` records the workflow ownership boundary and later-phase entry contract
- `docs/phase/phase-3-closure.md` records the completed Phase 3 evidence and deferred work
- `docs/phase/phase-4.md` records the completed runtime-profile and application-purpose registration migration
- `docs/phase/phase-4-checklist.md` records its implementation ledger
- `docs/phase/phase-4-closure.md` records the completed runtime-profile evidence
- `docs/spec/ai-application-purpose-registration.md` records the executable registration contract
- `docs/journal/2026/07/2026-07-18-application-purpose-registration-handoff.md` records Sanpomap and GeoResolver registration adoption boundaries
- `docs/spec/mock-ai-executable-spec.md` records the deterministic mock AI executable specification

## Rules

- Keep journal entries non-normative
- Keep design documents stable and decision-oriented
- Promote exploratory material from notes to design when it becomes stable
- Add specs only when behavior needs executable semantic coverage
