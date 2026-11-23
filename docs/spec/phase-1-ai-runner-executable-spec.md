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

## Structured JSON Array Contract Addendum (2026-10-18)

This addendum records the narrow producer normalization and array-validation
contract added under `P61-ADMISSION-AI-ARRAY-006`. The accepted Phase 1 status
and historical evidence above remain unchanged. The scenarios below are authored
executable specifications; this document does not claim their execution, full
validation, or a successful live provider review.

Structured results use a Core `Record`. Null-valued object properties are
omitted at every depth before validation. Empty arrays, empty objects and
non-null values are preserved. Arrays become vectors, and a non-object non-null
root is retained under the `value` field. A null root is invalid. A null array
element stays at its original index and fails element validation there.

Required presence is separate from content. An explicitly present empty array
satisfies a required field. A missing field or null fails required presence,
and required strings must still be nonblank. Array declarations check shape
separately: nulls, objects, and scalars are not arrays.

The supported array-schema subset is:

- Legacy `arrays` entries use `name` or `field`. Every declared legacy array
  is required, even without a root `required` entry. When its item `required`
  fields are declared, every item must be an object containing those fields.
- Standard `properties` entries with `type: array` validate present array
  fields. Null is equivalent to omission for an object property. Arrays listed
  in `required` must be present and may be empty. A present non-array fails.
- Standard `items` with `type: object` requires object items and recursively
  checks their required fields and declared array properties. `items` with
  `type: array` recursively checks array shape and its items. Empty arrays
  remain valid at every level.

This subset adds no general JSON-schema engine, scalar item type enforcement,
minimum-size checks, enums, or fallback. Existing required scalar/item checks
and bounded retry policy remain in place; schema mismatches are terminal.
Provider selection, application-purpose resolution, and the normal UnitOfWork
route remain unchanged.

All new scenarios are in the `TextusAiRunnerSpec` section
`explicit JSON values and declared arrays`. They use the actual registered
Gemma runtime binding with deterministic `_FakeHttpDriver` responses and make
no live HTTP/provider requests.

| Scenario name or family | Given / When / Then contract |
| --- | --- |
| `preserve clean CBD review arrays through the registered Gemma binding and JSON encoding` | Given CBD-shaped checks and empty findings/modelSubjects/limitations, when the binding normalizes and the Record is encoded, then the JSON roundtrip preserves every array. |
| `preserve explicit JSON values in …` | Given explicit empty/null/object values or a non-object root, when normalized and encoded, then values remain present, using a value envelope where needed. |
| `accept an explicitly empty required legacy array` | Given a required legacy array with required item fields, when the empty array is validated, then presence succeeds without invented items. |
| `reject … in a legacy array declared by … without retry` | Given either legacy alias and absent/null/non-array values, malformed items, or missing/null/blank item fields, when validated, then a terminal schema mismatch retains applicable missing-field diagnostics. |
| `reject a CBD-shaped candidate with … without retry` | Given standard properties/items declarations and malformed root/nested arrays, malformed object items, or blank required scalars, when validated, then the mismatch is terminal. |
| `distinguish … optional standard arrays from required presence` | Given an optional array declaration, when absent/empty/null/non-array candidates are validated, then only absence and an actual array succeed. |
| `validate recursive array items for … arrays` | Given array items declared as arrays of named objects, when validated recursively, then empty arrays and valid object items succeed while malformed shapes or missing required item fields fail. |

Focused validation of `TextusAiRunnerSpec`, normal `cozyBuildCar`, independent
review, and the separately admitted normal C4 execution remain caller-owned
evidence. These deterministic scenarios do not establish live provider execution.
