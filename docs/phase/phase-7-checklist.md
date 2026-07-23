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
- Current status: DONE
- Owner: Textus AI, Textus Experiment, and Sanpomap maintainers
- Update rule: Mark DONE only when one safe observation contract can represent
  local, CLI, and API execution without requiring every route to be run.

Implementation evidence (2026-07-23): Textus AI provides the public
`AiExecutionObservation` projection and `safeFacts` artifact map. It whitelists
normalized execution metadata, application-owned acceptance facts, safe CNCF
source identities, and opaque artifact references; it excludes prompt/output
content, provider payloads and request IDs, credentials, endpoints, and
arbitrary metadata. The focused specification proves API, local, subscription
CLI, and admission-estimate cost states plus reference confinement. SP-02
persists the projection from a selected Sanpomap flow through the assembled
Corpus/Experiment SPI path.

Textus AI also publishes the runtime-owned `ai.observation.*` subset on the
generic `AiRunner` response metadata contract. This keeps the selected
application flow on assembled SPI surfaces: consumers add only their
deterministic assessment and artifact identities and do not link to Textus AI
implementation classes. `AiExecutionObservationSpec` verifies the
provider-neutral safe projection for measured and estimated API usage,
unavailable usage, local not-applicable cost, subscription CLI unavailable
cost, provider attempt count, requested/admitted provider-standard tools,
returned context, tool charge-basis state, separate CNCF Operation/MCP call
counts, rate-schedule snapshot, sanitized failure classification, and
reference confinement. It also proves raw provider fields never enter the
metric artifact.

- [x] Record accepted result, schema validity, evidence validity, elapsed time,
  provider calls, tool calls, reported usage, local/commercial allocation, and
  failure classification.
- [x] Record provider-standard tool requirements, admitted tools, actual calls,
  returned-context usage, and tool-specific charge basis separately from CNCF
  Operation and MCP evidence calls.
- [x] Retain prompt-contract and strategy version, execution-plan reference,
  rate-schedule snapshot, and sanitized execution/acceptance metrics as
  evidence references.
- [x] Preserve reported, estimated, unavailable, and not-applicable as distinct
  measurement states; an absent value is never recorded as zero.
- [x] Calculate API cost only from observed usage and a versioned operator rate
  schedule. Keep the rate-schedule identity and basis with the metric artifact.
- [x] Record subscription-backed CLI plan/quota facts without fabricating a
  per-call monetary amount; record API-key-backed CLI use as API billing when
  the provider reports enough usage.

## Stage SP-02 - Corpus and Experiment SPI Integration

Stage Status:
- Current status: DONE
- Owner: Textus AI, Textus Corpus, Textus Experiment, and Sanpomap maintainers
- Update rule: Mark DONE when one selected Sanpomap execution can traverse the
  assembled SPI path and leave immutable referenced evidence.

- [x] Publish one immutable Textus Corpus revision through `CorpusRegistry`
  for the initial Phase 7 Sanpomap population.
- [x] Select at least one useful Sanpomap flow with deterministic application
  acceptance and final assembly.
- [x] Register sanitized fixture and expected-evidence references without raw
  prompts, credentials, or provider payloads.
- [x] Define one Textus Experiment and only the execution-plan arms selected
  for current operational trials.
- [x] Verify `CorpusRegistry` and `ExperimentManagement` are consumed through
  assembled CNCF component SPI operations without Textus AI implementation
  dependencies on either CAR.

Implementation evidence (2026-07-23): `textus-sanpomap` now exposes the
`Evaluation.recordAiExecutionObservation` command. It accepts only the
runtime-owned `ai.observation.*` facts published through the generic
`AiRunner` response, validates artifact-only references and application-owned
assessment fields, confirms the corpus-case revision through `CorpusRegistry`,
and invokes `ExperimentManagement.recordObservation` through the assembled
component path. Its executable specification proves that raw model output is
rejected before either assembled component is contacted.

`textus-sanpomap/scripts/check-phase7-ai-observation-assembly.sh` is the
opt-in heavy specification. It runs against a descriptor-backed CNCF server
with Sanpomap as the primary CAR and Corpus, Experiment, Textus AI Runtime,
GeoResolver, and Toolchain Runner resolved from the configured repository. The
check publishes a fresh immutable Corpus revision, selects `scenario-simple`,
defines and activates a single Experiment arm, receives one deterministic
Gemini fixture response, records the sanitized AI observation, re-reads it
through `ExperimentManagement.listExperimentObservations`, and rejects a
fixture API key or raw provider candidate payload in the metric artifact.

## Stage SP-03 - Detailed Purpose Catalog

Stage Status:
- Current status: DONE
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Mark DONE when Sanpomap AI steps resolve reusable detailed
  purposes and one specification-based initial runtime allocation is
  executable.

