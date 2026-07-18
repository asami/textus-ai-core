# AI Purpose Catalog

status=accepted
scope=textus-ai standard provider-neutral purpose vocabulary
updated_at=2026-07-18

## Decision

Textus AI defines a small standard catalog of generic purposes. A caller
selects one of these purposes, or an application-specific purpose that
explicitly inherits one. Textus AI resolves the selected purpose to an
operator-managed execution profile. That profile resolves the logical level
and effective provider execution settings.

The catalog standardizes names and intended profile selection; it does not
ship a concrete provider binding. Each deployment binds the listed generic
purposes to approved profiles. A requested standard purpose that is not bound
to a profile fails structurally before provider execution.

The standard purpose vocabulary names user-visible AI intent. It must not name
a provider, model, command-line tool, application, or domain workflow.

```text
purpose
  -> operator-managed execution profile
  -> logical level and model profile
  -> provider, mode, engine, model, reasoning, tools, and limits
```

## Implicit Baseline Purposes

The four configured logical-level names are also standard implicit purposes:
`simple-work`, `standard-work`, `standard-consideration`, and
`deep-consideration`. A caller may use one directly when it needs the baseline
profile without adding semantic intent.

```text
purpose = standard-work
  -> configured standard-work execution profile
  -> effective provider and model
```

This mechanism applies only to logical-level names. An operator model-profile
name is never a caller purpose. For example, `purpose=openai-standard` must
fail unless an explicit purpose binding exists for that name.

## Standard Catalog

| Purpose | Intended profile level | Intent |
| --- | --- | --- |
| `software-analysis` | `standard-consideration` | Understand existing software, investigate causes, and assess change impact. |
| `software-design` | `deep-consideration` | Develop design alternatives, boundaries, and implementation direction. |
| `software-implementation` | `standard-work` | Produce bounded software changes, including code and tests. |
| `web-analysis` | `deep-consideration` | Consider a supplied problem using information from the Web. |
| `structured-extraction` | `standard-work` | Extract or normalize caller-supplied material into a required structured result. |

`simple-work` has no semantic catalog entry: callers use that implicit baseline
purpose directly when they only need lightweight work. The remaining three
logical levels are covered by the semantic purposes above.

The intended level is an approved policy baseline, not a caller choice. An
operator can bind a purpose to a different approved profile without changing
application code. A caller cannot replace the resolved level, model,
reasoning setting, or tool set through provider-specific request fields.

`consideration` expresses the amount of analysis expected from the selected
profile. It intentionally avoids the stronger connotation of `deliberation`;
the level is not a claim that a provider performs formal deliberation.

The semantic purposes in this catalog add readable intent above the implicit
baseline purposes. Use `standard-consideration` when only the baseline level is
known; use `software-analysis` when the caller is specifically requesting
software analysis.

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
still resolve through a logical level and approved model profile; it must not
place provider or command arguments in application calls.

## Exclusions

The following are not standard purposes:

- provider or product names such as `codex`, `openai`, `gemini`, or `gemma`;
- model names, reasoning labels, and CLI capability names;
- command execution, which is a runtime capability rather than a caller
  purpose; and
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

This five-purpose set is the initial standard catalog. Add a standard purpose
only when a recurring, provider-neutral caller intent needs a distinct approved
profile policy. Do not add names for a provider, model, command capability,
output transport, or a one-off application workflow.

## Workflow Separation

Managed research is not an additional standard generic purpose. It is also not
an approved Textus AI runtime workflow at this point. An application may use a
purpose such as `artscene-exhibition-managed-research` when it needs to select
an approved research profile, but the name does not grant a workflow.

If a runtime workflow is needed later, it requires a separate design covering
its public contract, ownership, admission, lifecycle, budget scope, failure
semantics, and executable specification. Application orchestration, evidence
admission, source comparison, and domain fallback remain outside the current
purpose/profile mechanism.

## Profile Ownership

An execution profile is operator-managed configuration. It binds a purpose to
the approved logical level, provider, mode, engine, model, reasoning, tool
capabilities, and execution bounds. A profile is not selected directly by an
ordinary application caller.

The current `generic-purposes` configuration is a transitional implementation:
it still places level and some tool selection beside purpose names. It must be
reconciled to this profile-binding design before the catalog is treated as a
fully implemented runtime contract. This does not change current explicit
failure behavior for an unconfigured purpose.

## Invariants

- Every standard purpose resolves through one approved execution profile,
  logical level, and model profile before execution.
- External research tools are admitted only by the selected profile and runtime
  capability; no purpose name grants a tool.
- A standard purpose does not imply a provider, model, or provider-specific
  tool wire format or runtime workflow.
- Application purposes may select only profiles approved by their assembly;
  they may not broaden approved capabilities or replace profile policy through
  request fields.
- Missing, unsupported, or incompatible configured capabilities return a
  structured failure; they do not select a fallback purpose or provider.
