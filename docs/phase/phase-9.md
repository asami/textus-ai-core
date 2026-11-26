# Phase 9 - Structured Domain Rule Evaluation

status=planned
scheduled=within sm-workflow Phase 25.5, after CNCF Phase 114
checklist=[Phase 9 Checklist](phase-9-checklist.md)

## Goal
Implement the CNCF typed Domain Rule evaluator SPI through Textus AI, without provider-specific leakage.

## Work Items

| ID | Work item | Status |
| --- | --- | --- |
| P9-01 | Predicate, Selection and typed Value contract mapping; registered purposes | planned |
| P9-02 | OpenAI Structured Outputs adapter and capability-aware provider alternatives | planned |
| P9-03 | Deterministic schema/domain validation, decline/refusal/failure separation | planned |
| P9-04 | Free-first admission, accounting, deterministic bootstrap routing and audit | planned |
| P9-05 | Deterministic fixtures, opt-in live qualification and sm-workflow integration | planned |

## Boundaries
CNCF owns rule semantics and SPI; Textus AI owns provider mapping and execution. Preserve operator-owned routing, explicit paid authorization and admitted CNCF UoW/Safety Sandbox capabilities. No recursive routing or implicit paid fallback. No full hybrid Evidence integration (CNCF 114.1). Corpus/Experiment compares accuracy, prompt complexity, tokens, latency and cost; no unmeasured savings claims.

## Closure
All checklist items verified; publish compatible dependency for sm-workflow 25.5. Planning does not start execution.