Implementation evidence (2026-07-23): Textus AI ships the six detailed
purposes and resolves catalog-owned required logical tools before provider
admission. `textus.ai.purpose-bindings.<standard-purpose>.*` now binds one
detailed purpose ahead of its shared execution-class default without allowing
an application caller or application-purpose configuration to select runtime
fields. `AiRuntimeProfileSpec` proves that `evidence-synthesis` can select
local Gemma while `candidate-proposal` retains the shared Gemini class binding;
`TextusAiRunnerSpec` proves that Gemma `grounded-research` fails for missing
`web_search` support before provider execution. Sanpomap's current registered
research and recovery steps use `grounded-research` and `candidate-ranking`.
The target mappings for Requirement, Scenario, extraction, investigation,
linear-feature, and recovery workflows are documented in
`docs/notes/sanpomap-ai-usage-profiles.md`; a new internal registration remains
required only when a future workflow introduces another AI step.

- [x] Expand the purpose catalog with `grounded-research`,
  `evidence-synthesis`, `candidate-proposal`, `candidate-ranking`, and
  `constrained-planning`, while retaining `structured-extraction`.
- [x] Give each detailed purpose a provider-neutral profile containing its
  input-grounding mode, output contract, default execution class, prompt shape,
  citation policy, and provider-standard tool policy.
- [x] Map Requirement candidate discovery, Scenario semantic generation, HTML
  extraction, location investigation, linear-feature access selection, and
  route/gazetteer recovery AI steps to those purposes.
- [x] Split a composed Sanpomap operation into separately registered internal
  application purposes when its AI steps require different detailed purposes;
  do not make a caller select the internal purpose sequence.
- [x] Keep application purpose as the domain/step registration and runtime
  profile as the provider/model mapping; no third purpose layer is introduced.
- [x] Add purpose-specific runtime-profile bindings before execution-class
  defaults so two purposes in the same class can select different admitted
  providers and provider-standard tools.
- [x] Reject unknown purposes and runtime bindings that lack a required
  provider-standard tool before provider execution.
- [x] Use the existing CNCF `AiTool` logical IDs as the serialized contract:
  `web_search` and `url_context`. Provider wire names such as Google
  `google_search` remain adapter details; unsupported future logical tools are
  not admitted until CNCF and Textus AI add them deliberately.
- [x] Separate semantic generation from YAML/DSL serialization, coordinates,
  schema checks, and domain validation.
- [x] Reject malformed, uncited, or domain-invalid semantic records without a
  stochastic live repair loop.

## Stage SP-04 - Cost-Conscious Operational Collection

Stage Status:
- Current status: DONE
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Mark DONE when normal execution records evidence without hidden
  duplicate calls and comparison replay requires explicit budget admission.

Historical implementation evidence (2026-07-23): Phase 7 first used the
application-local `Evaluation.admitAiComparisonReplay` gate to prove that
comparison work was explicitly bounded before a fixture route ran. Phase 8
supersedes that non-durable gate with Experiment-owned
`reserveComparisonReplay`, which Sanpomap invokes through
`Evaluation.reserveAiComparisonReplay`.

The retained `scripts/check-phase7-ai-observation-assembly.sh` fixture now
reserves one bounded replay, invokes the selected `researchScenarioDsl` route
exactly once, persists its sanitized observation, consumes the reservation,
completes the run, and verifies the immutable Experiment summary. It remains
deterministic fixture evidence, not a paid-provider benchmark. The Phase 8
wrapper also verifies explicit cancellation of an unstarted reservation.

The native `TEXTUS_AI_LIVE_GEMMA_WORK_TEST=true` heavy specification passed on
2026-07-23. It selected `gemma3:12b` for `standard-work` through the
`gemma-work` profile and returned one bounded native Ollama response. The live
test is evidence for local standard work only; it does not authorize
`grounded-research`, which still requires a provider with `web_search`.

- [x] Record one observation for the route actually selected by the registered
  application purpose; do not fan out to unselected providers.
- [x] Require explicit operator enablement, call-count bounds, and cost budget
  before replaying one corpus case through another paid route.
- [x] Allow sampling and ordinary application runs to accumulate evidence over
  time without making broad provider coverage a Phase 7 condition.
- [x] Record unsupported or unverified schema, Web, URL, MCP, tool, usage, and
  confidentiality capabilities as facts rather than quality failures.
- [x] Assign suitable registered extraction, classification, candidate
  organization, and drafting purposes to Gemma only after their selected
  `gemma3:12b` workload has deterministic fixtures and one opt-in native heavy
  run.
- [x] Verify the selected `gemma3:12b` standard-work workload with
  deterministic fixtures and one opt-in native heavy run.
- [x] Use bounded question lists and CNCF-admitted evidence when external facts
  are required; do not ask Gemma to invent unobserved site facts or select a
  CNCF Operation/MCP tool.
- [x] Prefer an admitted subscription-backed Antigravity or Codex CLI route for
  stronger Web or thinking work when it fits the required capability contract.
