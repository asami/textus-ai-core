# AI Phase 3 Executable Evidence Specification

status=accepted
scope=textus-ai phase-3 ES-01
updated_at=2026-07-18

## Purpose

This specification maps every Phase 3 exit criterion to deterministic
executable evidence. It does not introduce a new runtime operation or a
second test harness; the named specifications are the authoritative evidence.

## Criterion Coverage

| Phase 3 criterion | Deterministic evidence | Required outcome |
| --- | --- | --- |
| Purpose and execution class resolve before provider execution | `TextusAiRunnerSpec` - `resolve a purpose and execution class through canonical class bindings` | Defaulted, matching, class-only, incompatible, and unconfigured selections resolve or fail structurally without exposing a model selector to callers. |
| Bounded execution succeeds with attributable input facts | `TextusAiRunnerSpec` - `admit a bounded execution-class request and publish its estimated input usage`; `AiInputTokenEstimatorSpec` | A bounded request reaches exactly one provider binding and records its documented estimate basis and limitation. |
| Input admission rejects every runner operation before execution | `TextusAiRunnerSpec` - `reject input budgets before generate record or chat provider execution` | `generate`, `generateRecord`, and `chat` fail structurally with no provider call. |
| Cost policy uses operator rates and safe accounting publication | `AiCostAccountingSpec`; `TextusAiRunnerSpec` - `account provider-reported usage through an operator rate schedule without exposing cost in the response` | Arithmetic is deterministic; response metadata excludes schedule and cost values; CallTree alone contains the operator accounting summary. |
| Cost rejection and incomplete accounting are explicit | `TextusAiRunnerSpec` - `reject a cost budget before provider execution and reject missing cost bounds structurally`; `reject an incomplete operator rate schedule before provider execution` | Over-budget and invalid configurations fail before provider or fallback execution. |
| Unknown or partial provider usage remains explicit | `TextusAiRunnerSpec` - `retain a configured cost upper bound as an explicit estimate when provider usage is unavailable`; `AiExecutionFactsSpec` - `record unavailable usage and pricing without synthesizing a numeric value` | The runtime records `cost_estimated` only with an admissible upper bound and never fabricates zero usage or price. |
| Output and reasoning bounds remain accountable | `AiExecutionFactsSpec` - `reject provider reasoning usage above the configured maximum`; `record an unverified reasoning limit when a bounded response omits reasoning usage`; `TextusAiRunnerSpec` - `reject provider responses that exceed an effective output-token maximum` | Reported excess fails structurally; unavailable measurements are represented as stable limitations. |
| No implicit provider or application-method fallback | Input- and cost-rejection scenarios above assert zero provider calls; `AiProviderAdmissionSpec` and `CodexRuntimeProviderSpec` assert unsupported capabilities fail before binding or process selection. | Budget, capability, and configuration failure never select another provider, tool, purpose, process, or application method. |
| Workflow remains outside the runtime | [AI Runtime Workflow Boundary](../design/ai-runtime-workflow-boundary.md) | No workflow operation, CML declaration, state store, scheduler, or orchestration adapter is added in Phase 3. |

## Validation

The phase-level validation command is:

```sh
sbt --batch test
```

CAR structural validation uses:

```sh
python3 /Users/asami/.codex/skills/cncf-car-lint/scripts/cncf_car_lint.py \
  /Users/asami/src/dev2026/textus-ai
```

Live provider requests, pricing verification, and external workflow execution
are operational checks. They are not substitutes for the deterministic
evidence specified here.
