# AI Provider Feature and Cost Evaluation

status=exploratory
scope=textus-ai phase-7
updated_at=2026-07-23

## Objective

Collect comparable evidence for local Gemma, managed CLI, and remote API routes
without turning Textus AI into a corpus store, experiment database, billing
system, or application acceptance engine.

The first development driver is Sanpomap. The comparison mechanism must remain
usable by other Textus applications through the same CNCF component SPI
surfaces. Phase 7 builds this mechanism; it does not pay for or execute a
complete provider benchmark.

## Operating Hypothesis

The initial hypothesis is that direct Gemini and OpenAI API use may be more
expensive than necessary for some Sanpomap workloads. Local Gemma may absorb
bounded semantic work, subscription-backed Antigravity or Codex CLI execution
may cover selected Web or thinking work at a lower marginal monetary cost, and
prompt decomposition may reduce unnecessary context or output.

None of these statements is assumed to be true. CLI use consumes plan quota or
credits, and staged prompts can increase cost by repeating context or adding
calls. The framework records the quantities needed to evaluate these tradeoffs
while the selected composition is used for real work.

## Collection Policy

- Record the execution selected by the registered application purpose.
- Do not automatically execute equivalent requests through other providers.
- Use normal application runs and bounded sampling as the main evidence source.
- Admit replay against another paid route only through explicit operator
  enablement, call-count bounds, and a cost budget.
- Treat broad cross-provider and statistically controlled measurement as future
  work after accumulated evidence shows which comparisons are worth paying for.

## Component Roles

| Component | Responsibility |
| --- | --- |
| Textus Corpus | Own reviewed immutable corpus revisions, case metadata, sanitized fixture references, and expected-evidence references. |
| Textus Experiment | Own experiment definitions, execution-plan arms, runs, observations, and derived outcome summaries. |
| Textus AI | Resolve registered application purposes and profiles, execute an admitted route, and return safe execution facts. |
| Application | Resolve fixture content, decompose its workflow, validate domain results, and publish acceptance evidence. |
| Evaluation driver | Orchestrate the SPI calls and write sanitized execution and metric artifacts referenced by observations. |

The evaluation driver invokes `CorpusRegistry`, CNCF `AiRunner`, the
application acceptance operation, and `ExperimentManagement` through assembled
component SPI operations. Textus AI must not depend on Corpus or Experiment
implementation classes.

## Tool Source Boundary

Tool source is a logic-selection and accounting dimension.

Provider-standard tools are executed by the selected AI service or managed CLI.
The current CNCF `AiTool` contract admits `web_search` and `url_context`.
Future tools such as file search or hosted code execution require an explicit
CNCF contract extension before use. A detailed purpose may require, prefer, or
forbid admitted logical standard tools. A route is not applicable when it
cannot satisfy a required standard tool. Observations retain the admitted
logical tools, actual calls, returned-context usage, and provider-specific
charge basis when reported.

CNCF Operations and MCP tools are different. CNCF owns their catalog,
admission, credentials, execution, limits, and failures. An execution plan may
invoke them to prepare evidence before an AI call or between application-owned
steps. The selected purpose receives that admitted evidence as input; it does
not select a CNCF operation, MCP server, endpoint, or tool.

Phase 6 `tool-grounded` and `prompt-grounded` execution proved bounded
interoperability while Textus AI coordinated CNCF tool calls. That remains the
current implementation. SP-00 is the prerequisite that moves selected Phase 7
Sanpomap flows to CNCF-owned composition; provider-native standard tools remain
a Textus AI route capability and must not be merged with CNCF evidence call
counts.

## Observation Flow

1. Resolve one immutable corpus revision and list its cases through
   `CorpusRegistry`.
2. Define one experiment against that revision and the application acceptance
   operation.
3. Define arms only for execution plans currently selected for operation or an
   explicitly budgeted comparison. A plan may identify a route and a workflow
   shape such as one-shot, staged-prompt, or local-first execution.
4. Execute the selected case and arm through the normal application and
   `AiRunner` surfaces. Do not fan out implicitly.
5. Run deterministic application acceptance.
6. Write sanitized execution, acceptance, and metric artifacts.
7. Record an immutable Experiment observation with references to those
   artifacts, then complete and summarize the run.

The initial Phase 7 replay gate has been replaced by the durable Phase 8
Experiment scheduler. Sanpomap invokes
`Evaluation.reserveAiComparisonReplay`, which delegates to
`ExperimentManagement.reserveComparisonReplay` before starting an additional
assembled execution-plan arm. The scheduler owns enablement, per-run replay
count and budget envelopes, expiry, consumption, cancellation, and audit state;
it accepts no provider/model/tool inputs. Sanpomap's assembled fixture reserves
one bounded replay before making its deterministic provider fixture available,
then consumes the reservation and completes the Experiment run. It does not
start a paid provider or claim comparison results.

