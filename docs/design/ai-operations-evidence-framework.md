# AI Operations Evidence Framework

status=accepted
scope=textus-ai phase-7 reusable operational evidence design
updated_at=2026-07-23

## Purpose

An AI execution is an operational choice, not only a generated response. The
framework records the selected purpose, route capability, bounded execution,
and application acceptance result so that a later comparison can explain a
cost or quality difference without retaining a prompt, model output, provider
payload, credential, endpoint, or account identity.

## Ownership

| Owner | Responsibility |
| --- | --- |
| Application | Registers application purposes, decomposes work, supplies admitted evidence, deterministically validates/assembles output, and records acceptance. |
| Textus AI | Resolves detailed purposes and runtime profiles, admits provider-standard tools, executes the selected route, and publishes safe `ai.observation.*` facts. |
| CNCF | Executes admitted Operation/MCP evidence outside Textus AI and preserves the caller context for assembled component calls. |
| Textus Corpus | Owns immutable comparison cases and case revisions. |
| Textus Experiment | Owns arms, runs, persisted observations, and later comparison summaries. |

The application caller never selects a provider, model, endpoint, CLI option,
or CNCF tool. It selects a registered application purpose. Textus AI resolves
the detailed purpose, execution class, runtime profile, and provider route.

## Execution Shape

```text
application purpose
  -> detailed purpose and required logical tools
  -> runtime-profile purpose binding or class default
  -> CNCF-admitted evidence preparation (when needed)
  -> one selected Textus AI execution
  -> deterministic application validation and assembly
  -> safe observation recording through Corpus / Experiment SPI
```

Provider-standard tools, such as `web_search`, are part of the selected AI
route. CNCF Operations and MCP tools are not provider tools: CNCF applies them
before the AI call and passes bounded evidence as ordinary input. The runner
does not receive a CNCF tool-selection request for this path.

## Observation Model

One observation identifies all of the following through safe facts or opaque
artifact references:

- Corpus revision and selected case;
- experiment arm and run;
- application purpose, effective detailed purpose, runtime profile, execution
  class, selected provider, and admitted provider-standard tools;
- execution plan and strategy references;
- provider, CNCF, latency, usage, and cost-state summaries;
- deterministic acceptance outcome; and
- execution, acceptance, and metric artifact references.

The one-step Sanpomap reference flow adds a `sanpomap.workflow.*` aggregate so
later staged plans can use the same metric shape. Missing values are represented
as unavailable state, never as fabricated zero measurements.

## Cost and Replay Policy

Ordinary application execution records only its selected route. It does not
fan out to providers for comparison. A comparison evaluation driver must pass
the operator gate with an enabled configuration, a positive bounded replay
count, and a positive bounded budget before it starts one comparison replay.
The gate does not reserve capacity or execute a provider route; a future
persisted comparison scheduler must define those lifecycle semantics explicitly.

Subscription-backed CLI execution and local execution do not fabricate API
monetary cost. The observation contract represents measured, estimated,
unavailable, and not-applicable cost states separately.

## Deferred Scope

This design does not claim that one provider/profile allocation is globally
optimal. Broad cross-provider measurement, statistical confidence, persisted
replay reservation, and long-running cost optimization consume accumulated
observations in a later phase.
