# textus-ai-runtime adopts CNCF AI Audit

Date: 2026-10-01

Decision: the AI execution component is `textus-ai-runtime` in the textus-ai-core repository. It consumes CNCF Phase 97 AI Audit.

Service Bus is a CNCF built-in facility. AI Audit uses that built-in Service Bus/Journal as the authoritative persistence boundary for selected interaction/evidence records; there is no separate textus-service-bus component dependency in this design.

Observability is derived from AI Audit rather than receiving a second detailed stream directly from textus-ai-runtime. CNCF projects compact/sanitized operational information to OpenTelemetry and includes AIInteractionId/auditRef as a back pointer. Detailed context/request/response remains governed by AI Audit policy. This keeps Grafana/Jaeger useful for routine operations while allowing drill-down to authoritative evidence only when required.

This keeps execution, audit semantics, persistence and observability separated while allowing Workflow/Continuation and direct textus-ai-runtime calls to produce a common AI Interaction history usable by cbd-support and Control Center.
