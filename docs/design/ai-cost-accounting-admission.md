# AI Cost Accounting and Admission

status=accepted
scope=textus-ai phase-3 EA-03
updated_at=2026-07-18

## Decision

Textus AI uses operator-owned rate schedules expressed as integer microunits
per one million tokens. No provider price endpoint, account, currency amount,
or floating-point calculation is used at execution time.

```text
textus.ai.rate-schedules.<schedule>.input-microunits-per-million-tokens
textus.ai.rate-schedules.<schedule>.cached-input-microunits-per-million-tokens
textus.ai.rate-schedules.<schedule>.output-microunits-per-million-tokens
textus.ai.rate-schedules.<schedule>.reasoning-microunits-per-million-tokens

textus.ai.execution-classes.<class>.rate-schedule
textus.ai.execution-classes.<class>.max-cost-microunits
textus.ai.execution-classes.<class>.max-reasoning-tokens
```

A purpose or generic purpose may narrow `max-cost-microunits` and
`max-reasoning-tokens`, but may not replace the inherited schedule. The rate
schedule name is the stable operator identity. All four rate fields are
required and must be non-negative integers.

## Cost Admission

When `max-cost-microunits` is configured, Textus AI rejects a request before
provider/service resolution unless it can form an upper bound from:

- the EA-02 input estimate, charged at the greater of normal and cached input
  rates;
- an effective `max-output-tokens` when output has a non-zero rate; and
- an effective `max-reasoning-tokens` when reasoning has a non-zero rate.

Each token category is rounded up independently:

```text
microunits = ceil(tokens * rate_per_million / 1_000_000)
```

Missing rate data, a missing required output/reasoning bound, numeric overflow,
or an upper bound above the configured cost budget returns a structured failure
before the selected provider, external tool, or fallback path is invoked.
A provider-reported output or reasoning value above its configured bound is
rejected after execution; an omitted reasoning measurement is marked
`reasoning_limit_not_verified`.

## Post-Execution Accounting

If the selected provider reports all usage categories that have a non-zero
rate, Textus AI computes a `provider-reported-v1` cost. Cached input is
subtracted from normal input before normal input pricing is applied.

If reported usage is incomplete but an admission upper bound exists, Textus AI
records `configured-upper-bound-v1` and the `cost_estimated` limitation. If
neither basis is available, it records `cost_unavailable`. It never invents
zero usage, a provider price, or a monetary currency value.

## Publication Boundary

The response exposes effective policy and limitation facts but not rate schedule
identity, rate values, or cost amount. CallTree-only facts may contain:

- `ai.accounting.rate_schedule_id`;
- `ai.accounting.cost_microunits`; and
- `ai.accounting.cost_basis`.

Microunits are schedule-relative operator accounting quanta, not a currency
claim. CallTree does not contain an account identity, rate table, provider
price, prompt, raw evidence, or raw provider response.
