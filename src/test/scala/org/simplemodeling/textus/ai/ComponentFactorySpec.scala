package org.simplemodeling.textus.ai

import cats.~>
import org.goldenport.Consequence
import org.goldenport.observation.{Cause, Descriptor, Taxonomy}
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentDescriptor, ComponentInstanceMetadata, ComponentOrigin}
import org.goldenport.cncf.admission.ConcurrencyScopeId
import org.goldenport.cncf.config.{ComponentParameterProvenance, RuntimeConfig}
import org.goldenport.cncf.context.{ExecutionContext, GlobalContext, RuntimeContext, ScopeContext, ScopeKind}
import org.goldenport.cncf.mcp.client.McpClientSocket
import org.goldenport.cncf.processexecution.{LocalProcessExecutionDriver, ProcessArtifactName, ProcessCapabilityId, ProcessExecutionAdmission, ProcessExecutionInputFile, ProcessExecutionRequest, WorkAreaRelativePath}
import org.goldenport.cncf.spi.{SpiContract, SpiResolver, SpiSelection}
import org.goldenport.cncf.subsystem.{GenericSubsystemDescriptor, Subsystem}
import org.goldenport.cncf.unitofwork.{UnitOfWork, UnitOfWorkInterpreter, UnitOfWorkOp}
import org.goldenport.cncf.workarea.WorkAreaSpace
import org.goldenport.configuration.{Configuration, ConfigurationOrigin, ConfigurationResolution, ConfigurationTrace, ResolvedConfiguration}
import org.goldenport.configuration.ConfigurationValue
import org.goldenport.cncf.spi.ai.runner.{AiChatRequest, AiGenerateRequest, AiMessage, AiRecordRequest, AiRunner, AiRunnerApplicationPurpose, AiRunnerApplicationPurposePolicy, AiRunnerApplicationPurposeRegistration, AiRunnerRequirement}
import org.goldenport.record.Record
import org.simplemodeling.textus.ai.provider.codex.CodexRuntimeConfig
import org.simplemodeling.textus.ai.runtime.{AiApplicationPurposeCatalog, AiProfileConfig, TextusAiRunnerProvider}
import org.scalatest.GivenWhenThen
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * @since   Jul. 16, 2026
 *  version Jul. 26, 2026
 * @version Aug.  5, 2026
 * @author  ASAMI, Tomoharu
 */
