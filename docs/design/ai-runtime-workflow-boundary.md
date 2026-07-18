# AI Runtime Workflow Boundary

status=accepted
scope=textus-ai phase-3 WB-01
updated_at=2026-07-18

## Decision

Textus AI does not provide a runtime workflow in Phase 3. The supported
component surface remains the single-operation `generate`, `chat`, and
`generateRecord` calls selected through `AiRunnerRequirement`.

Current callers prove a policy-resolution and bounded-execution need, not a
runtime-orchestration need. In particular, an application may name a logical
purpose such as Web research, but that purpose selects an approved execution
profile. It does not declare a multi-step procedure or grant a workflow.

## Ownership Boundary

The application owns:

- evidence collection, redaction, and admission;
- source authority, comparison, and reconciliation;
- sequencing of retrieval, AI calls, and deterministic processing;
- fallback between its own fetch methods or domain strategies; and
- acceptance, persistence, and presentation of the final domain result.

Textus AI owns one admitted AI operation: purpose/profile resolution, provider
selection, tool admission, configured execution limits, schema normalization,
safe execution facts, and a structured terminal outcome. A failure does not
cause Textus AI to select another purpose, provider, model, tool, or
application method implicitly.

## Why A Workflow Is Not Added

The existing `AiRunner` contract carries no application workflow identifier,
step plan, source-authority policy, result-reconciliation rule, or durable
workflow state. Adding a loop behind a purpose would therefore make the
runtime silently own application policy and would make aggregate cost, retry,
and cancellation semantics ambiguous.

The prospective ArtScene `ai_managed_research` fetch method is an application
orchestration candidate. It is not evidence that the generic runtime should
run a managed-research procedure. ArtScene can compose admitted single
operations and preserve its own retrieval and fallback policy until a concrete
cross-application workflow contract is accepted.

## Entry Contract For A Later Workflow Phase

A later phase may add runtime workflow support only after a named caller
supplies an approved contract containing all of the following.

| Concern | Required contract |
| --- | --- |
| Public identity | A provider-neutral workflow identifier and version, distinct from `purpose` and model profile names. |
| Inputs | Typed, bounded caller input and explicit ownership of evidence admission; no raw provider command or unrestricted retrieval field. |
| Steps | An operator-admitted finite step graph with explicit permitted ports and external capabilities. |
| State | Owner, durability, resumability, idempotency, and retention semantics. The runtime must not create hidden durable state. |
| Budget | Aggregate input, output, reasoning, timeout, concurrency, and operator-rate cost budgets across every step, including the basis for unknown provider usage. |
| Lifecycle | Explicit start, cancellation, timeout, retry, partial-result, and terminal-state behavior through CNCF execution facilities. |
| Failure | Structured per-step and aggregate failures; no implicit provider, tool, or application-method fallback. |
| Observability | CallTree-safe workflow and step identities, aggregate facts, and limitations without evidence, prompts, credentials, URLs, raw responses, or rate values. |
| Result | A typed or schema-validated candidate result whose application acceptance remains outside the runtime unless the future contract explicitly changes that ownership. |

The later phase must declare the CNCF Port and Extension/Variation Point used
for each external step. It must not bypass component-owned admission with a
raw process, HTTP client, credential, or arbitrary network capability.

## Required Executable Evidence Before Implementation

Workflow code is not eligible until deterministic executable specifications
cover:

- admission of a valid finite workflow and rejection of an unconfigured or
  broadened workflow;
- aggregate budget rejection before the first external step and before each
  subsequent admitted step;
- cancellation, timeout, retry, and terminal-state propagation;
- no implicit provider, tool, or application-method fallback;
- redacted CallTree facts for successful, partial, and failed workflows; and
- application-owned evidence admission and final-result acceptance boundaries.

Live provider checks remain operational validation, not workflow executable
specification evidence.

## Phase 3 Consequence

Phase 3 records this boundary and does not add a CML operation, workflow
command, state store, scheduler, or orchestration adapter. A later phase must
reopen the design from a concrete caller contract rather than promoting a
purpose name into a procedure implicitly.
