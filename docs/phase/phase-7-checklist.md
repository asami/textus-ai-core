# Phase 7 Checklist - Cost-Aware AI Operations Evidence Framework

status=open
phase=[Phase 7 - Cost-Aware AI Operations Evidence Framework](phase-7.md)

## Repository Ownership

- `textus-ai`: detailed-purpose catalog, runtime bindings, provider-standard
  tool admission, safe execution facts, and client-side CNCF boundary use.
- `goldenport-cncf`: generic composed-execution/Port contract only when the
  existing CNCF contract cannot express the required application composition.
- `textus-sanpomap`: workflow composition, internal application-purpose
  registration, deterministic assembly, acceptance, and evidence invocation.
- `textus-corpus`: immutable cases and evidence references.
- `textus-experiment`: experiment arms, runs, observations, and summaries.

Each implementation slice must declare its changed repository or repositories.
Textus AI must use Corpus and Experiment only through their assembled CNCF SPI
operations.

## Stage SP-00 - CNCF Evidence-Execution Boundary

Stage Status:
- Current status: DONE
- Owner: CNCF, Textus AI, and Sanpomap maintainers
- Update rule: Mark DONE only when a selected Phase 7 flow composes admitted
  CNCF Operation/MCP evidence outside Textus AI and hands only evidence to the
  AI runner.

- [x] State the existing Phase 6 behavior precisely: `tool-grounded` and
  `prompt-grounded` currently create and invoke CNCF Operation/MCP sockets in
  Textus AI.
- [x] Define the CNCF Port/composed-execution contract that lets an application
  select and invoke admitted Operations/MCP tools before or between AI steps.
- [x] Change `goldenport-cncf` only when its current contract cannot express
  that composition; otherwise consume its released contract unchanged.
- [x] Make the selected Sanpomap flow use the CNCF-composed evidence path and
  pass sanitized, admitted evidence as an ordinary AI input.
- [x] Add deterministic assembled specifications proving that the Phase 7 flow
  neither requests an Operation/MCP tool set from Textus AI nor invokes a
  Textus AI tool loop.
- [x] Preserve the Phase 6 direct strategies as explicitly bounded legacy
  behavior until a separately reviewed compatibility or retirement decision.

Implementation evidence (2026-07-23): `textus-sanpomap` configures the selected
source only at bootstrap under `textus.sanpomap.scenario-research.evidence.*`.
`operation-tool-set` / `operation-tool` and `mcp-server-set` / `mcp-server` /
`mcp-tool` are complete source declarations; `maximum-characters` bounds each
source before prompt assembly. `operation-evidence-field` and
`operation-argument-mode` declare the fixed Operation result field and argument
template. The assembled `ComponentFactorySpec` installs both released CNCF
registries, executes their sources before `AiRunner.generate`, verifies ordinary
prompt evidence, and proves `AiRunnerRequirement.tools` is empty. The Phase 6
direct strategies remain unchanged as explicitly documented legacy behavior.
`TextusAiRunnerSpec` also proves that a request marked
`cncf.evidence.composed=true` rejects a resolved runner-owned tool strategy
before its loop starts.

## Stage SP-01 - Observation Framework

Stage Status:
- Current status: OPEN
- Owner: Textus AI, Textus Experiment, and Sanpomap maintainers
- Update rule: Mark DONE only when one safe observation contract can represent
  local, CLI, and API execution without requiring every route to be run.

Implementation evidence (2026-07-23): Textus AI provides the public
`AiExecutionObservation` projection and `safeFacts` artifact map. It whitelists
normalized execution metadata, application-owned acceptance facts, safe CNCF
source identities, and opaque artifact references; it excludes prompt/output
content, provider payloads and request IDs, credentials, endpoints, and
arbitrary metadata. The focused specification proves API, local, subscription
CLI, and admission-estimate cost states plus reference confinement. SP-02 must
still persist the projection from a selected Sanpomap flow through the assembled
Corpus/Experiment SPI path.

Textus AI also publishes the runtime-owned `ai.observation.*` subset on the
generic `AiRunner` response metadata contract. This keeps the selected
application flow on assembled SPI surfaces: consumers add only their
deterministic assessment and artifact identities and do not link to Textus AI
implementation classes.

