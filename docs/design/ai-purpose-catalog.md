# AI Purpose Catalog

status=accepted
scope=textus-ai standard provider-neutral purpose vocabulary
updated_at=2026-07-18

## Decision

Textus AI defines a small standard catalog of generic purposes. A caller
selects one of these purposes, or an application-specific purpose that
explicitly inherits one. A caller may also select an `executionClass`. Textus
AI resolves both selectors to approved operator configuration and effective
provider execution settings.

The catalog standardizes names and intended execution-class selection; it does
not ship a concrete provider binding. Each deployment binds the listed generic
purposes and execution classes to approved operator configuration. A requested
standard purpose that is not configured fails structurally before provider
execution.

The standard purpose vocabulary names user-visible AI intent. It must not name
a provider, model, command-line tool, application, or domain workflow.

```text
purpose + executionClass
  -> approved operator configuration
  -> execution-class binding and model profile
  -> provider, mode, engine, model, reasoning, tools, and limits
```

## Execution Classes

The standard execution-class values are `simple-work`, `standard-work`,
`standard-consideration`, and `deep-consideration`. They classify requested
execution independently of semantic purpose and concrete provider selection.

```text
purpose = software-implementation
executionClass = standard-work
  -> effective provider and model
```

The runtime resolves the explicit `executionClass` selector. Configured
logical-level names remain implicit purpose aliases only as a compatibility
path; new integrations must use `executionClass`.

## Standard Catalog

| Purpose | Intended execution class | Intent |
| --- | --- | --- |
| `software-analysis` | `standard-consideration` | Understand existing software, investigate causes, and assess change impact. |
| `software-design` | `deep-consideration` | Develop design alternatives, boundaries, and implementation direction. |
| `software-implementation` | `standard-work` | Produce bounded software changes, including code and tests. |
| `command-execution` | `simple-work` | Perform a command-execution task. |
| `web-analysis` | `deep-consideration` | Consider a supplied problem using information from the Web. |
| `structured-extraction` | `standard-work` | Extract or normalize caller-supplied material into a required structured result. |

An execution class is an approved policy baseline, not a provider/model
override. A caller cannot replace the resolved model, reasoning setting, or
tool set through provider-specific request fields.

`consideration` expresses the amount of analysis expected from the selected
profile. It intentionally avoids the stronger connotation of `deliberation`;
the level is not a claim that a provider performs formal deliberation.

The semantic purpose and execution class remain independent. For example,
`software-analysis` identifies the task intent, while
`standard-consideration` identifies its requested execution class.

## Application Purposes

Applications use a descriptive domain purpose when they need domain-specific
prompt, schema, source, or execution bounds. That purpose inherits one
standard purpose with `base-purpose` and may narrow, but never broaden, its
effective policy.

```yaml
textus:
  ai:
    purposes:
      artscene-exhibition-extraction-from-source:
        base-purpose: structured-extraction
        max-output-tokens: 240
      artscene-exhibition-web-research:
        base-purpose: web-analysis
        max-output-tokens: 240
        max-concurrent: 1
```

Examples of non-standard application purposes include
`artscene-exhibition-web-research` and `car-review-domain-terminology`. Their
names express application intent, not provider selection.

An application requiring a specialized policy that cannot safely inherit a
standard purpose must define that policy explicitly in its assembly. It must
still resolve through an execution class and approved model profile; it must
not place provider or command arguments in application calls.

## Exclusions

The following are not standard purposes:

- provider or product names such as `codex`, `openai`, `gemini`, or `gemma`;
- model names, reasoning labels, and CLI capability names;
- command-line names, arguments, and runtime capability names; and
- transport or output-shape labels such as `chat`, `generate`, or
  `generateRecord`.

`software-implementation` replaces the provisional `implementation-work`
example. A Codex CLI profile may be the operator-selected resolution, but it
does not define the purpose's meaning.

`local-source-extraction` is likewise an application purpose that inherits
`structured-extraction` and supplies bounded, admitted source material. It is
not a separate generic purpose because it requires no capability beyond the
standard structured-extraction boundary.

## Catalog Growth

This six-purpose set is the initial standard catalog. Add a standard purpose
only when a recurring, provider-neutral caller intent needs a distinct approved
profile policy. Do not add names for a provider, model, command-line
capability, output transport, or a one-off application workflow.

## Workflow Separation

Managed research is not an additional standard generic purpose. It is also not
an approved Textus AI runtime workflow at this point. An application may use a
purpose such as `artscene-exhibition-managed-research` when it needs to select
an approved research profile, but the name does not grant a workflow.

If a runtime workflow is needed later, it requires a separate design covering
its public contract, ownership, admission, lifecycle, budget scope, failure
semantics, and executable specification. Application orchestration, evidence
admission, source comparison, and domain fallback remain outside the current
purpose/execution-class mechanism.

## Execution-Class Resolution

`executionClass` is a caller selector. Operator-managed model profiles remain
the configuration that supplies provider, mode, engine, model, reasoning, tool
capabilities, and execution bounds. An execution class does not expose a model
profile name or grant any capability.

Canonical configuration binds `execution-classes.<class>.model-profile` and
sets a generic purpose default with
`generic-purposes.<purpose>.execution-class`. When a request supplies a class
as well, it must match the purpose default; an incompatible pair fails before
provider selection. `levels` and `generic-purposes.*.level` remain read-only
migration fallbacks and do not change explicit failure behavior for an
unconfigured purpose.

## Invariants

- Every request resolves its purpose and optional execution class through
  approved operator configuration before execution.
- External research tools are admitted only by normal runtime capability
  policy; neither selector grants a tool.
- A standard purpose does not imply a provider, model, or provider-specific
  tool wire format or runtime workflow.
- Application purposes and execution classes may select only configuration
  approved by their assembly; they may not broaden approved capabilities or
  replace model policy through request fields.
- Missing, unsupported, or incompatible configured capabilities return a
  structured failure; they do not select a fallback purpose or provider.
