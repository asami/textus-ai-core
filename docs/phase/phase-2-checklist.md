# Phase 2 Checklist - Purpose Policy Runtime

Status: complete
Phase: [Phase 2 - Purpose Policy Runtime](phase-2.md)

## PP-01 Strict Purpose Contract

- [x] Define the CNCF AI runner field or equivalent that requires purpose resolution.
- [x] Specify structured failure semantics for absent, malformed, and unresolved required purposes.
- [x] Verify that compatible non-required-purpose requests retain current behavior.

## PP-02 Profile Policy Resolution

- [x] Resolve provider, mode, engine, model, and logical tools from a purpose profile.
- [x] Add profile defaults for maximum output tokens, timeout, and record retry.
- [x] Validate named model profiles and bounded-policy values before provider binding.
- [x] Add concurrency policy resolution and its supported enforcement boundary.
- [x] Validate known provider-local model constraints and logical-tool compatibility before provider binding.
- [x] Define validated structured-output and prompt-policy configuration boundaries.

## PP-03 Admission And Observability

- [x] Reject provider/tool incompatibility before invoking a provider.
- [x] Reject required-purpose execution when no valid purpose profile resolves.
- [x] Record effective purpose, profile selection, provider, model, tools, and bounded facts in safe CallTree attributes.
- [x] Surface safe policy and tool-result summaries in response metadata where available.

## PP-04 Bounded Execution

- [x] Enforce maximum output tokens for every provider path that supports it.
- [x] Enforce timeout behavior through the supported CNCF runtime lifecycle.
- [x] Enforce record-retry policy through the supported CNCF runtime lifecycle.
- [x] Enforce concurrency policy through the supported CNCF runtime admission lifecycle.
- [x] Document provider-specific limits and structured failures where enforcement is unavailable.

## PP-05 Executable Specification

- [x] Add deterministic tests for successful resolution of each ArtScene integration purpose.
- [x] Add deterministic tests for unresolved required purposes and invalid profile configuration.
- [x] Add deterministic tests for unsupported logical tools and no-fallback behavior.
- [x] Add deterministic tests for bounded policy propagation, record-retry, and concurrency-admission enforcement.
- [x] Update operator documentation with profile examples and safe observability expectations.
- [x] Run review, fix actionable findings, and commit the completed phase.

## PP-06 Codex CLI Purpose Web Tools

- [x] Resolve Codex model and reasoning policy only from an approved purpose/model profile.
- [x] Map `url_context` and `web_search` to an admitted, finite Codex CLI capability profile.
- [x] Reject unavailable Codex Web capability and unsupported policy fields structurally, without provider or fetch-method fallback.
- [x] Record purpose, profile, effective model/reasoning, requested/enabled tools, and bounded result or error facts safely.
- [x] Add deterministic fake-process specifications for Codex command construction, tool admission, and no-fallback behavior.
- [x] Document the purpose-profile configuration and optional live operational smoke boundary.

## PP-07 Generic Purpose And Level Policy

- [x] Define logical profiles for deep deliberation, standard deliberation, standard work, and simple work.
- [x] Bind each logical level to approved provider/model/reasoning configuration without exposing those values to application callers.
- [x] Define generic purposes for Web research, specification analysis, implementation work, structured extraction, and local-source extraction.
- [x] Add explicit `base-purpose` inheritance from an application purpose to a generic purpose, with deterministic policy precedence and no implicit fallback.
- [x] Reject an application profile that broadens an inherited provider, model, reasoning, tool, or execution policy without explicit operator approval.
- [x] Add deterministic specifications for generic-purpose resolution, application-purpose narrowing, and rejected broadening.
- [x] Document generic purpose and logical-level configuration examples.

## Deferred To Phase 3

- [ ] Managed-research orchestration.
- [ ] Input-token budgeting and provider-independent estimation.
- [ ] Cost admission, accounting, quotas, and reporting.
