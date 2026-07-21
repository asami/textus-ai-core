package org.simplemodeling.textus.ai

import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin, ExtensionPoint, Port, ServiceContract, VariationSelection}
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.mcp.client.*
import org.goldenport.cncf.subsystem.Subsystem
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * Executable specification for the Textus AI MCP client consumer boundary.
 *
 * @since   Jul. 21, 2026
 * @version Jul. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class McpClientConsumerSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Textus AI MCP client consumer" should {
    "receive only the runtime-admitted remote catalog through its logical input socket" in {
      Given("a Textus AI runtime profile naming one logical server set and a transport reporting an extra tool")
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
        name = "textus-ai-mcp-consumer-spec",
        configuration = configuration
      )
      val component = new ComponentFactory().create(
        ComponentCreate(subsystem, ComponentOrigin.Main)
      ).primary
      val serversetid = McpServerSetId.parseC("research").toOption.get
      val serverid = McpServerId.parseC("catalog").toOption.get
      val admittedname = McpToolName.parseC("paper.search").toOption.get
      val deniedname = McpToolName.parseC("paper.delete").toOption.get
      val serverset = McpClientServerSet.createC(
        serversetid,
        Vector(McpClientServer.createC(serverid, Vector(admittedname)).toOption.get)
      ).toOption.get
      val admittedtool = _tool(serverid, admittedname)
      val deniedtool = _tool(serverid, deniedname)
      val transport = new FakeTransport(Vector(deniedtool, admittedtool))
      val registry = McpClientRuntimeRegistry.createC(
        Vector(serverset),
        _transport_binding(transport)
      ).toOption.get

      When("CNCF runtime assembly installs the admitted MCP client service into the Textus AI component")
      val evidence = try {
        val installed = registry.install(component)
        val socket = component.port.get[McpClientSocket]
        val catalog = socket.flatMap(_.service(serversetid).toOption).flatMap(_.catalog.toOption)
        (installed, socket, catalog)
      } finally {
        registry.close()
        subsystem.shutdown()
      }

      Then("the consumer sees only logical policy identity and the allowlisted provider-neutral tool")
      evidence._1.isSuccess shouldBe true
      evidence._2.map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("research"))
      evidence._3.map(_.tools.map(_.identity.print)) shouldBe Some(Vector("catalog/paper.search"))
      evidence._3.toVector.flatMap(_.tools).map(_.identity) should not contain deniedtool.identity
    }
  }

  private def _tool(
    serverid: McpServerId,
    toolname: McpToolName
  ): McpClientTool =
    McpClientTool.createC(
      McpToolIdentity(serverid, toolname),
      McpInputSchema.objectC(Vector.empty).toOption.get
    ).toOption.get

  private def _transport_binding(
    transport: McpClientTransport
  ): Component.Binding[McpClientTransportRequirement, McpClientTransport] =
    Component.Binding(Port(
      api = McpClientTransportPortApi,
      spi = Vector(new ExtensionPoint[McpClientTransport] {
        def supports(
          contract: ServiceContract[McpClientTransport],
          variation: VariationSelection
        )(using ExecutionContext): Boolean =
          variation == VariationSelection()

        def provide(
          contract: ServiceContract[McpClientTransport],
          variation: VariationSelection
        )(using ExecutionContext): Consequence[McpClientTransport] =
          Consequence.success(transport)
      }),
      variation = McpClientTransportSelectionPoint
    ))

  private final class FakeTransport(
    tools: Vector[McpClientTool]
  ) extends McpClientTransport {
    def initialize(
      server: McpClientServer,
      limits: McpClientLimits
    )(using ExecutionContext): Consequence[Unit] =
      Consequence.unit

    def listTools(
      server: McpClientServer,
      limits: McpClientLimits
    )(using ExecutionContext): Consequence[Vector[McpClientTool]] =
      Consequence.success(tools)

    def callTool(
      server: McpClientServer,
      call: McpClientCall,
      limits: McpClientLimits
    )(using ExecutionContext): Consequence[McpClientResult] =
      Consequence.operationNotFound("Textus AI consumer catalog evidence does not invoke tools")
  }
}
