# Phase 2 Checklist - Purpose Policy Runtime

Status: active
Phase: [Phase 2 - Purpose Policy Runtime](phase-2.md)

## PP-01 Strict Purpose Contract

- [x] Define the CNCF AI runner field or equivalent that requires purpose resolution.
- [x] Specify structured failure semantics for absent, malformed, and unresolved required purposes.
- [x] Verify that compatible non-required-purpose requests retain current behavior.

## PP-02 Profile Policy Resolution

- [ ] Resolve provider, mode, engine, model, and logical tools from a purpose profile.
- [ ] Add bounded-policy configuration for maximum output tokens, timeout, retry, and concurrency.
- [ ] Validate profile configuration and reject ambiguous or incompatible selections.
- [ ] Define validated structured-output and prompt-policy configuration boundaries.

## PP-03 Admission And Observability

- [ ] Reject provider/tool incompatibility before invoking a provider.
- [ ] Reject required-purpose execution when no valid purpose profile resolves.
- [ ] Record effective purpose, profile selection, provider, model, tools, and bounded facts in safe CallTree attributes.
- [ ] Surface safe policy and tool-result summaries in response metadata where available.

## PP-04 Bounded Execution

- [ ] Enforce maximum output tokens for every provider path that supports it.
- [ ] Enforce timeout behavior through the supported CNCF runtime lifecycle.
- [ ] Enforce retry and concurrency policies through the supported CNCF runtime lifecycle.
- [ ] Document provider-specific limits and structured failures where enforcement is unavailable.

## PP-05 Executable Specification

- [ ] Add deterministic tests for successful resolution of each ArtScene integration purpose.
- [ ] Add deterministic tests for unresolved required purposes and invalid profile configuration.
- [ ] Add deterministic tests for unsupported logical tools and no-fallback behavior.
- [ ] Add deterministic tests for bounded execution policy propagation and enforcement.
- [ ] Update operator documentation with profile examples and safe observability expectations.
- [ ] Run review, fix actionable findings, and commit the completed phase.

## Deferred To Phase 3

- [ ] Managed-research orchestration.
- [ ] Input-token budgeting and provider-independent estimation.
- [ ] Cost admission, accounting, quotas, and reporting.
