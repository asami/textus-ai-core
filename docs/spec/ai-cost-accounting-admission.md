# AI Cost Accounting and Admission Specification

status=accepted
scope=textus-ai phase-3 EA-03
updated_at=2026-07-18

## Semantic Expectations

- A rate schedule requires four non-negative integer rate fields and has a
  stable operator-owned identifier.
- Cost arithmetic is integer microunits with per-category upward rounding.
- A cost budget requires a schedule and every non-zero output/reasoning rate
  requires a corresponding effective upper-token bound before provider
  execution.
- An over-budget request fails structurally before provider or fallback
  invocation.
- Provider-reported output or reasoning usage above an effective bound fails
  after execution; an omitted bounded reasoning measurement is explicit.
- Provider-reported usage produces a measured cost only when every charged
  usage category is reported and internally consistent.
- Incomplete reported usage uses the prior admission upper bound only as an
  explicit estimate; without that bound accounting is unavailable.
- Schedule identity, cost amount, and basis are CallTree-only. Response
  metadata never contains them.

## Executable Evidence

`AiCostAccountingSpec` covers integer rounding and impossible cached usage.
`TextusAiRunnerSpec` covers operator schedule resolution, measured CallTree
accounting, response redaction, pre-provider over-budget rejection, and missing
output-bound rejection. `AiExecutionFactsSpec` covers reported reasoning-limit
rejection and omitted-reasoning limitations.
