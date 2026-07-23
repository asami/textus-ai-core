# Sanpomap AI Purpose Resolution

status=exploratory
scope=textus-ai phase-7 and sanpomap development driver
updated_at=2026-07-23

## Decision Direction

Keep the existing two-level purpose structure and increase the resolution of
Textus AI purposes.

```text
Sanpomap application purpose
  -> detailed Textus AI purpose
  -> execution class
  -> selected runtime profile purpose binding or class default
  -> provider, engine, model, and admitted provider-standard tools
```

An application purpose is a registered domain or internal AI-step identity. A
detailed purpose is a reusable provider-neutral AI contract. Its definition is
the AI usage profile: grounding, output, prompt shape, standard-tool policy,
default execution class, and required observations.

No third purpose level is introduced. A composed Sanpomap operation registers
separate internal application purposes for AI steps that require different
detailed purposes. The public operation owns their sequence; callers do not
select the sequence or its internal purpose names.

## Purpose Profile

Each detailed purpose defines:

- input grounding: supplied content, admitted evidence, or provider-grounded;
- output contract: text, semantic record, candidate set, ranking, or plan;
- default execution class;
- prompt shape: one-shot or a bounded staged AI step;
- provider-standard tools: required, preferred, allowed, or forbidden;
- source and citation requirements;
- deterministic schema/acceptance requirements; and
- usage, latency, tool, and cost observations required by Phase 7.

The current serialized provider-standard tool contract is CNCF `AiTool`:
`web_search` and `url_context`. Google maps those identities to its
`google_search` and `url_context` wire features; other adapter wire names and
CLI flags remain runtime concerns. `file_search` and `code_execution` are
possible future logical capabilities, not Phase 7 configuration values until
they are added to CNCF `AiTool` and provider admission deliberately.

## Detailed Purpose Catalog

| Purpose | Input grounding | Output | Default class | Provider-standard tools |
| --- | --- | --- | --- | --- |
| `structured-extraction` | Supplied content | Schema-valid semantic record | `standard-work` | Forbidden |
| `grounded-research` | Provider-grounded public information | Cited evidence set | `advanced-thinking` | `web_search` required; `url_context` preferred |
| `evidence-synthesis` | CNCF/application-admitted evidence | Cited semantic record or bounded prose | `standard-work` | Forbidden by default |
| `candidate-proposal` | Objective plus supplied evidence | Typed candidates with reasons | `standard-work` | Forbidden; run `grounded-research` first when current facts are required |
| `candidate-ranking` | Supplied candidates and constraints | Ranked candidates with bounded reasons | `simple-thinking` | Forbidden |
| `constrained-planning` | Accepted candidates, geography, and constraints | Typed semantic plan | `advanced-thinking` | Forbidden; calculations and routing stay deterministic/CNCF-owned |

`forbidden` prevents a model from acquiring unobserved facts or incurring a
hidden provider-tool charge when the contract says all evidence is supplied.
It does not prevent the CNCF execution plan from preparing evidence before the
AI step.

The existing software and command purposes remain valid. `web-analysis` may
remain a broad consideration purpose, while `grounded-research` is selected
when acquiring current evidence and citations is part of the contract.

## Provider-Standard Tools And CNCF Evidence

Provider-standard tools are part of purpose resolution. A required tool narrows
the applicable runtime bindings before cost is considered. Actual tool calls,
returned-context usage, and provider tool charges are recorded as AI execution
facts.

The Phase 7 target is that CNCF Operations and MCP tools are applied by
CNCF-owned execution. They own
catalog selection, admission, credentials, invocation, limits, and failure
policy. Their output becomes admitted evidence for a no-standard-tool purpose
such as `evidence-synthesis` or `candidate-ranking`.

Current Phase 6 `tool-grounded` and `prompt-grounded` strategies are an
explicit legacy exception: Textus AI currently owns their sockets and tool
loop. SP-00 implements the selected Phase 7 `researchScenarioDsl` route with
bootstrap-configured CNCF Operation/MCP evidence composition before the runner
call. The runner receives only bounded ordinary evidence with no requested
CNCF tool. A `cncf.evidence.composed=true` request is rejected before provider
execution if its runtime profile would re-enter a Textus-AI-owned CNCF tool
loop. The legacy strategies are not the implementation route for new
Phase 7 application flows.

Provider-native research and CNCF evidence preparation are separate execution
plans. CNCF tools are never silently substituted for a missing required
provider-standard tool, and provider tools are never relabelled as CNCF calls.

## Sanpomap Scene Classification

### Requirement Candidate Discovery

1. `grounded-research` discovers current visit and reference evidence when
   provider-native Web research is selected.
2. `candidate-proposal` creates typed visit and reference candidates from the
   admitted evidence.
3. `candidate-ranking` reduces or orders the candidate set when needed.
4. Sanpomap compiles Requirement DSL deterministically.

When CNCF Operations/MCP provide the evidence, skip provider-native research
and invoke `candidate-proposal` with that evidence.

### Scenario Semantic Generation

- `evidence-synthesis` creates semantic stop records from an accepted
  Requirement and admitted source evidence.
