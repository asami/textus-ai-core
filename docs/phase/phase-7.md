# Phase 7 - Cost-Aware AI Operations Evidence Framework

status=open
planned_at=2026-07-23
strategy=[Textus AI Development Strategy](../strategy/textus-ai-development-strategy.md)
checklist=[Phase 7 Checklist](phase-7-checklist.md)

## Purpose

Use Sanpomap as the development driver to build a reusable evidence framework
for cost-aware AI operations. Start with combinations that are reasonable from
provider specifications and current implementation constraints, use them in
normal application work, and retain comparable capability, acceptance, usage,
latency, and cost evidence as the work proceeds.

The framework also increases purpose resolution. Sanpomap AI steps resolve
reusable detailed Textus AI purposes, and runtime profiles may bind those
purposes before using execution-class defaults. Purpose-standard-tool
requirements therefore participate in deterministic route selection and its
observations.

The Phase 7 CNCF evidence-execution boundary is a target state, not a claim
about the current Phase 6 implementation. Current `tool-grounded` and
`prompt-grounded` strategies still let Textus AI create and invoke CNCF
Operation/MCP sockets. SP-00 introduces the CNCF-composed evidence path for
Phase 7 application flows before detailed-purpose routing relies on it. The
legacy strategies remain available until their compatibility or retirement is
decided explicitly; they are not extended as the Phase 7 integration path.

SP-00 is implemented in `textus-sanpomap` on 2026-07-23. Its
`researchScenarioDsl` flow reads fixed bootstrap configuration for an admitted
Operation tool and/or MCP tool, consumes the released CNCF
`OperationToolSocket` / `McpClientSocket` contracts, bounds returned text, and
passes it as ordinary prompt evidence. It sends an empty `AiRunnerRequirement`
tool set. No CNCF change was required because the released Ports already expose
the required application-owned invocation scope. Textus AI rejects a request
marked `cncf.evidence.composed=true` before provider execution when its resolved
strategy would invoke a runner-owned Operation or MCP socket.

Phase 7 does not perform an exhaustive provider benchmark or claim a final
optimal allocation. Broad replay across every API and CLI route is deferred
until the accumulated evidence justifies its cost.

## Boundaries

- Sanpomap owns workflow decomposition, source policy, deterministic DSL
  compilation, domain validation, and final acceptance.
- Textus AI owns purpose/profile resolution, bounded provider execution, and
  safe execution facts.
- Provider-standard tools are part of AI route capability and logic selection.
  Their admission, invocation count, returned-context usage, and provider tool
  charge are comparison facts.
- CNCF Operations and MCP tools are applied by CNCF-owned execution before or
  around an AI call. They supply admitted evidence to the AI operation and are
  not reclassified as provider-standard AI tools. This is the required Phase 7
  path after SP-00, rather than a description of the current Phase 6 path.
- Textus Corpus owns reviewed, immutable comparison inputs and expected
  evidence references. Textus Experiment owns comparison arms, runs,
  observations, and aggregate outcome records.
- The evaluation driver consumes Textus Corpus and Textus Experiment through
  their assembled CNCF component SPI surfaces. Textus AI does not import their
  implementation classes or become an experiment database.
- Application callers select registered purposes, never providers, models,
  CLI options, endpoints, or fallback behavior.
- Hybrid execution is explicit application composition, not implicit provider
  fallback.
- Evidence collection observes the selected execution. It does not fan one
  application request out to every provider or duplicate a paid request by
  default.
- The current Google managed-CLI implementation is `antigravity-cli`.
  Supporting a distinct Gemini CLI executable requires a separate binding and
  is not implied by the `gemini` API profile.
- Provider rate schedules are versioned operator evidence. A current monetary
  estimate is never treated as a provider invoice, and subscription-backed CLI
  use is not assigned a fabricated per-call price.
- An execution-plan arm may describe workflow structure as well as a route.
  One large prompt, staged bounded prompts, and Gemma-plus-managed execution are
  distinct strategies when their call and token shapes differ.
- A Sanpomap application purpose resolves one detailed Textus AI purpose. The
  purpose definition is the reusable provider-neutral usage profile and owns
  grounding, output, prompt-shape, and provider-standard tool requirements.
  The runtime profile still owns provider and model selection.

## Repository Ownership

- `textus-ai` owns detailed-purpose profiles, purpose-specific runtime
  bindings, provider-standard tool admission, safe execution facts, and the
  client side of the CNCF evidence-execution contract.
