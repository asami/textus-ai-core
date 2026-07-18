# Phase 2 Checklist - Purpose Policy Runtime

Status: active
Phase: [Phase 2 - Purpose Policy Runtime](phase-2.md)

## PP-01 Strict Purpose Contract

- [x] Define the CNCF AI runner field or equivalent that requires purpose resolution.
- [x] Specify structured failure semantics for absent, malformed, and unresolved required purposes.
- [x] Verify that compatible non-required-purpose requests retain current behavior.

## PP-02 Profile Policy Resolution

- [x] Resolve provider, mode, engine, model, and logical tools from a purpose profile.
- [x] Add profile defaults for maximum output tokens, timeout, and record retry.
- [x] Validate named model profiles and bounded-policy values before provider binding.
- [ ] Add concurrency policy resolution and its supported enforcement boundary.
- [x] Validate known provider-local model constraints and logical-tool compatibility before provider binding.
- [x] Define validated structured-output and prompt-policy configuration boundaries.

## PP-03 Admission And Observability

- [x] Reject provider/tool incompatibility before invoking a provider.
- [x] Reject required-purpose execution when no valid purpose profile resolves.
- [x] Record effective purpose, profile selection, provider, model, tools, and bounded facts in safe CallTree attributes.
- [x] Surface safe policy and tool-result summaries in response metadata where available.

## PP-04 Bounded Execution

- [ ] Enforce maximum output tokens for every provider path that supports it.
- [ ] Enforce timeout behavior through the supported CNCF runtime lifecycle.
- [ ] Enforce retry and concurrency policies through the supported CNCF runtime lifecycle.
- [ ] Document provider-specific limits and structured failures where enforcement is unavailable.

## PP-05 Executable Specification

- [x] Add deterministic tests for successful resolution of each ArtScene integration purpose.
- [x] Add deterministic tests for unresolved required purposes and invalid profile configuration.
- [x] Add deterministic tests for unsupported logical tools and no-fallback behavior.
- [x] Add deterministic tests for bounded execution policy propagation and record-retry enforcement.
- [x] Update operator documentation with profile examples and safe observability expectations.
- [ ] Run review, fix actionable findings, and commit the completed phase.

## Deferred To Phase 3

- [ ] Managed-research orchestration.
- [ ] Input-token budgeting and provider-independent estimation.
- [ ] Cost admission, accounting, quotas, and reporting.