- A separate `grounded-research` step acquires missing current facts only when
  needed.
- Sanpomap compiles Scenario DSL deterministically; AI does not author YAML
  structure.

### HTML Or Document Structure Extraction

Use `structured-extraction` on already fetched HTML, text, or bounded document
fragments. Provider Web, URL, file, and code tools are forbidden because
retrieval has already occurred.

### Location Investigation

Two explicit paths are valid:

- provider-native `grounded-research` with `web_search` and optional
  `url_context`;
  or
- CNCF/GeoResolver evidence preparation followed by `evidence-synthesis` or
  `candidate-ranking`.

They use different internal application purposes and experiment arms.

### Linear Feature And Access-Point Selection

GeoResolver or another CNCF operation obtains geometry and access candidates.
Use `candidate-ranking` to select plausible access points and
`constrained-planning` only for route-context tradeoffs. A provider-native
`grounded-research` step is an explicit alternative when authoritative CNCF
evidence is unavailable, not an implicit fallback.

### Route And Gazetteer Recovery

Use `structured-extraction` to normalize supplied provider candidates and
diagnostics, then `candidate-ranking` to select or reject a candidate. Start a
separate `grounded-research` step only when new public evidence is required.

### Route Planning And Ordering

Use `constrained-planning` for semantic tradeoffs over accepted candidates and
constraints. Distance matrices, coordinates, route feasibility, and DSL
serialization stay deterministic or CNCF-owned.

## Internal Application Purpose Mapping

The following are the target step identities for composed workflows. They are
not public operation names and are introduced only when a workflow contains
more than one AI step with distinct contracts:

| Internal application purpose | Detailed purpose |
| --- | --- |
| `sanpomap-requirement-grounded-research` | `grounded-research` |
| `sanpomap-requirement-candidate-proposal` | `candidate-proposal` |
| `sanpomap-requirement-candidate-ranking` | `candidate-ranking` |
| `sanpomap-scenario-grounded-research` | `grounded-research` |
| `sanpomap-scenario-evidence-synthesis` | `evidence-synthesis` |
| `sanpomap-html-structure-extraction` | `structured-extraction` |
| `sanpomap-location-native-research` | `grounded-research` |
| `sanpomap-location-evidence-synthesis` | `evidence-synthesis` |
| `sanpomap-linear-feature-ranking` | `candidate-ranking` |
| `sanpomap-route-location-ranking` | `candidate-ranking` |
| `sanpomap-gazetteer-location-ranking` | `candidate-ranking` |
| `sanpomap-route-semantic-planning` | `constrained-planning` |

Public Sanpomap operation names do not have to match these internal AI
application-purpose names.

## Current Sanpomap Registrations

The current executable registration catalog has one AI step per registered
application purpose. It therefore uses the stable existing names directly:

| Registered application purpose | Detailed purpose |
| --- | --- |
| `sanpomap-scenario-research` | `grounded-research` |
| `sanpomap-location-investigation` | `grounded-research` |
| `sanpomap-linear-feature-research` | `grounded-research` |
| `sanpomap-route-location-recovery` | `candidate-ranking` |
| `sanpomap-gazetteer-location-recovery` | `candidate-ranking` |

`candidate-proposal`, `evidence-synthesis`, `structured-extraction`, and
`constrained-planning` remain catalog contracts ready for the next composed
Sanpomap flow. They must receive their own internal registration before a
workflow invokes them; configuration does not create those registrations.

## Runtime Selection

1. Resolve the registered application purpose to one detailed purpose.
2. Load the purpose profile and default execution class.
3. In the selected runtime profile, use a purpose-specific binding when one is
   configured; otherwise use the execution-class default.
4. Reject the binding before provider execution if it lacks a required
   provider-standard tool.
5. Apply limits, rate schedule, and safe execution-fact policy.

Purpose-specific runtime bindings provide the needed resolution. For example,
`evidence-synthesis` and `candidate-proposal` both default to `standard-work`,
but a composite profile can bind synthesis to local Gemma and proposal to a
managed provider. `grounded-research` can bind to Antigravity CLI or Gemini API
because its Web tool contract differs from other advanced-thinking work.

Selection remains deterministic and operator-owned. Missing tools, execution
failure, or cost do not cause an unregistered provider fallback.

## Required Safe Facts

- `ai.policy.application_purpose`;
- `ai.policy.effective_standard_purpose` with the detailed purpose identity;
- `ai.policy.runtime_profile`;
- `ai.policy.effective_execution_class`;
- required, admitted, and used provider-standard tools; and
- separate CNCF Operation/MCP evidence call counts at execution-plan scope.

## Phase 7 Implementation Questions

- how purpose profiles are represented in the Textus AI catalog and validated;
- how runtime profiles express purpose-specific bindings before class defaults;
- which current Sanpomap registrations are split into internal step purposes;
- how subsequent flows consume the released CNCF composed-execution contract
  and migrate away from Phase 6 Textus AI-owned `tool-grounded` and
  `prompt-grounded` orchestration; and
- which normalized facts need additions for provider-standard tool cost and
  execution-plan-level CNCF evidence calls.
