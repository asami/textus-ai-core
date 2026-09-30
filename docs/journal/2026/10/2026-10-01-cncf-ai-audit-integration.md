# textus-ai-runtime adopts CNCF AI Audit

Date: 2026-10-01

Decision: the AI execution component is `textus-ai-runtime` in the textus-ai-core repository. It will consume CNCF Phase 97 AI Audit rather than an independent textus-ai-runner audit mechanism.

Service Bus is a CNCF built-in facility. AI Audit uses that built-in Service Bus/Journal as the authoritative persistence boundary for selected interaction/evidence records; there is no separate textus-service-bus component dependency in this design.

This keeps execution, audit semantics and persistence separated while allowing Workflow/Continuation and direct textus-ai-runtime calls to produce a common AI Interaction history usable by cbd-support and Control Center.