- [x] Keep Gemini and OpenAI API routes as explicit budgeted alternatives where
  API stability, structured output, or provider tools justify direct monetary
  cost; keep Claude Code and Anthropic API unverified until operational need
  and credentials justify measurement.
- [x] Select a provider route only when it satisfies required standard tools;
  unsupported tools make a route not-applicable rather than silently selecting
  an unrelated CNCF Operation or MCP tool.
- [x] Keep CNCF Operation/MCP selection, admission, invocation, credentials,
  and failure policy in the CNCF execution plan; pass only admitted evidence to
  the selected detailed purpose through the SP-00 composition boundary.
- [x] Ensure evidence collection failure does not fail an otherwise accepted
  application result unless the selected operating policy requires evidence.

## Stage SP-05 - Strategy-Shape Evidence

Stage Status:
- Current status: DONE
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Mark DONE when observations distinguish route choice from prompt
  and workflow decomposition, so later analysis can attribute cost differences.

Implementation evidence (2026-07-23):
`docs/design/ai-execution-plan-strategy-catalog.md` defines stable identities
for one-shot, staged question-list, local Gemma, managed CLI, provider-grounded,
and CNCF-evidence-plus-synthesis plans. Existing `AiExecutionObservation` and
Sanpomap observation recording already preserve per-AI-step purpose/profile,
provider/tool/CNCF counts, cost state, execution-plan reference, and strategy
reference. `recordAiExecutionObservation` now adds a safe
`sanpomap.workflow.*` aggregate to every persisted metric artifact. The current
one-step flow reports `ai_step_count=1`; a later staged flow must aggregate the
same stateful fields rather than creating a different metric shape.

On 2026-07-23, the current-CAR assembled check exercised the selected
provider-grounded Scenario research route through `CorpusRegistry`, `AiRunner`,
application acceptance, and `ExperimentManagement`. The fixture accepts one
provider connection only; any hidden retry or fallback causes the check to
fail. Deterministic component specifications cover terminal validation and
collection rejection before an observation is persisted.

- [x] Give one large prompt, staged bounded prompts, local-first composition,
  and managed-only execution distinct strategy or execution-plan identities.
- [x] Record per-step and whole-workflow provider calls, elapsed time, usage,
  and acceptance without prompts or provider payloads.
- [x] Distinguish provider-standard Web/URL/code/file tool activity from CNCF
  Operation/MCP activity in every strategy observation.
- [x] Measure prompt splitting as a hypothesis: count repeated context, total
  input/output tokens, calls, and acceptance rather than assuming it is cheaper.
- [x] Run the deterministic assembled specification for the evidence path and
  terminal collection failures.
- [x] Verify that a CLI failure does not trigger an unregistered provider or
  model fallback.
- [x] Verify corpus cases and experiment observations through assembled
  `CorpusRegistry`, `AiRunner`, application acceptance, and
  `ExperimentManagement` SPI operations.

## Stage SP-06 - Framework Closure

Stage Status:
- Current status: DONE
- Owner: Textus AI and Sanpomap maintainers
- Update rule: Close when the framework and one initial operating composition
  are executable. A statistically broad or exhaustive benchmark is deferred.

- [x] Fix the initial execution-class allocation in Textus AI runtime profiles
  and Sanpomap application-purpose defaults as a tunable operational baseline.
- [x] Version the prompt, semantic-record, deterministic compiler, and
  acceptance contracts used by the selected flows.
- [x] Document operator prerequisites for native Ollama, the selected managed
  CLI, authentication, heavy tests, and safe diagnostics.
- [x] Promote settled behavior into design and specification documents and
  retain experimental comparisons as non-normative evidence.
- [x] Record application purpose, effective detailed purpose, runtime profile,
  execution class, and effective provider-standard tools as separate safe
  execution facts.
- [x] Record the compatibility or retirement decision for the Phase 6
  Textus-AI-owned `tool-grounded`/`prompt-grounded` strategies after SP-00.
- [x] Record exhaustive cross-provider measurement, statistical confidence,
  and long-term cost optimization as future work driven by accumulated data.
- [x] Run Textus AI and Sanpomap regression suites, CAR lint, review, and release
  validation before closing Phase 7.

Closure evidence (2026-07-23): the full Textus AI suite passed 177 deterministic
tests with 5 opt-in live tests cancelled and no failures or errors. The full
Sanpomap suite passed 82 tests with no failures. The current-CAR
descriptor-backed Phase 7 assembly check passed against the freshly rebuilt
Sanpomap CAR. The selected CNCF Ivy artifact and its test-source compilation
export `executeOperationResponseInChildContext`, the child-call SPI used by the
assembled Corpus and Experiment path. `cozy lint car .` for Textus AI completed
with no errors; it reports the existing missing ABI baseline and development
`sbt-cozy` snapshot warnings.