final class ComponentFactorySpec
  extends AnyWordSpec
  with Matchers
  with GivenWhenThen
  with OptionValues {
  "ComponentFactory" should {
    "resolve initialization profiles" which {
      "resolve the runtime-wide profile before constructing provider ports" in {
        Given("a runtime-wide Textus AI profile and no component-instance setting")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem("textus-ai-initialization-runtime-default", configuration = configuration)

        When("the CNCF component initialization boundary creates Textus AI")
        val result = new ComponentFactory().createPrimaryC(
          ComponentCreate(subsystem, ComponentOrigin.Main)
        )

        Then("the resolved typed profile is used to construct the runtime provider")
        val component = result.toOption.value
        val resolution = component.initializationParameters
          .resolve(ComponentFactory.profileParameterKey)
          .toOption
          .value
        val provider = component.port.get[TextusAiRunnerProvider].value
        val contract = SpiContract("ai-runner", classOf[AiRunner])
        resolution.value shouldBe Some("gemini")
        resolution.provenance shouldBe ComponentParameterProvenance.RuntimeConfiguration
        provider.provide(contract, SpiSelection()).isFaillure shouldBe true
        provider.provide(contract, SpiSelection(provider = Some("gemma"))).isSuccess shouldBe true
      }

      "apply a component-instance profile over its packaged default" in {
        Given("a packaged profile default and a named component-instance override")
        given ExecutionContext = ExecutionContext.create()
        val subsystem = new Subsystem(
          "textus-ai-initialization-instance-override",
          configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
        )
        val descriptor = ComponentDescriptor(
          componentName = Some("textus-ai-runtime"),
          config = Map("textus.ai.profile" -> "gemma")
        )

        When("the named instance is initialized")
        val result = new ComponentFactory().createPrimaryC(ComponentCreate(
          subsystem,
          ComponentOrigin.Main,
          componentDescriptors = Vector(descriptor),
          instanceMetadata = Some(ComponentInstanceMetadata(
            "textus-ai-runtime",
            "gemini-instance",
            Map("textus.ai.profile" -> "gemini")
          ))
        ))

        Then("provider construction uses the instance-selected profile")
        val component = result.toOption.value
        val resolution = component.initializationParameters
          .resolve(ComponentFactory.profileParameterKey)
          .toOption
          .value
        val provider = component.port.get[TextusAiRunnerProvider].value
        val contract = SpiContract("ai-runner", classOf[AiRunner])
        resolution.value shouldBe Some("gemini")
        resolution.provenance shouldBe ComponentParameterProvenance.SubsystemInstance
        provider.provide(contract, SpiSelection()).isFaillure shouldBe true
        provider.provide(contract, SpiSelection(provider = Some("gemma"))).isSuccess shouldBe true
      }

      "reject an invalid higher-precedence profile without falling back" in {
        Given("a valid packaged default and an invalid named component-instance override")
        val subsystem = new Subsystem(
          "textus-ai-initialization-invalid-override",
          configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
        )
        val descriptor = ComponentDescriptor(
          componentName = Some("textus-ai-runtime"),
          config = Map("textus.ai.profile" -> "gemma")
        )

        When("component initialization resolves the selected profile")
        val result = new ComponentFactory().createPrimaryC(ComponentCreate(
          subsystem,
          ComponentOrigin.Main,
          componentDescriptors = Vector(descriptor),
          instanceMetadata = Some(ComponentInstanceMetadata(
            "textus-ai-runtime",
            "invalid-instance",
            Map("textus.ai.profile" -> "not-a-textus-ai-profile")
          ))
        ))

        Then("initialization fails instead of using the lower packaged default")
        result.isFaillure shouldBe true
      }

      "isolate profile resolution between named component instances" in {
        Given("one Textus AI factory and two component instances with different profiles")
        given ExecutionContext = ExecutionContext.create()
        val subsystem = new Subsystem(
          "textus-ai-initialization-instance-isolation",
          configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
        )
        val factory = new ComponentFactory()
        def _create_(instance: String, profile: String) =
          factory.createPrimaryC(
            ComponentCreate(subsystem, ComponentOrigin.Main)
              .withInstanceMetadata(ComponentInstanceMetadata(
              "textus-ai-runtime",
              instance,
              Map("textus.ai.profile" -> profile)
              ))
          )

        When("both instances are initialized independently")
        val gemma = _create_("gemma-instance", "gemma").toOption.value
        val gemini = _create_("gemini-instance", "gemini").toOption.value

        Then("each provider runtime retains only its resolved initialization profile")
        val contract = SpiContract("ai-runner", classOf[AiRunner])
        val gemmaprovider = gemma.port.get[TextusAiRunnerProvider].value
        val geminiprovider = gemini.port.get[TextusAiRunnerProvider].value
        gemma.initializationParameters
          .resolve(ComponentFactory.profileParameterKey)
          .toOption.value.value shouldBe Some("gemma")
        gemini.initializationParameters
          .resolve(ComponentFactory.profileParameterKey)
          .toOption.value.value shouldBe Some("gemini")
        gemmaprovider.provide(contract, SpiSelection()).isSuccess shouldBe true
        geminiprovider.provide(contract, SpiSelection()).isFaillure shouldBe true
      }
    }

    "resolve execution-class initialization paths" which {
      "project normal component initialization values from the typed route" in {
        Given("a runtime execution-class value using the canonical public key")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.execution-classes.standard-work.mcp-server-set" ->
              ConfigurationValue.StringValue("research")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          "textus-ai-execution-class-typed-route",
          configuration = configuration
        )

        When("the CNCF initialization boundary creates the Textus AI component")
        val component = new ComponentFactory().createPrimaryC(
          ComponentCreate(subsystem, ComponentOrigin.Main)
        ).toOption.value
        val route = ComponentFactory.executionClassParameterPathRoute
        val snapshot = component.initializationParameters.resolvePath(route).toOption.value
        val segment = snapshot.segments.find(_.value == "standard-work").value
        val leaf = ComponentFactory._execution_class_parameter_path_leaves
          .find(_.name == "mcp-server-set").value
        val resolution = snapshot.resolve(segment, leaf).toOption.value

        Then("the declared path snapshot and unchanged parser receive its canonical value")
        resolution.value shouldBe Some("research")
        resolution.provenance shouldBe ComponentParameterProvenance.RuntimeConfiguration
        component.port.get[McpClientSocket].map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("research"))
      }

      "prefer a runtime compatibility spelling over a lower named-instance canonical spelling" in {
        Given("a runtime compatibility key and a lower named-instance canonical key")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.executionClasses.standard-work.mcpServerSet" ->
              ConfigurationValue.StringValue("runtime-research")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          "textus-ai-execution-class-runtime-compatibility",
          configuration = configuration
        )

        When("the named component instance is initialized")
        val component = new ComponentFactory().createPrimaryC(
          ComponentCreate(subsystem, ComponentOrigin.Main).withInstanceMetadata(
            ComponentInstanceMetadata(
              "textus-ai-runtime",
              "runtime-compatibility",
              Map(
                "textus.ai.execution-classes.standard-work.mcp-server-set" -> "instance-research"
              )
            )
          )
        ).toOption.value
        val route = ComponentFactory.executionClassParameterPathRoute
        val snapshot = component.initializationParameters.resolvePath(route).toOption.value
        val segment = snapshot.segments.find(_.value == "standard-work").value
        val leaf = ComponentFactory._execution_class_parameter_path_leaves
          .find(_.name == "mcp-server-set").value
        val resolution = snapshot.resolve(segment, leaf).toOption.value

        Then("CNCF precedence selects the runtime alias and the parser sees canonical behavior")
        resolution.value shouldBe Some("runtime-research")
        resolution.provenance shouldBe ComponentParameterProvenance.RuntimeConfiguration
        component.port.get[McpClientSocket].map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("runtime-research"))
      }

      "prefer a named instance over assembly and packaged execution-class defaults" in {
        Given("packaged and assembly defaults plus a named component-instance value")
        given ExecutionContext = ExecutionContext.create()
        val subsystem = new Subsystem(
          "textus-ai-execution-class-instance-precedence",
          configuration = ResolvedConfiguration(
            Configuration(Map("textus.ai.profile" -> ConfigurationValue.StringValue("gemini"))),
            ConfigurationTrace.empty
          )
        ).withDescriptor(GenericSubsystemDescriptor(
          java.nio.file.Paths.get("/textus-ai-execution-class-instance-precedence.yaml"),
          "textus-ai-execution-class-instance-precedence",
          config = Map(
            "textus.ai.execution-classes.standard-work.mcp-server-set" -> "assembly-research"
          )
        ))
        val descriptor = ComponentDescriptor(
          componentName = Some("textus-ai-runtime"),
          config = Map(
            "textus.ai.execution-classes.standard-work.mcp-server-set" -> "packaged-research"
          )
        )

        When("the named component instance is initialized without a higher overlay")
        val component = new ComponentFactory().createPrimaryC(ComponentCreate(
          subsystem,
          ComponentOrigin.Main,
          componentDescriptors = Vector(descriptor),
          instanceMetadata = Some(ComponentInstanceMetadata(
            "textus-ai-runtime",
            "instance-precedence",
            Map(
              "textus.ai.execution-classes.standard-work.mcp-server-set" -> "instance-research"
            )
          ))
        )).toOption.value
        val route = ComponentFactory.executionClassParameterPathRoute
        val snapshot = component.initializationParameters.resolvePath(route).toOption.value
        val segment = snapshot.segments.find(_.value == "standard-work").value
        val leaf = ComponentFactory._execution_class_parameter_path_leaves
          .find(_.name == "mcp-server-set").value
        val resolution = snapshot.resolve(segment, leaf).toOption.value

        Then("the named-instance value is the immutable typed value consumed by the parser")
        resolution.value shouldBe Some("instance-research")
        resolution.provenance shouldBe ComponentParameterProvenance.SubsystemInstance
        component.port.get[McpClientSocket].map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("instance-research"))
      }

      "prevent raw nested execution-class values from bypassing the typed projection" in {
        Given("nested and direct raw execution-class values for the same declared leaf")
        given ExecutionContext = ExecutionContext.create()
        val rawtrace = ConfigurationTrace(Map(
          "textus.ai.execution-classes.standard-work.mcp-server-set" -> ConfigurationResolution(
            "textus.ai.execution-classes.standard-work.mcp-server-set",
            ConfigurationValue.StringValue("direct-research"),
            ConfigurationOrigin.Environment,
            Nil,
            sourceType = Some("fixture"),
            sourceId = Some("raw-route-fixture")
          )
        ))
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.execution-classes.standard-work.mcp-server-set" ->
              ConfigurationValue.StringValue("direct-research"),
            "textus" -> ConfigurationValue.ObjectValue(Map(
              "ai" -> ConfigurationValue.ObjectValue(Map(
                "execution-classes" -> ConfigurationValue.ObjectValue(Map(
                  "standard-work" -> ConfigurationValue.ObjectValue(Map(
                    "mcp-server-set" -> ConfigurationValue.StringValue("nested-research")
                  ))
                ))
              ))
            ))
          )),
          rawtrace
        )
        val subsystem = new Subsystem(
          "textus-ai-execution-class-raw-projection",
          configuration = configuration
        )

        When("component initialization resolves the declared route and projects it for the parser")
        val component = new ComponentFactory().createPrimaryC(
          ComponentCreate(subsystem, ComponentOrigin.Main)
        ).toOption.value
        val route = ComponentFactory.executionClassParameterPathRoute
        val snapshot = component.initializationParameters.resolvePath(route).toOption.value
        val projected = ComponentFactory._execution_class_configuration_c(Some(configuration), snapshot)
          .toOption.flatten.value

        Then("the direct typed value is canonical, no nested raw route value survives, and the parser projection has no raw provenance")
        projected.configuration.values.get(
          "textus.ai.execution-classes.standard-work.mcp-server-set"
        ) shouldBe Some(ConfigurationValue.StringValue("direct-research"))
        projected.trace shouldBe ConfigurationTrace.empty
        configuration.trace shouldBe rawtrace
        component.port.get[McpClientSocket].map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("direct-research"))
      }

      "ignore ambient execution-class properties outside the typed projection" in {
        Given("a unique ambient execution-class JVM property and one real projected class key")
        given ExecutionContext = ExecutionContext.create()
        val ambientkey = "textus.ai.execution-classes.deep-thinking.model"
        val original = Option(System.getProperty(ambientkey))
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.execution-classes.standard-work.model" ->
              ConfigurationValue.StringValue("projected-standard-model")
          )),
          ConfigurationTrace.empty
        )

        try {
          System.setProperty(ambientkey, "ambient-deep-thinking-model")

          When("normal component initialization discovers and projects the declared route")
          val component = new ComponentFactory().createPrimaryC(ComponentCreate(
            new Subsystem("textus-ai-execution-class-ambient-isolation", configuration = configuration),
            ComponentOrigin.Main
          )).toOption.value
          val snapshot = component.initializationParameters
            .resolvePath(ComponentFactory.executionClassParameterPathRoute)
            .toOption.value
          val projected = ComponentFactory._execution_class_configuration_c(Some(configuration), snapshot)
            .toOption.flatten.value
          val profiles = AiProfileConfig.fromConfiguration(Some(projected), selectedprofile = Some("gemini"))
          val standard = profiles.resolveRequired(AiRunnerRequirement(purpose = Some("standard-work")))
          val deep = profiles.resolveRequired(AiRunnerRequirement(purpose = Some("deep-thinking")))

          Then("only the actual projected segment is discovered, its value applies, and the ambient class value is ignored")
          snapshot.segments.map(_.value) shouldBe Vector("standard-work")
          standard.toOption.flatMap(_.requirement.model) shouldBe Some("projected-standard-model")
          deep.toOption.flatMap(_.requirement.model) shouldBe Some("gemini-2.5-pro")
        } finally {
          original match {
            case Some(value) => System.setProperty(ambientkey, value)
            case None => System.clearProperty(ambientkey)
          }
        }
      }

      "declare and resolve every execution-class vocabulary spelling through normal initialization" in {
        Given("the frozen canonical leaves, leaf aliases, and five accepted route prefixes")
        given ExecutionContext = ExecutionContext.create()
        val route = ComponentFactory.executionClassParameterPathRoute
        val canonicalvalues = Vector(
          "provider" -> "google",
          "mode" -> "remote",
          "engine" -> "gemini",
          "model" -> "gemini-route-model",
          "reasoning-level" -> "medium",
          "tools" -> "web_search",
          "mcp-server-set" -> "route-research",
          "operation-tool-set" -> "route-operations",
          "max-input-tokens" -> "1",
          "max-output-tokens" -> "1",
          "max-reasoning-tokens" -> "1",
          "max-cost-microunits" -> "1",
          "rate-schedule" -> "route-rate",
          "timeout-seconds" -> "1",
          "record-retry-limit" -> "1",
          "max-concurrent" -> "1",
          "strategy-max-repairs" -> "1",
          "strategy-max-provider-attempts" -> "1",
          "fallback-provider" -> "openai",
          "fallback-mode" -> "remote",
          "fallback-engine" -> "gpt",
          "fallback-model" -> "gpt-route-fallback",
          "fallback-reasoning-level" -> "medium",
          "fallback-rate-schedule" -> "route-fallback-rate"
        )
        val aliases = Vector(
          "reasoningLevel" -> ("reasoning-level", "medium"),
          "enabled-tools" -> ("tools", "web_search"),
          "mcpServerSet" -> ("mcp-server-set", "route-research"),
          "operationToolSet" -> ("operation-tool-set", "route-operations")
        )
        val prefixes = Vector(
          "textus.ai.execution-classes",
          "textus.ai.executionClasses",
          "textus.runtime.ai.execution-classes",
          "cncf.ai.execution-classes",
          "cncf.runtime.ai.execution-classes"
        )
        val canonicalvaluebyname: Map[String, String] = canonicalvalues.toMap
        val aliasbyname: Map[String, (String, String)] = aliases.toMap

        When("each spelling is resolved by the declared CNCF route during normal factory initialization")
        val canonical = canonicalvalues.map { case (leafname, value) =>
          leafname -> _route_resolution(route, s"${route.prefix}.standard-work.$leafname", leafname, value)
        }
        val compatibleprefixes = prefixes.map { prefix =>
          prefix -> _route_resolution(route, s"$prefix.standard-work.model", "model", prefix)
        }
        val compatibleleaves = aliases.map { case (alias, (canonicalname, value)) =>
          alias -> _route_resolution(route, s"${route.prefix}.standard-work.$alias", canonicalname, value)
        }

        Then("the public declaration is complete and every canonical or compatibility spelling yields its canonical typed value")
        route.prefix shouldBe prefixes.head
        route.prefixAliases shouldBe prefixes.tail
        route.leaves.map(_.name) shouldBe canonicalvalues.map(_._1)
        route.leaves.map(leaf => leaf.name -> leaf.aliases).toMap shouldBe Map(
          "reasoning-level" -> Vector("reasoningLevel"),
          "tools" -> Vector("enabled-tools"),
          "mcp-server-set" -> Vector("mcpServerSet"),
          "operation-tool-set" -> Vector("operationToolSet")
        ) ++ canonicalvalues.map(_._1).filterNot(Set(
          "reasoning-level", "tools", "mcp-server-set", "operation-tool-set"
        )).map(_ -> Vector.empty).toMap
        canonical.foreach { case (leafname, resolution) =>
          val expected: Option[String] = canonicalvaluebyname.get(leafname)
          resolution.value shouldBe expected
        }
        compatibleprefixes.foreach { case (prefix, resolution) =>
          resolution.value shouldBe Some(prefix)
        }
        compatibleleaves.foreach { case (alias, resolution) =>
          val expected: Option[String] = aliasbyname.get(alias).map(_._2)
          resolution.value shouldBe expected
        }
      }

      "reject unknown leaves and invalid route shapes before constructing providers" in {
        Given("one unknown execution-class leaf and one path with an extra segment")
        val unknown = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.execution-classes.standard-work.unknown-leaf" ->
              ConfigurationValue.StringValue("unexpected")
          )),
          ConfigurationTrace.empty
        )
        val invalidshape = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.execution-classes.standard-work.mcp-server-set.extra" ->
              ConfigurationValue.StringValue("unexpected")
          )),
          ConfigurationTrace.empty
        )

        When("the component factory admits each configuration")
        val unknownresult = new ComponentFactory().createPrimaryC(ComponentCreate(
          new Subsystem("textus-ai-execution-class-unknown-leaf", configuration = unknown),
          ComponentOrigin.Main
        ))
        val invalidshaperesult = new ComponentFactory().createPrimaryC(ComponentCreate(
          new Subsystem("textus-ai-execution-class-invalid-shape", configuration = invalidshape),
          ComponentOrigin.Main
        ))

        Then("CNCF rejects both paths before Textus AI can construct a provider")
        _assert_route_rejection(
          unknownresult,
          "textus.ai.execution-classes.standard-work.unknown-leaf"
        )
        _assert_route_rejection(
          invalidshaperesult,
          "textus.ai.execution-classes.standard-work.mcp-server-set.extra"
        )
      }
    }

    "publish runtime ports and providers" which {
      "publish only a normalized MCP client input socket for runtime-owned server sets" in {
        Given("a Textus AI execution class configured with one logical MCP server set")
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
            "textus.ai.execution-classes.standard-work.mcp-server-set" -> ConfigurationValue.StringValue("research")
          )),
          ConfigurationTrace.empty
        )

        When("the component factory constructs its CNCF Port")
        val component = _component(configuration)
        val socket = component.port.get[McpClientSocket]

        Then("the consumer exposes only the logical requirement and no installed provider details")
        socket.map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("research"))
        socket.exists(_.isInstalled) shouldBe false
        component.port.inputEntries.collect { case value: McpClientSocket => value }.size shouldBe 1
      }

      "install configured purpose concurrency admission in the provider component scope" in {
        Given("a Textus AI runtime with one bootstrap-registered bounded ArtScene purpose")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemini")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          name = "textus-ai-concurrency-scope-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val application = new Component() {}.withPort(Component.Port.of(
          AiRunnerApplicationPurposeRegistration(Vector(AiRunnerApplicationPurpose(
            "artscene-exhibition-web-research",
            "web-analysis",
            AiRunnerApplicationPurposePolicy(maxConcurrent = Some(1))
          )))
        ))
        SpiResolver.resolve(Vector(application, component)) shouldBe a[Consequence.Success[_]]
        val parent = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-concurrency-scope-spec",
          None,
          summon[ExecutionContext].observability
        )
        component.withScopeContext(parent)
        val key = ConcurrencyScopeId.parseC("artscene-exhibition-web-research").toOption.get

        When("the component resolves and consumes the configured permit")
        val admission = component.scopeContext.scopedConcurrencyAdmissionOption
        val first = admission.flatMap(_.acquireC(key).toOption)
        val saturated = admission.map(_.acquireC(key))
        first.foreach(_.release())

        Then("the component owns a per-purpose runtime admission boundary")
        admission should not be empty
        saturated.exists(_.isFaillure) shouldBe true
      }

      "publish the artifact component name required by an assembly descriptor" in {
        Given("a Textus AI Runtime component factory")
        val factory = new ComponentFactory()
        val subsystem = new Subsystem(
          name = "textus-ai-runtime-spec",
          configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
        )

        When("the factory creates its primary component")
        val bundle = factory.create(ComponentCreate(subsystem, ComponentOrigin.Main))

        Then("the component core matches the CAR descriptor component name")
        bundle.primary.core.name shouldBe "textus-ai-runtime"
      }

      "canonicalize the codex-cli provider alias before deriving defaults" in {
        Given("a configured and enabled Codex CLI provider alias")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.provider" -> ConfigurationValue.StringValue("codex-cli"),
            "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
            "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli")
          )),
          ConfigurationTrace.empty
        )
        val component = _component(configuration)

        When("the component publishes its AI runner SPI provider")
        val provider = component.port.get[TextusAiRunnerProvider]

        Then("the default selection resolves to the Codex CLI engine")
        provider should not be empty
        provider.value.supports(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection()
        ) shouldBe true
      }

      "bind commercial providers only from merged CNCF configuration" in {
        Given("an empty runtime configuration and explicitly configured commercial runtimes")
        given ExecutionContext = ExecutionContext.create()
        val empty = _component()
        val openaiconfigured = _component(ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.openai.api-key" -> ConfigurationValue.StringValue("test-openai-key"),
            "textus.ai.openai.model" -> ConfigurationValue.StringValue("gpt-test")
          )),
          ConfigurationTrace.empty
        ))
        val googleconfigured = _component(ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.google.api-key" -> ConfigurationValue.StringValue("test-google-key"),
            "textus.ai.google.model" -> ConfigurationValue.StringValue("gemini-test")
          )),
          ConfigurationTrace.empty
        ))
        val anthropicconfigured = _component(ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.anthropic.api-key" -> ConfigurationValue.StringValue("test-anthropic-key")
          )),
          ConfigurationTrace.empty
        ))
        val contract = SpiContract("ai-runner", classOf[AiRunner])
        val openaiselection = SpiSelection(provider = Some("openai"))
        val googleselection = SpiSelection(provider = Some("google"))
        val anthropicselection = SpiSelection(provider = Some("anthropic"))

        When("the component resolves commercial AI runners")
        val emptyopenai = empty.port.get[TextusAiRunnerProvider].value.provide(contract, openaiselection)
        val emptygoogle = empty.port.get[TextusAiRunnerProvider].value.provide(contract, googleselection)
        val emptyanthropic = empty.port.get[TextusAiRunnerProvider].value.provide(contract, anthropicselection)
        val configuredopenai = openaiconfigured.port.get[TextusAiRunnerProvider].value.provide(contract, openaiselection)
        val configuredgoogle = googleconfigured.port.get[TextusAiRunnerProvider].value.provide(contract, googleselection)
        val configuredanthropic = anthropicconfigured.port.get[TextusAiRunnerProvider].value.provide(contract, anthropicselection)

        Then("only explicit merged configuration admits the commercial provider")
        emptyopenai.isFaillure shouldBe true
        emptygoogle.isFaillure shouldBe true
        emptyanthropic.isFaillure shouldBe true
        configuredopenai.isSuccess shouldBe true
        configuredgoogle.isSuccess shouldBe true
        configuredanthropic.isSuccess shouldBe true
      }
    }

    "construct managed runtime capabilities" which {
      "install the enabled Codex capability into the component execution scope" in {
        Given("an explicitly enabled Codex runtime with a trusted executable location")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("codex-cli"),
            "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
            "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          name = "textus-ai-codex-scope-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val parent = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-codex-scope-spec",
          None,
          summon[ExecutionContext].observability
        )
        component.withScopeContext(parent)
        val capability = ProcessCapabilityId.parseC("codex-cli").toOption.get

        When("the runtime admits its plain and record provider suffixes")
        val plain = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("-"))
        )
        val record = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("--output-schema", "schema.json", "-"))
        )
        val unsafe = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("-", "--output-schema", "schema.json"))
        )
        val schema = ProcessArtifactName.parseC("schema").toOption.get
        val alternatepath = WorkAreaRelativePath.parseC("alternate-schema.json").toOption.get
        val alternateinput = ProcessExecutionInputFile.createC(schema, alternatepath, Vector(1.toByte), 16L).toOption.get
        val relocated = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(
            capability,
            Vector("--output-schema", "schema.json", "-"),
            inputFiles = Vector(alternateinput)
          )
        )

        Then("the component contributes only the fixed Codex invocation protocol and local driver")
        plain.toOption.map(_.effectiveArguments) shouldBe Some(Vector("exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"))
        record.toOption.map(_.effectiveArguments) shouldBe Some(Vector("exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "--output-schema", "schema.json", "-"))
        unsafe.isFaillure shouldBe true
        relocated.isFaillure shouldBe true
        component.scopeContext.processExecutionDriverOption.exists(_.isInstanceOf[LocalProcessExecutionDriver]) shouldBe true
      }

      "declare a managed Ollama service only when the Docker runtime is explicit" in {
        Given("a Gemma runtime profile with an explicit managed Docker selection")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
            "textus.ai.gemma.runtime" -> ConfigurationValue.StringValue("managed-docker"),
            "textus.ai.gemma.service.image" -> ConfigurationValue.StringValue("example/ollama:test"),
            "textus.ai.gemma.service.volume-name" -> ConfigurationValue.StringValue("textus-ai-test-models")
          )),
          ConfigurationTrace.empty
        )
        val profiles = AiProfileConfig.fromConfiguration(Some(configuration), AiApplicationPurposeCatalog.empty)
        val managed = ComponentFactory._ollama_managed_service_config(Some(configuration), profiles)
        val subsystem = new Subsystem(
          name = "textus-ai-ollama-scope-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val parent = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-ollama-scope-spec",
          None,
          summon[ExecutionContext].observability
        )
        component.withScopeContext(parent)
        When("the component and its managed service definition are resolved")
        val definition = managed.map(_.definitionC)

        Then("lifecycle intent is typed while the component scope has no Docker command capability")
        managed should not be empty
        definition.exists(_.isSuccess) shouldBe true
        definition.flatMap(_.toOption).map(_.image.print) shouldBe Some("example/ollama:test")
        definition.flatMap(_.toOption).map(_.serviceId.print) shouldBe Some("ollama")
        definition.flatMap(_.toOption).map(_.persistence).collect {
          case x: org.goldenport.cncf.servicecontainer.ServiceContainerPersistence.NamedVolumes =>
            x.volumes.map(volume => volume.name.print -> volume.target.print)
        } shouldBe Some(Vector("textus-ai-test-models" -> "/root/.ollama"))
        component.scopeContext.processExecutionAdmissionOption shouldBe empty
        component.scopeContext.processExecutionDriverOption shouldBe empty
      }

      "use native Ollama by default without declaring a managed Docker service" in {
        Given("a Gemma profile with no Docker or endpoint configuration")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemma")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          name = "textus-ai-external-ollama-scope-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val parent = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-external-ollama-scope-spec",
          None,
          summon[ExecutionContext].observability
        )
        component.withScopeContext(parent)
        val profiles = AiProfileConfig.fromConfiguration(Some(configuration), AiApplicationPurposeCatalog.empty)

        When("the managed service configuration and component scope are inspected")
        val managed = ComponentFactory._ollama_managed_service_config(Some(configuration), profiles)
        val gemma = ComponentFactory._gemma_runtime_config(Some(configuration), profiles, Some(subsystem))

        Then("native Ollama remains selected and managed lifecycle is not installed")
        managed shouldBe empty
        gemma.runtime shouldBe "native"
        gemma.endpoint.toString shouldBe "http://127.0.0.1:11434"
        component.scopeContext.processExecutionDriverOption shouldBe empty
      }

      "reject a managed Docker selection that also supplies a native endpoint" in {
        Given("a conflicting managed Docker and native endpoint configuration")
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemma"),
            "textus.ai.gemma.endpoint" -> ConfigurationValue.StringValue("http://127.0.0.1:11434"),
            "textus.ai.gemma.runtime" -> ConfigurationValue.StringValue("managed-docker")
          )),
          ConfigurationTrace.empty
        )
        val profiles = AiProfileConfig.fromConfiguration(Some(configuration), AiApplicationPurposeCatalog.empty)
        val subsystem = new Subsystem(
          name = "textus-ai-native-ollama-fallback-config-spec",
          configuration = configuration
        )

        When("the Gemma runtime configuration is resolved")
        val managed = ComponentFactory._ollama_managed_service_config(Some(configuration), profiles)
        val gemma = ComponentFactory._gemma_runtime_config(Some(configuration), profiles, Some(subsystem))

        Then("the managed runtime records a structural configuration error and never bootstraps Docker")
        managed shouldBe empty
        gemma.bootstrap shouldBe empty
        gemma.configurationError shouldBe Some("Gemma managed-docker runtime may not set textus.ai.gemma.endpoint")
        subsystem.shutdown()
      }

      "combine managed Gemma lifecycle with the Codex CLI one-shot capability" in {
        Given("a Gemma-simple Codex profile with an enabled Codex CLI runtime")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("gemma-simple-codex-cli"),
            "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
            "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          name = "textus-ai-composite-local-scope-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val parent = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-composite-local-scope-spec",
          None,
          summon[ExecutionContext].observability
        )
        component.withScopeContext(parent)
        val codex = ProcessCapabilityId.parseC("codex-cli").toOption.get

        When("the composite runtime admits its one-shot Codex backend")
        val codexresult = ProcessExecutionAdmission.resolveC(component.scopeContext, ProcessExecutionRequest(codex, Vector("-")))

        Then("Ollama lifecycle remains outside Process Execution while Codex stays admitted")
        codexresult.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
          "exec", "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"
        ))
      }

      "install the enabled Claude Code capability from its runtime profile" in {
        Given("an enabled Claude Code runtime with a trusted executable location")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("claude-code"),
            "textus.ai.claude.enabled" -> ConfigurationValue.StringValue("true"),
            "textus.ai.claude.executable" -> ConfigurationValue.StringValue("/runtime/claude")
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          name = "textus-ai-claude-code-scope-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val parent = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-claude-code-scope-spec",
          None,
          summon[ExecutionContext].observability
        )
        component.withScopeContext(parent)
        val capability = ProcessCapabilityId.parseC("claude-code-profile-runtime-standard-work").toOption.get

        When("the runtime resolves its standard-work process request")
        val result = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("-"))
        )

        Then("only Claude Code print-mode JSON arguments and the profile model are admitted")
        result.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
          "-p", "--output-format", "json", "--model", "sonnet", "--max-turns", "1", "-"
        ))
        component.scopeContext.processExecutionDriverOption.exists(_.isInstanceOf[LocalProcessExecutionDriver]) shouldBe true
      }

      "compile a Codex runtime-profile binding into fixed model, reasoning, and Web arguments" in {
        Given("an enabled Codex runtime and a deep-thinking Web binding")
        given ExecutionContext = ExecutionContext.create()
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("codex-cli"),
            "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
            "textus.ai.codex.executable" -> ConfigurationValue.StringValue("/runtime/codex-cli"),
            "textus.ai.execution-classes.deep-thinking.reasoning-level" -> ConfigurationValue.StringValue("high"),
            "textus.ai.execution-classes.deep-thinking.tools" -> ConfigurationValue.StringValue("url_context,web_search")
          )),
          ConfigurationTrace.empty
        )
        val component = new ComponentFactory().create(ComponentCreate(
          new Subsystem(name = "textus-ai-codex-profile-spec", configuration = configuration),
          ComponentOrigin.Main
        )).primary
        component.withScopeContext(ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-codex-profile-spec",
          None,
          summon[ExecutionContext].observability
        ))
        val capability = ProcessCapabilityId.parseC("codex-cli-profile-runtime-deep-thinking-web").toOption.get
        val versioncapability = ProcessCapabilityId.parseC("codex-cli-profile-runtime-deep-thinking-version").toOption.get

        When("the runtime-profile Web capability is resolved")
        val result = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("-"))
        )
        val version = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(versioncapability)
        )

        Then("only the fixed approved model, reasoning, and global Web option are admitted")
        result.toOption.map(_.effectiveArguments) shouldBe Some(Vector(
          "--search", "exec", "--model", "gpt-5.6-sol",
          "--config", "model_reasoning_effort=\"high\"",
          "--sandbox", "read-only", "--ephemeral", "--skip-git-repo-check", "-"
        ))
        version.toOption.map(_.effectiveArguments) shouldBe Some(Vector("--version"))
      }

      "leave Codex process execution unavailable when the provider is disabled" in {
        Given("a Textus AI runtime without enabled Codex configuration")
        given ExecutionContext = ExecutionContext.create()
        val subsystem = new Subsystem(
          name = "textus-ai-codex-disabled-spec",
          configuration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        component.withScopeContext(ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-codex-disabled-spec",
          None,
          summon[ExecutionContext].observability
        ))
        val capability = ProcessCapabilityId.parseC("codex-cli").toOption.get

        When("a Codex process request is resolved")
        val result = ProcessExecutionAdmission.resolveC(
          component.scopeContext,
          ProcessExecutionRequest(capability, Vector("-"))
        )

        Then("no component-owned Codex capability or local driver has been installed")
        result.isFaillure shouldBe true
        component.scopeContext.processExecutionDriverOption shouldBe None
      }
    }

    "execute through the provider-owned component scope" which {
      "execute Codex requests through the provider component scope from a sibling caller scope" in {
        Given("an enabled component-local Codex driver, a sibling caller scope, and a controlled executable")
        val executable = java.nio.file.Files.createTempFile("textus-ai-codex-spec", ".sh")
        java.nio.file.Files.writeString(executable,
          "#!/bin/sh\n" +
            "if [ \"$1\" = \"--version\" ]; then printf 'codex-cli 0.144.0'; exit 0; fi\n" +
            "if [ -f schema.json ]; then marker=record; else marker=plain; fi\n" +
            "if [ -z \"$HOME\" ]; then home=empty; else home=set; fi\n" +
            "args=$(printf '%s' \"$*\" | tr -d '\"')\n" +
            "printf '{\"title\":\"%s:%s:%s\"}' \"$marker\" \"$home\" \"$args\"\n"
        )
        executable.toFile.setExecutable(true)
        val configuration = ResolvedConfiguration(
          Configuration(Map(
            "textus.ai.profile" -> ConfigurationValue.StringValue("codex-cli"),
            "textus.ai.codex.enabled" -> ConfigurationValue.StringValue("true"),
            "textus.ai.codex.executable" -> ConfigurationValue.StringValue(executable.toString)
          )),
          ConfigurationTrace.empty
        )
        val subsystem = new Subsystem(
          name = "textus-ai-codex-live-spec",
          configuration = configuration
        )
        val component = new ComponentFactory().create(ComponentCreate(subsystem, ComponentOrigin.Main)).primary
        val parentcontext = ExecutionContext.create()
        val runtimescope = ScopeContext(
          ScopeKind.Runtime,
          "textus-ai-codex-live-spec",
          None,
          parentcontext.observability
        )
        component.withScopeContext(runtimescope)
        val callerscope = ScopeContext(
          ScopeKind.Component,
          "textus-ai-codex-caller-spec",
          Some(runtimescope),
          parentcontext.observability
        )
        given ExecutionContext = _runtime_context(callerscope)
        val runner = component.port.get[TextusAiRunnerProvider].value.provide(
          SpiContract("ai-runner", classOf[AiRunner]),
          SpiSelection()
        ).toOption.get

        When("plain, structured, and chat requests reach the component-owned local driver")
        val (generated, recorded, chatted) = try {
          (
            runner.generate(AiGenerateRequest("plain prompt", requirement = AiRunnerRequirement(
              purpose = Some("software-implementation"),
              purposeRequired = true
            ))),
            runner.generateRecord(AiRecordRequest(
              "record prompt",
              Record.dataAuto("required" -> Vector("title")),
              requirement = AiRunnerRequirement(
                purpose = Some("software-implementation"),
                purposeRequired = true
              )
            )),
            runner.chat(AiChatRequest(
              Vector(AiMessage("user", "chat prompt")),
              requirement = AiRunnerRequirement(
                purpose = Some("software-implementation"),
                purposeRequired = true
              )
            ))
          )
        } finally {
          java.nio.file.Files.deleteIfExists(executable)
        }

        Then("the fixed command, empty environment, and bounded schema file are executed in provider-owned managed WorkAreas")
        withClue(generated.toString) {
          generated.toOption.map(_.text) shouldBe Some("{\"title\":\"plain:empty:exec --model gpt-5.6-terra --config model_reasoning_effort=high --sandbox read-only --ephemeral --skip-git-repo-check -\"}")
        }
        recorded.toOption.flatMap(_.record.getAny("title")) shouldBe Some("record:empty:exec --model gpt-5.6-terra --config model_reasoning_effort=high --sandbox read-only --ephemeral --skip-git-repo-check --output-schema schema.json -")
        chatted.toOption.map(_.message.content) shouldBe Some("{\"title\":\"plain:empty:exec --model gpt-5.6-terra --config model_reasoning_effort=high --sandbox read-only --ephemeral --skip-git-repo-check -\"}")
        callerscope.processExecutionDriverOption shouldBe None
        callerscope.processExecutionAdmissionOption shouldBe None
      }
    }
  }

  private def _component(
    configuration: ResolvedConfiguration = ResolvedConfiguration(Configuration.empty, ConfigurationTrace.empty)
  ): Component =
    new ComponentFactory().createPrimaryC(ComponentCreate(
      new Subsystem("textus-ai-normal-factory-spec", configuration = configuration),
      ComponentOrigin.Main
    )).toOption.value

  private def _route_resolution(
    route: org.goldenport.cncf.config.ComponentParameterPathRoute,
    path: String,
    leafname: String,
    value: String
  ) = {
    val component = _component(ResolvedConfiguration(
      Configuration(Map(
        "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
        path -> ConfigurationValue.StringValue(value)
      )),
      ConfigurationTrace.empty
    ))
    val snapshot = component.initializationParameters.resolvePath(route).toOption.value
    val segment = snapshot.segments.find(_.value == "standard-work").value
    val leaf = ComponentFactory._execution_class_parameter_path_leaves.find(_.name == leafname).value
    snapshot.resolve(segment, leaf).toOption.value
  }

  private def _assert_route_rejection(
    result: Consequence[Component],
    path: String
  ): Unit =
    result match {
      case Consequence.Failure(conclusion) =>
        val facets = conclusion.observation.cause.descriptor.facets
        conclusion.observation.taxonomy.category shouldBe Taxonomy.Category.Configuration
        conclusion.observation.cause.kind shouldBe Some(Cause.Kind.Policy)
        facets should contain (Descriptor.Facet.Policy("component-initialization-parameter"))
        facets should contain (Descriptor.Facet.Reason("rejected"))
        facets should contain (Descriptor.Facet.Parameter.argument(path))
        result.toOption shouldBe empty
      case Consequence.Success(component) =>
        fail(s"route rejection unexpectedly constructed component: ${component.core.name}")
    }

  private def _runtime_context(scope: ScopeContext): ExecutionContext = {
    GlobalContext.set(GlobalContext(WorkAreaSpace.create(RuntimeConfig.default)))
    val base = ExecutionContext.create()
    var runtime: RuntimeContext = null
    lazy val context: ExecutionContext = ExecutionContext.withRuntimeContext(base, runtime)
    lazy val uow = new UnitOfWork(context)
    runtime = new RuntimeContext(
      core = RuntimeContext.core(
        name = "textus-ai-codex-live-spec",
        parent = Some(scope),
        observabilitycontext = base.observability
      ),
      unitofworksupplier = () => uow,
      unitofworkinterpreterfn = new (UnitOfWorkOp ~> Consequence) {
        def apply[A](operation: UnitOfWorkOp[A]): Consequence[A] =
          new UnitOfWorkInterpreter(uow).interpret(operation)
      },
      commitaction = _ => (),
      abortaction = _ => (),
      disposeaction = _ => (),
      token = "textus-ai-codex-live-spec"
    )
    context
  }
}
