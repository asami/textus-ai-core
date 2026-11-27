# textus-ai task API and sm-workflow integration (proposal)

Date: 2026-11-18
Status: Design journal / proposal, not implemented

## Intent

sm-workflow should consume multiple AI providers through **textus-ai**, not implement separate OpenAI, Gemini, GitHub Models/Copilot, or local-LLM integrations. Reuse provider-specific strengths and included/free quotas where terms and API access permit. API credits and subscription/agent quotas are distinct; do not assume a Copilot subscription quota is programmatically available.

## Responsibility boundary

**sm-workflow owns:** deterministic Context Packaging, problem completeness and size checks, goal/step and fix orchestration, tool/workspace execution, Candidate–Admission, tests/lint, Continuation, Take Over and final acceptance.

**textus-ai owns:** provider adapters, credentials, model/capability catalog, inference/task API, provider-specific input formatting, quota/budget policies, usage accounting, eligible provider selection and bounded provider-level fallback. It must remain useful to other clients such as KnowledgeHub and EAI; no knowledge of sm-workflow-specific Goal Phase or Admission internals.

Routing is two-stage: sm-workflow declares task requirements and execution constraints; textus-ai chooses a qualifying model/provider. Codex agent execution may remain in sm-workflow rather than pretending it is equivalent to a stateless inference API. Clarify this boundary against actual implementations.

## Proposed API shape (illustrative, not existing contract)

- `infer(InferenceRequest): Consequence[InferenceResult]`: general inference.
- `execute(AiTaskRequest): Consequence[AiTaskResult]`: task-oriented, with task kind (code fix, review, classification), Context Package reference/payload, structured-output schema, required capabilities, reasoning level, tool requirements, latency and budget policy.
- Result: provider/model, response or patch candidate, token usage, estimated/actual billable cost, quota category, finish/decline reason, trace identifiers and reproducibility metadata.

Budget policy should express `preferIncludedQuota`, `allowPaidOverage=false`, and per-task limits. Avoid claiming exact promotional-credit balances without a verified provider mechanism. Routing must not make expensive AI calls merely to decide which AI to call.

## Context Package integration

sm-workflow constructs a bounded, reproducible, self-contained collection of problem information deterministically. Multiple files are fine if the entire necessary context fits. Experimental thresholds: 64 KiB standard, 1 MiB extended, subject to token/window and cost checks. Local LLM or Luna may exceptionally help packaging, never expensive default inference. textus-ai converts the package into provider-specific requests but does not rediscover project context.

## Execution and safety

For bounded code fixes, textus-ai returns a candidate patch or structured result; sm-workflow validates/apply/tests/lints and controls Admission. Providers must not obtain implicit unrestricted filesystem, git, shell or commit privileges. Missing context, unsupported tools, budget exhaustion or failed bounded attempts should return explicit decline/failure for sm-workflow Take Over. Provider switch should preserve Continuation and evidence at action/step boundaries.

## Provider policies and measurement

Select by task capability, context limit, measured quality, latency, available quota and marginal cost. OpenAI promotional API credit is an attractive initial resource, Gemini may be preferred for tasks where measured performance is stronger, and free-only GitHub offerings can be used only after confirming eligible programmatic interfaces and billing. Codex Terra/Sol remain agent options.

Measure accepted output per actual cost, task success, retry count, latency, quota consumption, packaging overhead and provider declines. Never equate subscription quota and API credit without empirical measurement.

## Implementation investigation

1. Inspect existing textus-ai-core APIs and naming; determine whether the generic/task-oriented layering belongs there or in another textus-ai module.
2. Define request/result schemas and provider capability/budget metadata without sm-workflow concepts.
3. Connect sm-workflow Context Package and deterministic routing requirements.
4. Pilot small self-contained code fixes and structured judgements; compare API/Gemini/local/Codex on equivalent evidence.
5. Validate each provider's actual free/included quota and safe no-overage behavior before enabling it.

Related: sm-workflow journal `docs/journal/2026/2026-11-18-context-packaging-provider-routing.md`.
