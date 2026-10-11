<!-- cncf-phase-finding:HYG-P9-001:begin -->
HYG-P9-001: TextusAiRunner.scala exceeds the ordinary source-size guidance and combines several runtime responsibilities. Prepare a behavior-preserving decomposition as separate maintenance work, retaining existing contracts and paired specs. This is nonblocking for the standalone Phase 9 JSONL adapter.
<!-- cncf-phase-finding:HYG-P9-001:end -->

<!-- cncf-phase-finding:HYG-P9-002:begin -->
HYG-P9-002: Existing private structural types in runtime and runner specs retain underscore-prefixed names contrary to UpperCamelCase structural naming. Rename them mechanically in a separately bounded maintenance batch without changing behavior. This is nonblocking for Phase 9.
<!-- cncf-phase-finding:HYG-P9-002:end -->

<!-- cncf-phase-finding:HYG-P9-003:begin -->
HYG-P9-003: AiExecutionObservationSpec retains legacy header and Given/When/Then structure. Align its presentation with the shared spec convention in separate maintenance work while preserving assertions and scenarios. This is nonblocking for Phase 9.
<!-- cncf-phase-finding:HYG-P9-003:end -->

<!-- cncf-phase-finding:HYG-P9-004:begin -->
HYG-P9-004: Existing ComponentFactorySpec.scala:573-574 and TextusAiRunnerSpec.scala:1062-1063,1099-1100 use deprecated componentid/instanceid named-argument labels. The current focused compilation reports six warnings recommending componentId/instanceId. Update these existing call sites in a separately bounded maintenance batch, preserving scenarios and the CNCF API. This is nonblocking for P9-JL; no changed Phase 9 line introduces these labels.
<!-- cncf-phase-finding:HYG-P9-004:end -->

<!-- cncf-phase-finding:HYG-P9-005:begin -->
HYG-P9-005: ComponentFactorySpec.scala:996 retains the pre-existing E25 executable fixture created with Files.createTempFile without a target-owned directory. Shared Work Artifact Placement Rules require test work artifacts under target/. Move fixture allocation into a test-owned target work directory in separate maintenance work, preserving provider-owned managed execution, sibling-caller isolation and cleanup. This is nonblocking for P9-JL because the allocation is unchanged from the original Git base and current controlled execution passes.
<!-- cncf-phase-finding:HYG-P9-005:end -->

<!-- cncf-phase-finding:HYG-P9-006:begin -->
HYG-P9-006: CodexRuntimeProvider.scala retains pre-existing naming debt: the constant Gpt56Minimum at line 68 is not SCREAMING_SNAKE_CASE, and the private _require_no_output_token_limit parameter maxTokens at line 283 is not flatcase. Both declarations are present at the original Git base. Prepare a separately bounded naming maintenance change, retaining CLI-version behavior and considering existing public references to the constant before renaming it. This is nonblocking for the P9-JL adapter.
<!-- cncf-phase-finding:HYG-P9-006:end -->
