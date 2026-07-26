# Sanpomap Assembly Runtime ABI Handoff

status=handoff
scope=Textus AI runtime CAR assembly compatibility for downstream Sanpomap integration
updated_at=2026-07-26

## Context

Sanpomap Phase 2 packages a development assembly containing Sanpomap, Textus
AI Runtime, GeoResolver, Toolchain Runner, Corpus, and Experiment CARs. The
packaged command fails before it can report its loaded components.

The observed linkage failure is for:

```text
org.goldenport.cncf.entity.runtime.EntityRuntimeDescriptor.apply(...)
```

The failing boundary is the assembled Textus AI runtime, not Sanpomap business
logic and not CNCF launcher syntax. Textus AI owns the runtime assembly that
loads its AI component and must ensure that the API it was compiled against is
the API available in the assembled execution classpath.

## Current Contract

Textus AI currently declares CNCF `0.5.1-SNAPSHOT` as both its compile
dependency and tested runtime version in `project.yaml`. The downstream
Sanpomap assembly also selects Textus AI Runtime `0.2.1-SNAPSHOT` and CNCF
`0.5.1-SNAPSHOT`.

Those declarations are insufficient while the assembled process resolves an
`EntityRuntimeDescriptor` binary that does not provide the method required by
the Textus AI artifact. This is a binary compatibility defect or stale-artifact
resolution issue, not an application-purpose or provider-selection failure.

## Required Textus AI Work

1. Identify the exact classpath entry that supplies
   `EntityRuntimeDescriptor` during the Textus AI Runtime CAR assembly.
2. Identify the CNCF API signature against which the Textus AI Runtime classes
   were compiled.
3. Make Textus AI's internal build and CAR assembly resolve one compatible API
   line. Do not delegate this compatibility decision to Sanpomap, GeoResolver,
   or a launcher wrapper.
4. Add or extend a Textus AI packaging-level executable verification that loads
   the assembled runtime and exercises the affected entity runtime boundary.
   It must fail deterministically when compile-time and execution-time ABI
   differ.
5. Rebuild the Textus AI Runtime CAR after the compatibility verification
   passes.

## Downstream Acceptance

After Textus AI's runtime CAR is rebuilt, run the existing Sanpomap assembled
acceptance:

```text
textus-sanpomap/scripts/check-phase2-mcp-ai-assembly.sh
```

Success requires the command to complete `admin.assembly.report`, load the
Textus AI Runtime component, and produce the expected Scenario DSL artifact.
The check must run with the same development versions recorded by the assembled
CAR descriptors; it must not substitute an older local Textus AI or CNCF
artifact.

Sanpomap will then update its Phase 2 evidence from that successful run. Until
then, its assembly evidence is historical and must not be rewritten to claim
the new Toolchain Runner version passed.

## Ownership Boundaries

Textus AI owns:

- runtime CAR build and assembly classpath compatibility;
- detection of stale or incompatible runtime API artifacts;
- a packaging-level ABI verification for the AI runtime.

Sanpomap owns:

- composing the declared component CARs;
- its application-purpose request and result handling;
- recording a successful downstream acceptance result.

GeoResolver and Toolchain Runner do not own this linkage failure.

## Non-Goals

- No change to Sanpomap route, presentation, or PDF behavior.
- No Codex provider or application-purpose policy change.
- No launcher-specific workaround or runtime-version string override.
- No direct process execution in Sanpomap to bypass Textus AI Runtime.
