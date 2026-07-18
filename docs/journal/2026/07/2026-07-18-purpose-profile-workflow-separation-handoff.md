# Handoff: Purpose, Profile, and Workflow Separation

date=2026-07-18
status=recorded
scope=textus-ai runtime design correction

## Context

The previous discussion mixed three separate concepts: caller-selected AI
purpose, operator-managed execution profile, and runtime workflow. In
particular, it incorrectly treated managed research as a standard purpose
because it may require a multi-step implementation.

This record corrects that model before further purpose-catalog or Phase 3
implementation work begins.

## Confirmed Model

### Purpose

`purpose` is a caller-facing logical selection. It abstracts the approved AI
execution choice; callers use it without naming an engine, model, or logical
level.

```text
AiRunnerRequirement.purpose
  -> operator-managed profile resolution
  -> engine / model / level
```

A purpose is not a provider alias, a model alias, a command-line capability,
or an execution procedure.

### Profile

A profile is operator-managed configuration. It binds a purpose to approved
execution choices, including the provider/engine/model configuration and the
logical level. It is not selected directly by ordinary application callers.

Profiles may change operationally without requiring an application to change
its purpose value, subject to the existing policy and compatibility rules.

### Workflow

A workflow is a runtime or application execution procedure. A multi-step
procedure such as research, source comparison, and structured synthesis is not
part of the purpose abstraction merely because a particular implementation
uses those steps.

No Textus AI workflow concept is decided by this handoff. If workflow support
is needed later, it requires its own design: public contract, ownership,
admission, lifecycle, budget scope, failure semantics, and executable
specification.

## Corrected Consequences

- Do not add `managed-research` to the standard purpose catalog on the basis
  that it may be implemented as a multi-step workflow.
- Do not treat `implementation-work` as a Codex CLI alias. A provider must be
  selected by profile resolution, not encoded by a purpose name.
- A name such as `web-research` may be a purpose only when it identifies an
  approved execution profile. It must not itself grant provider tools or imply
  a runtime workflow.
- Application-specific orchestration, evidence admission, source comparison,
  and domain fallback remain outside the current purpose/profile mechanism.

## Follow-up

1. Design the standard Textus AI purpose catalog as a small, provider-neutral
   vocabulary that covers the four logical levels: `simple-work`,
   `standard-work`, `standard-deliberation`, and `deep-deliberation`.
2. State for each standard purpose only its intended profile/level selection;
   do not assign a workflow to it.
3. Reconcile Phase 3 documentation before implementation. Its current
   managed-research-purpose and workflow assertions predate this correction and
   are not an approved runtime design.