- [ ] Record accepted result, schema validity, evidence validity, elapsed time,
  provider calls, tool calls, reported usage, local/commercial allocation, and
  failure classification.
- [ ] Record provider-standard tool requirements, admitted tools, actual calls,
  returned-context usage, and tool-specific charge basis separately from CNCF
  Operation and MCP evidence calls.
- [ ] Retain prompt-contract and strategy version, execution-plan reference,
  rate-schedule snapshot, and sanitized execution/acceptance metrics as
  evidence references.
- [ ] Preserve reported, estimated, unavailable, and not-applicable as distinct
  measurement states; an absent value is never recorded as zero.
- [ ] Calculate API cost only from observed usage and a versioned operator rate
  schedule. Keep the rate-schedule identity and basis with the metric artifact.
- [ ] Record subscription-backed CLI plan/quota facts without fabricating a
  per-call monetary amount; record API-key-backed CLI use as API billing when
  the provider reports enough usage.

## Stage SP-02 - Corpus and Experiment SPI Integration

Stage Status:
- Current status: OPEN
- Owner: Textus AI, Textus Corpus, Textus Experiment, and Sanpomap maintainers
- Update rule: Mark DONE when one selected Sanpomap execution can traverse the
  assembled SPI path and leave immutable referenced evidence.

- [ ] Publish one immutable Textus Corpus revision through `CorpusRegistry`
  for the initial Phase 7 Sanpomap population.
- [ ] Select at least one useful Sanpomap flow with deterministic application
  acceptance and final assembly.
- [ ] Register sanitized fixture and expected-evidence references without raw
  prompts, credentials, or provider payloads.
- [ ] Define one Textus Experiment and only the execution-plan arms selected
  for current operational trials.
- [ ] Verify `CorpusRegistry` and `ExperimentManagement` are consumed through
  assembled CNCF component SPI operations without Textus AI implementation
  dependencies on either CAR.

## Stage SP-03 - Detailed Purpose Catalog

Stage Status:
- Current status: OPEN
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Mark DONE when Sanpomap AI steps resolve reusable detailed
  purposes and one specification-based initial runtime allocation is
  executable.

- [ ] Expand the purpose catalog with `grounded-research`,
  `evidence-synthesis`, `candidate-proposal`, `candidate-ranking`, and
  `constrained-planning`, while retaining `structured-extraction`.
- [ ] Give each detailed purpose a provider-neutral profile containing its
  input-grounding mode, output contract, default execution class, prompt shape,
  citation policy, and provider-standard tool policy.
- [ ] Map Requirement candidate discovery, Scenario semantic generation, HTML
  extraction, location investigation, linear-feature access selection, and
  route/gazetteer recovery AI steps to those purposes.
- [ ] Split a composed Sanpomap operation into separately registered internal
  application purposes when its AI steps require different detailed purposes;
  do not make a caller select the internal purpose sequence.
- [ ] Keep application purpose as the domain/step registration and runtime
  profile as the provider/model mapping; no third purpose layer is introduced.
- [ ] Add purpose-specific runtime-profile bindings before execution-class
  defaults so two purposes in the same class can select different admitted
  providers and provider-standard tools.
- [ ] Reject unknown purposes and runtime bindings that lack a required
  provider-standard tool before provider execution.
- [ ] Use the existing CNCF `AiTool` logical IDs as the serialized contract:
  `web_search` and `url_context`. Provider wire names such as Google
  `google_search` remain adapter details; unsupported future logical tools are
  not admitted until CNCF and Textus AI add them deliberately.
- [ ] Separate semantic generation from YAML/DSL serialization, coordinates,
  schema checks, and domain validation.
- [ ] Use registered application purposes to assign suitable extraction,
  classification, candidate organization, and drafting tasks to Gemma.
- [ ] Use bounded question lists and CNCF-admitted evidence when external facts
  are required; do not ask Gemma to invent unobserved site facts or select a
  CNCF Operation/MCP tool.
- [ ] Reject malformed, uncited, or domain-invalid semantic records without a
  stochastic live repair loop.
