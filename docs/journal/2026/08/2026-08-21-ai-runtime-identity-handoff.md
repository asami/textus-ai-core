# AI Runtime Canonical Identity Handoff

Status: completed

Task: `AI-RUNTIME-IDENTITY-001`

Authority: direct user instruction on Aug. 21, 2026

## Purpose

Repair the Textus AI CAR so its CML-generated and runtime-loadable component
identity agrees with the already established public CAR identity
`org.simplemodeling.textus.AiRuntime`.

The current repository mixes this repair with pending `0.2.1` publication
normalization. The identity repair is a separate acceptance task and must be
committed before recursive CAR publication resumes.

Invoke the follow-up with this document as its authority:

```text
$cncf-goal-task /Users/asami/src/dev2026/textus-ai/docs/journal/2026/08/2026-08-21-ai-runtime-identity-handoff.md
```

Use a parent profile admitted for a protected-decision task, preferably
`gpt-5.6-terra` with reasoning effort `xhigh`.

## Repository roles

| Repository | Role |
| --- | --- |
| `/Users/asami/src/dev2026/textus-ai` | Sole mutation repository. Owns CML, generated identity metadata, runtime factory integration, tests, and this handoff. |
| `/Users/asami/src/dev2026/textus-art-scene` | Read-only acceptance consumer. Its assemblies already reference `org.simplemodeling.textus.AiRuntime`. |
| Other repositories participating in recursive ArtScene publication | Preserve exactly; no mutation or validation expansion is authorized by this task. |

Do not modify `docs/strategy/**` or `docs/phase/**`. Do not publish a library,
plugin, or CAR in this task.

## Baseline evidence

The reviewed baseline is Textus AI HEAD
`9253ebb3bc492042105397a1ec295eafb347304f` with an empty index and five tracked
dirty paths:

- `.gitignore`
- `project.yaml`
- `project/plugins.sbt`
- `src/main/car/abi-manifest.json`
- `src/main/car/component-descriptor.json`

The dirty tree contains two independent kinds of work:

1. Identity metadata additions in `project.yaml`:
   `project.name`, `project.scalaPackage`, `project.component.name`, and
   `project.component.className`.
2. Pending recursive-publication normalization: `.cozy` ignore/configuration,
   version `0.2.1`, Cozy `0.3.2.2`, sbt-cozy `0.1.16`,
   goldenport-cncf `0.5.2`, CNCF runtime `0.5.2`, and release-version CAR
   descriptor/ABI fields.

The second group is not part of the identity acceptance commit. Freeze its
exact pre-task bytes, restore the pre-publication SNAPSHOT values while making
and validating the identity commit, then reapply the frozen publication bytes
after the identity commit. The final post-task working tree must retain the
same pending publication intent, updated only where the accepted canonical
identity requires it.

The failing generation evidence is serialized SBT invocation
`99033-20260820T184932Z`:

```text
CAR_COMPONENT_IDENTITY_DISAGREEMENT
expected package: org.simplemodeling.textus.airuntime
actual package:   org.simplemodeling.textus.ai
expected class:   AiRuntimeComponent
actual class:     TextusAiComponent
```

## Canonical identity decision

The following identity is authoritative and must not be changed:

- namespace: `org.simplemodeling.textus`
- local component id: `AiRuntime`
- qualified component id: `org.simplemodeling.textus.AiRuntime`
- artifact id: `textus-ai-runtime`
- generated Scala package: `org.simplemodeling.textus.airuntime`
- generated component class: `AiRuntimeComponent`

`TextusAi`, `TextusAiComponent`, and the use of
`org.simplemodeling.textus.ai` as the generated component package are legacy
projection names. They are not alternate public component identities.

The existing provider and runtime implementation packages under
`org.simplemodeling.textus.ai` are not renamed merely for cosmetic
consistency. PLAN may update their imports or move the component factory/main
entry integration only where required by the canonical generated package,
Cozy generation contract, or runtime component loading.

## Required implementation boundary

The task must align all source-of-truth and runtime consumers needed for the
canonical projection, including as applicable:

- `project.yaml` identity fields;
- `src/main/cozy/ai.cml` component and package declarations;
- handwritten component factory, generated-domain loader, and component main
  integration;
- imports and type references that consume generated component/domain types;
- focused executable specifications and CAR ABI/identity assertions;
- directly relevant component manual identity text.

Generated files under `target/**` are evidence, not committed source. Do not
edit them directly.

The task must not:

- change the public CAR id away from `AiRuntime`;
- change ArtScene assembly identities or source merely to accommodate the old
  projection;
- add a compatibility alias that keeps `TextusAiComponent` as the canonical
  generated class;
- rename unrelated provider/runtime APIs or redesign AI execution behavior;
- absorb ABI-baseline maintenance, phase work, strategy work, or publication
  into this repair.

## Acceptance criteria

1. `project.yaml` and CML agree on `AiRuntime`,
   `org.simplemodeling.textus.airuntime`, and `AiRuntimeComponent`.
2. Cozy generation produces the component under
   `org/simplemodeling/textus/airuntime/AiRuntimeComponent.scala`; it no longer
   produces the canonical component as `TextusAiComponent`.
3. The handwritten runtime factory is discoverable and loadable through the
   canonical generated component contract, and existing AI provider/runtime
   behavior remains intact.
4. The CAR descriptor and ABI identity remain
   `org.simplemodeling.textus.AiRuntime` with the task's SNAPSHOT version during
   identity validation.
5. ArtScene's existing read-only assemblies continue to resolve the same
   qualified component identity without source changes.
6. Integrated CAR lint no longer reports
   `CAR_COMPONENT_IDENTITY_DISAGREEMENT`.
7. Focused component factory, generated identity, ABI, and runtime loading
   specifications pass through the serialized SBT wrapper.
8. The complete Textus AI test suite passes on the accepted identity tree.
9. `git diff --check`, the one full task review, and any admitted focused
   closure re-review pass before the identity acceptance commit.
10. The identity commit excludes publication-only version/toolchain changes;
    after it succeeds, the frozen pending `0.2.1` publication normalization is
    restored as uncommitted recursive-publication work.

## Validation order

1. Generate/compile and run focused identity/factory/ABI/runtime specs through
   `/Users/asami/.codex/skills/cncf-sbt-serial-execution/scripts/run-sbt-serial.sh`.
2. Run integrated CAR lint and confirm canonical identity agreement.
3. Inspect the generated managed-source path and runtime factory projection;
   do not edit generated output.
4. Run the complete Textus AI test suite once on the accepted identity tree.
5. Perform the single full task review, apply at most the admitted bounded
   closure batch, and focused re-review if required.
6. Commit the identity task only, then restore the separately frozen recursive
   publication normalization.

## Completion evidence required in the handback

- exact owned paths and final diff identity;
- focused and full-test invocation ids, test counts, exits, and lock release;
- CAR-lint identity result;
- generated canonical package/class evidence;
- read-only ArtScene consumer evidence;
- identity commit hash and exact committed path list;
- post-commit proof that strategy/phase files were untouched and pending
  publication bytes were restored without being included in the identity
  commit.

## Existing separate work

`abi.baseline.missing` is pre-existing release/readiness hygiene. Record it as
separate work if still observed; it does not authorize expansion of this
identity task.

## Implemented

Identity commit `c0f465b` established the canonical `AiRuntime` projection.
The released legacy `0.2.1` ABI baseline is retained, and `0.2.2` is selected
for the corrected release.