## Comparable Facts

### Identity

- corpus revision and case identity;
- experiment, run, and arm identity;
- execution-plan and prompt-contract version;
- workflow strategy and decomposition version;
- application purpose, effective detailed purpose, runtime profile, and
  execution class; and
- effective provider, engine, model, and local/remote location.

### Capability

- schema-constrained output support;
- provider Web search and URL-context support;
- provider-native and CNCF-mediated tool support;
- MCP availability and ownership mode;
- usage-reporting availability;
- safe prompt transport classification; and
- declared unsupported or unverified capability codes.

Standard-tool capability records the logical tool, admission policy, actual
call count, and result-context/token accounting. CNCF Operation/MCP capability
records only the external evidence path and safe source identity; it is not an
AI provider-tool capability.

Capability is partly static route metadata and partly observed behavior. A
route that cannot meet a case's required capability is `not-applicable`, not a
failed quality result.

### Result Quality

- accepted or rejected by application validation;
- schema and parser validity;
- required-field and completeness checks;
- evidence and source validity;
- domain invariant results; and
- stable application-defined score or reason codes where available.

No general model-quality score is invented by Textus AI. Sanpomap defines the
acceptance contract for its Requirement and Scenario results.

### Operational Measurements

- elapsed milliseconds;
- provider and attempt counts;
- workflow step and total AI call counts;
- tool calls and tool-loop rounds;
- input, cached-input, output, reasoning, and total tokens with source;
- input/output byte or character counts when safe and useful;
- normalized finish and failure classification; and
- local versus managed commercial allocation.

Every value distinguishes `reported`, `estimated`, `unavailable`, and
`not-applicable`. Unknown is never encoded as zero.

### Cost Evidence

API cost is a schedule-relative estimate, not a provider invoice. A metric
artifact retains:

- an immutable rate-schedule identity and effective date;
- currency and integer calculation unit;
- charged quantities and their reported or estimated source;
- model token rates and provider-tool rates applied;
- calculated amount and basis; and
- limitations such as missing usage or unpriced tools.

Existing Textus AI `ai.usage.*` and CallTree accounting facts are the execution
inputs. Volatile public prices and account discounts do not become runtime
profile definitions.

Subscription-backed CLI execution records plan mode, reported quota or credit
facts when safely available, and `monetary_cost=unavailable`. If a CLI is
authenticated with an API key and reports sufficient usage, it may use the
corresponding API rate schedule and must be identified as API-billed CLI use.

Local Gemma records API monetary cost as `not-applicable`. Host energy and
hardware amortization are deferred until a stable operator measurement exists.

## Confidentiality

Corpus and Experiment records contain references and normalized facts, never:

- raw prompts or model responses;
- provider request or response payloads;
- credentials, authorization headers, or account identity;
- arbitrary URLs or fetched private content; or
- unredacted application data.

Metric and evidence artifacts follow application retention policy and expose
only digests, counts, stable reason codes, and explicitly sanitized evidence.

## Route Catalog and Initial Selection

The framework can classify these route families:

- native Ollama with `gemma3:12b`;
- Codex CLI;
- Antigravity CLI;
- Claude Code;
- OpenAI API;
- Google Gemini API; and
- Anthropic API.

They are not Phase 7 execution requirements and are not forced into one
universal benchmark. Arms are comparable only when they satisfy the same case
capability contract.

The initial operational candidates are local `gemma3:12b` for bounded work,
Antigravity CLI for admitted Web-grounded work, and Codex CLI for admitted
stronger-thinking work. Gemini and OpenAI APIs remain budgeted alternatives
when their API contract, structured output, or provider tools justify direct
cost. Claude Code and Anthropic API remain unverified alternatives until an
operational need and credentials justify measurement.

Prompt decomposition is evaluated as part of the execution plan, not as a
provider capability. A staged plan is cheaper only when its measured total
tokens, calls, and accepted-result behavior support that conclusion.

The detailed purpose catalog and Sanpomap mapping are defined in
[Sanpomap AI Purpose Resolution](sanpomap-ai-usage-profiles.md).

## Expected Initial Extensions

No CNCF or Textus Corpus SPI change is required for the initial slice. The
existing Corpus fixture/evidence references and Experiment execution,
acceptance, and metric references are sufficient.

Possible later work is owned by evidence:

- add typed metric lookup or aggregation to Textus Experiment only if
  reference-based reports prove insufficient;
- add typed execution facts to CNCF `AiRunner` only after provider-neutral
  meanings are stable;
- add quota/credit extraction to CLI adapters only when the CLI provides a
  stable machine-readable contract; and
- run exhaustive or statistically controlled cross-provider comparisons only
  after operational evidence identifies a decision whose expected value
  justifies the provider cost.