- [ ] Verify the selected `gemma3:12b` workload with deterministic fixtures and
  one opt-in native heavy run.
- [ ] Prefer the existing subscription-backed Antigravity or Codex CLI route
  for stronger Web or thinking work when its admitted contract fits.
- [ ] Keep Gemini and OpenAI API routes as explicit budgeted alternatives where
  API stability, structured output, or provider tools justify their direct
  monetary cost.
- [ ] Keep Claude Code and Anthropic API as unverified alternatives until an
  operational need and credentials justify measurement.
- [ ] Select a provider route only when it satisfies required standard tools;
  unsupported required tools make a route not-applicable rather than silently
  selecting an unrelated CNCF Operation or MCP tool.

## Stage SP-04 - Cost-Conscious Operational Collection

Stage Status:
- Current status: OPEN
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Mark DONE when normal execution records evidence without hidden
  duplicate calls and comparison replay requires explicit budget admission.

- [ ] Record one observation for the route actually selected by the registered
  application purpose; do not fan out to unselected providers.
- [ ] Require explicit operator enablement, call-count bounds, and cost budget
  before replaying one corpus case through another paid route.
- [ ] Allow sampling and ordinary application runs to accumulate evidence over
  time without making broad provider coverage a Phase 7 condition.
- [ ] Record unsupported or unverified schema, Web, URL, MCP, tool, usage, and
  confidentiality capabilities as facts rather than quality failures.
- [ ] Keep CNCF Operation/MCP selection, admission, invocation, credentials,
  and failure policy in the CNCF execution plan; pass only admitted evidence to
  the selected detailed purpose through the SP-00 composition boundary.
- [ ] Ensure evidence collection failure does not fail an otherwise accepted
  application result unless the selected operating policy requires evidence.

## Stage SP-05 - Strategy-Shape Evidence

Stage Status:
- Current status: OPEN
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Mark DONE when observations distinguish route choice from prompt
  and workflow decomposition, so later analysis can attribute cost differences.

- [ ] Give one large prompt, staged bounded prompts, local-first composition,
  and managed-only execution distinct strategy or execution-plan identities.
- [ ] Record per-step and whole-workflow provider calls, elapsed time, usage,
  and acceptance without prompts or provider payloads.
- [ ] Distinguish provider-standard Web/URL/code/file tool activity from CNCF
  Operation/MCP activity in every strategy observation.
- [ ] Measure prompt splitting as a hypothesis: count repeated context, total
  input/output tokens, calls, and acceptance rather than assuming it is cheaper.
- [ ] Add deterministic assembled specifications for the evidence path and
  terminal collection failures.
- [ ] Verify that a CLI failure does not trigger an unregistered provider or
  model fallback.
- [ ] Drive corpus cases and experiment observations through assembled
  `CorpusRegistry`, `AiRunner`, application acceptance, and
  `ExperimentManagement` SPI operations.

## Stage SP-06 - Framework Closure

Stage Status:
- Current status: OPEN
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Close when the framework and one initial operating composition
  are executable. A statistically broad or exhaustive benchmark is deferred.

- [ ] Fix the initial execution-class allocation in Textus AI runtime profiles
  and Sanpomap application-purpose defaults as a tunable operational baseline.
- [ ] Version the prompt, semantic-record, deterministic compiler, and
  acceptance contracts used by the selected flows.
- [ ] Document operator prerequisites for native Ollama, the selected managed
  CLI, authentication, heavy tests, and safe diagnostics.
- [ ] Promote settled behavior into design and specification documents and
  retain experimental comparisons as non-normative evidence.
- [ ] Record application purpose, effective detailed purpose, runtime profile,
  execution class, and effective provider-standard tools as separate safe
  execution facts.
- [ ] Record the compatibility or retirement decision for the Phase 6
  Textus-AI-owned `tool-grounded`/`prompt-grounded` strategies after SP-00.
- [ ] Record exhaustive cross-provider measurement, statistical confidence,
  and long-term cost optimization as future work driven by accumulated data.
- [ ] Run Textus AI and Sanpomap regression suites, CAR lint, review, and release
  validation before closing Phase 7.
