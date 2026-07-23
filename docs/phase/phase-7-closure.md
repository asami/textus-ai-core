# Phase 7 Closure - Cost-Aware AI Operations Evidence Framework

status=complete
completed_at=2026-07-23
phase=[Phase 7](phase-7.md)

## Outcome

Phase 7 establishes a reusable, provider-neutral operational evidence framework
using Sanpomap as the reference application. A registered application purpose
now resolves to a detailed purpose and an operator-owned runtime binding;
selected execution can be observed, deterministically accepted by the
application, and recorded through assembled Corpus and Experiment SPI paths.

## Evidence

| Criterion | Evidence |
| --- | --- |
| CNCF evidence boundary | Sanpomap composes admitted Operation/MCP evidence outside Textus AI and passes bounded ordinary input to the runner. |
| Detailed purpose resolution | Textus AI provides `grounded-research`, `evidence-synthesis`, `candidate-proposal`, `candidate-ranking`, `constrained-planning`, and `structured-extraction` profiles with strict tool admission. |
| Safe observation | `AiExecutionObservation` retains route, usage, tool, cost-state, and opaque-reference facts without provider payloads. |
| Corpus / Experiment integration | Sanpomap records a selected case through `CorpusRegistry` and `ExperimentManagement` assembled SPI operations. |
| Cost policy | Ordinary execution does not fan out; a bounded operator gate admits an explicit comparison replay declaration. |
| Strategy identity | Execution-plan and strategy references distinguish one-shot, staged, local, managed-CLI, provider-grounded, and CNCF-evidence-plus-synthesis shapes. |

## Validation

- Textus AI deterministic suite: 177 passed; 5 opt-in live tests canceled.
- Sanpomap suite: 82 passed.
- Fresh Sanpomap CAR and descriptor-backed Phase 7 assembly specification
  passed with the deterministic Gemini Interactions fixture.
- CNCF exports `executeOperationResponseInChildContext` for assembled child
  component calls.
- CAR lint reported no failures. Existing ABI-baseline and development
  `sbt-cozy` SNAPSHOT warnings remain release-readiness debt.

## Deferred Work

- Perform broad and statistically controlled provider/cost measurements only
  after operational observations accumulate.
- Define persisted replay reservation/consumption only with a future comparison
  scheduler contract.
- Define the next phase before expanding the public operation surface or adding
  application-specific runtime orchestration.
