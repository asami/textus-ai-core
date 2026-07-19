# Phase 1 AI Runner Executable Specification

status=accepted
scope=textus-ai CAR Review execution foundation
updated_at=2026-07-18

## Purpose

This specification defines the executable evidence for Textus AI Phase 1. It
verifies provider-neutral AI execution required by bounded CAR Review without
making Textus AI responsible for Review policy, evidence admission, or release
decisions.

All scenarios run without provider credentials or network access. The fixture
and HTTP-driver seams are test-only evidence; they are not production provider
adapters.

## Contract Matrix

| Contract | Executable evidence | Expected behavior |
| --- | --- | --- |
| Effective execution facts | `AiExecutionFactsSpec` | Selected provider values win over spoofed metadata; unknown values are omitted. |
| Usage and response normalization | `AiExecutionFactsSpec`, `TextusAiRunnerSpec` | Response identity, finish reason, and non-negative provider usage are normalized only when supplied. |
| Confidentiality | `TextusAiRunnerSpec` CallTree scenarios | CallTree records retain bounded counts and SHA-256 digests, never prompts, responses, request metadata, or provider error bodies. |
| Deterministic substitution | `CarReviewAiRunnerFixtureSpec` | A test-only `AiRunner` produces a stable CAR Review-shaped record through the CNCF SPI. |
| Limitation and failure outcomes | `CarReviewAiRunnerFixtureSpec`, `TextusAiRunnerSpec` | Unknown facts, malformed output, empty output, unavailable provider, quota, timeout, and unsupported cancellation are explicit and deterministic. |
| Retry boundary | `CarReviewAiRunnerFixtureSpec`, `TextusAiRunnerSpec` | Retry-then-success is observable; production structured generation retries only empty or timeout-like outcomes within its bounded retry limit. |
| Provider behavior | `TextusAiRunnerSpec` | Google/Gemini, OpenAI, and Gemma/Ollama expose only safe response facts; HTTP failures use stable categories without body disclosure. |
| Gemma registration | `TextusAiRunnerSpec` Gemma runtime-binding scenario | The registered Gemma binding performs schema-valid structured generation and preserves a categorized terminal failure. |
| Fallback policy | `AiExecutionFactsSpec`, `TextusAiRunnerSpec` | Provider and model selection are not changed implicitly; Gemma endpoint fallback requires explicit `GemmaRuntimeConfig.fallbackEndpoint`. |
| Controlled Codex execution | `CodexRuntimeProviderSpec`, CNCF `ProcessExecutionModelSpec`, CNCF `ProcessExecutionWorkAreaSpec` | `generate`, `chat`, and schema-constrained `generateRecord` use an admitted logical `codex-cli` capability, bounded stdin/output, and a bounded `schema.json` WorkArea input without direct host-process access. |
| Codex component-scope ownership | `ComponentFactorySpec` sibling-caller scenario | A caller in a sibling component scope needs no Codex driver or admission; the provider resolves managed process execution through the Textus AI component scope that owns the capability. |
| Codex terminal boundaries | `CodexRuntimeProviderSpec` | Unavailable CLI, non-zero exit, timeout, cancellation, output limit, and unsupported tools produce explicit failures without exposing prompt, stdout, stderr, credentials, or account identity. |

## Fixture Scenarios

`CarReviewAiRunnerFixture` selects its deterministic scenario with the
test-only `ai.fixture.scenario` request property.

| Scenario | Expected result |
| --- | --- |
| absent | Schema-shaped record candidate with normalized fixture facts |
| `unknown` | Successful deterministic response with `provider_identity_unavailable` and `usage_unavailable` limitations |
| `malformed` | Explicit malformed structured-output failure |
| `empty` | Explicit empty structured-output failure |
| `unavailable` | Explicit provider-unavailable failure |
| `quota` | Explicit quota-exhausted failure |
| `timeout` | Explicit provider-timeout failure |
| `cancelled` | Explicit `cancellation_not_propagated` boundary failure |
| `retry-then-success` | First record request fails with timeout; the next request succeeds with retry count `1` |

## Lifecycle Boundary

The synchronous adapter boundary does not propagate caller cancellation or
enforce a component-wide concurrency budget. Textus AI therefore exposes the
stable limitation codes `cancellation_not_propagated` and
`concurrency_not_enforced` on normal runner responses. CNCF Job lifecycle
controls remain the required owner for future propagation and enforcement.

No test scenario permits implicit local-to-commercial provider fallback or an
implicit model change. Provider-specific endpoint fallback is allowed only
when resolved runtime configuration explicitly declares it.

## Codex Process Boundary

The Codex adapter is an ordinary `AiRunner` provider. It requests the
runtime-granted `codex-cli` capability and submits only a bounded prompt,
registered dynamic arguments, and, for record generation, a bounded logical
`schema.json` input file. It does not select an executable, invoke a driver,
write a host file, construct a shell command, or read Codex authentication.

When explicitly enabled, Textus AI runtime assembly installs the fixed
`codex exec --sandbox read-only --ephemeral --skip-git-repo-check` capability
definition and grant, plus CNCF's generic local Process Execution driver, in
its component scope. The absolute executable location, empty environment
allowlist, finite Process Execution limits, exact permitted provider suffixes,
and `schema.json` input path are runtime-owned. The CNCF UnitOfWork owns the
per-invocation WorkArea and process-handle lifecycle. The local driver's launch
workers are shared daemon runtime infrastructure. Tests use
`ProcessExecutionTestProfile`;
they never start a live Codex CLI or call a network service.

Profiles that select `gpt-5.6-sol`, `gpt-5.6-terra`, or `gpt-5.6-luna` also
receive a separate, fixed `codex --version` capability. Before submitting a
prompt, the provider runs that admitted capability and requires Codex CLI
version `0.144.0` or later. A missing, malformed, failing, or older version is
a structured configuration failure; it never falls back to another model or
executes the prompt capability.

The provider binding retains the caller-facing public `create` and Codex
extension-point construction contracts. During Textus AI component assembly it
records the owning component internally. The provider therefore uses that
component's execution context for managed-process admission even when the
`AiRunner` request originates from a sibling CAR scope.

## Validation Command

Run the complete Phase 1 executable specification with:

```bash
sbt --batch test
```

The suite must pass without live Gemini, OpenAI, Gemma, or Ollama access.