- `goldenport-cncf` owns any generic Port or composed-execution contract needed
  to invoke Operations or MCP tools outside the AI runner. It changes only when
  the existing CNCF contract cannot express the Phase 7 boundary.
- `textus-sanpomap` owns application workflow decomposition, registered
  internal application purposes, deterministic assembly, acceptance, and use
  of the CNCF evidence-execution contract.
- `textus-corpus` owns immutable cases and sanitized evidence references.
  `textus-experiment` owns experiment arms, runs, observations, and summaries.
  Both are consumed only through their assembled component SPI surfaces.

The Phase 7 implementation plan must name the repository changed by each
slice. A Textus AI release does not include sibling repository changes.

## Work

- Work A: SP-00 - establish the CNCF-composed evidence-execution boundary and
  prove that a Phase 7 application flow does not select or invoke an
  Operation/MCP tool through Textus AI.
- Work B: SP-01 and SP-02 - define the safe observation framework and connect
  it through Textus Corpus, Textus Experiment, and CNCF `AiRunner` SPI
  operations.
- Work C: SP-03 - implement the detailed purpose catalog, Sanpomap internal
  application-purpose mapping, purpose-specific runtime bindings, and required
  provider-standard tool validation.
- Work D: SP-04 and SP-05 - operate an initially plausible Gemma and managed-CLI
  composition, retain API routes as budgeted alternatives, and collect evidence
  without automatic paid replay.
- Work E: SP-06 - promote the framework and initial mappings into stable
  design, executable specifications, profiles, and operating documentation.

## Stages

| ID | Stage | Outcome | Status |
| --- | --- | --- | --- |
| SP-00 | CNCF evidence-execution boundary | A Phase 7 flow composes admitted CNCF Operation/MCP evidence outside Textus AI and passes only evidence to the runner. | done |
| SP-01 | Observation framework | Safe capability, usage, latency, acceptance, and schedule-relative cost evidence has a stable referenced contract. | done |
| SP-02 | Corpus and experiment SPI integration | Selected Sanpomap executions can consume immutable cases and record observations through assembled component SPI operations. | done |
| SP-03 | Detailed purpose catalog | Sanpomap AI steps map to reusable provider-neutral purposes with behavior, output, grounding, and standard-tool profiles. | open |
| SP-04 | Cost-conscious operational collection | Normal operation records evidence without automatic provider fan-out; replay is explicit and budgeted. | open |
| SP-05 | Strategy-shape evidence | One-shot, staged-prompt, local-first, and managed-only strategies can be distinguished and compared when exercised. | open |
| SP-06 | Framework closure | The evidence path, initial profiles, operating controls, and deferred benchmark boundary are executable and documented. | open |

## Completion Conditions

Phase 7 closes only when all items in the Phase 7 checklist are complete and:

- at least one useful Sanpomap flow completes through deterministic final
  assembly and records an observation through the framework;
- an observation identifies its corpus revision, corpus case, experiment arm,
  execution plan, detailed purpose, prompt strategy, provider-standard tool
  usage, CNCF evidence path, acceptance outcome, and safe metric evidence;
- the detailed purpose catalog, purpose-specific runtime bindings, and
  pre-provider standard-tool compatibility validation are executable;
- at least one Phase 7 Sanpomap flow uses the SP-00 CNCF evidence-execution
  boundary and proves that Textus AI neither selects nor invokes its
  Operation/MCP tools;
- Sanpomap Requirement, Scenario, extraction, investigation, linear-feature,
  and recovery AI steps have explicit detailed-purpose mappings, including
  separate internal application purposes where one operation composes different
  AI contracts;
- local, subscription-backed CLI, and API-billed executions can represent
  known, estimated, unavailable, and not-applicable cost states without false
  equivalence;
- normal evidence collection does not duplicate paid provider calls, while an
  explicit budget can admit a bounded comparison replay;
- Phase 7 documents an initially plausible operating composition without
  requiring OpenAI, Google, and Anthropic API and CLI routes all to be executed;
  and
- ordinary tests remain deterministic while live provider checks remain opt-in
  heavy evidence.

## References

- [Phase 7 Checklist](phase-7-checklist.md)
- [AI Purpose Catalog](../design/ai-purpose-catalog.md)
- [Gemma-First Operational Profile](../spec/gemma-first-operational-profile.md)
- [AI Provider Feature and Cost Evaluation](../notes/ai-provider-feature-cost-evaluation.md)
- [Sanpomap AI Purpose Resolution](../notes/sanpomap-ai-usage-profiles.md)
- [Phase 6 Dashboard](phase-6.md)
