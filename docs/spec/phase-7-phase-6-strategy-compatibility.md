# Phase 7 Phase 6 Strategy Compatibility

status=accepted
scope=textus-ai phase-7 SP-06
updated_at=2026-07-23

## Decision

Phase 6 `tool-grounded` and `prompt-grounded` remain supported compatibility
strategies for existing runtime profiles. They are not the integration path for
new Phase 7 application flows.

New Phase 7 application flows compose admitted CNCF Operation and MCP evidence
outside Textus AI, then pass bounded evidence as ordinary AI input. The AI
runner receives no CNCF Operation/MCP selection request. Provider-standard
tools remain runtime-owned logical capabilities and continue to be admitted by
Textus AI.

## Retirement Boundary

No removal is scheduled in Phase 7. Retirement requires a separately reviewed
consumer inventory, a migration path for every active direct strategy, and
replacement executable evidence. Until then, compatibility paths retain their
existing bounds and are not extended with new application-specific behavior.

## Evidence

Sanpomap `researchScenarioDsl` is the Phase 7 reference flow. Its assembled
specification installs admitted CNCF evidence sources before the AI call and
asserts that `AiRunnerRequirement.tools` is empty. Existing Textus AI strategy
specifications continue to cover the retained Phase 6 behavior.
