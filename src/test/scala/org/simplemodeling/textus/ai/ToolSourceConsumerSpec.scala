package org.simplemodeling.textus.ai

import org.goldenport.Consequence
import org.goldenport.cncf.component.{Component, ComponentCreate, ComponentOrigin, ExtensionPoint, Port, ServiceContract, VariationSelection}
import org.goldenport.cncf.component.builtin.BuiltinComponentIdentity
import org.goldenport.cncf.context.ExecutionContext
import org.goldenport.cncf.mcp.client.*
import org.goldenport.cncf.operationtool.*
import org.goldenport.cncf.testutil.RuntimeBindingAdmissionFixture
import org.goldenport.configuration.{Configuration, ConfigurationTrace, ConfigurationValue, ResolvedConfiguration}
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/*
 * Executable specification for the Textus AI admitted tool-source consumer boundary.
 *
 * @since   Jul. 21, 2026
 * @version Aug. 21, 2026
 * @author  ASAMI, Tomoharu
 */
final class ToolSourceConsumerSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Textus AI admitted tool-source consumer" should {
    "receive only the runtime-admitted remote catalog through its logical input socket" in {
      Given("a Textus AI runtime profile naming one logical server set and a transport reporting an extra tool")
      given ExecutionContext = ExecutionContext.create()
      val configuration = ResolvedConfiguration(
        Configuration(Map(
          "textus.ai.profile" -> ConfigurationValue.StringValue("gemini"),
          "textus.ai.execution-classes.standard-work.mcp-server-set" ->
            ConfigurationValue.StringValue("research"),
          "textus.ai.execution-classes.standard-work.operation-tool-set" ->
            ConfigurationValue.StringValue("builtin-tools")
        )),
        ConfigurationTrace.empty
      )
      val subsystem = RuntimeBindingAdmissionFixture.default(configuration = configuration)
      val component = new ComponentFactory().create(
        ComponentCreate(subsystem, ComponentOrigin.Main)
      ).primary
      subsystem.add(component)
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
      val operationtoolsetid = OperationToolSetId.parseC("builtin-tools").toOption.get
      val operationidentity = OperationToolIdentity.createC(BuiltinComponentIdentity.ADMIN.name, "system", "ping").toOption.get
      val operationlimits = OperationToolLimits.createC(4, 4096, 4096, 1).toOption.get
      val operationadmission = OperationToolAdmission.createC(
        operationtoolsetid,
        Vector(operationidentity),
        operationlimits
      ).toOption.get
      val operationregistry = OperationToolRuntimeRegistry.createC(
        subsystem,
        Vector(operationadmission)
      ).toOption.get

      When("CNCF runtime assembly installs both admitted services into the Textus AI component")
      val evidence = try {
        val mcpinstalled = registry.install(component)
        val operationinstalled = operationregistry.install(component)
        val socket = component.port.get[McpClientSocket]
        val operationsocket = component.port.get[OperationToolSocket]
        val catalog = socket.flatMap(_.service(serversetid).toOption).flatMap(_.catalog.toOption)
        val operationservice = operationsocket.flatMap(_.service(operationtoolsetid).toOption)
        val operationcatalog = operationservice.flatMap(_.catalog.toOption)
        val operationresult = operationservice.flatMap(_.withInvocation { invocation =>
          invocation.invoke(OperationToolCall(operationidentity, org.goldenport.record.Record.empty))
        }.toOption)
        (
          mcpinstalled,
          operationinstalled,
          socket,
          operationsocket,
          catalog,
          operationcatalog,
          operationresult
        )
      } finally {
        registry.close()
        subsystem.shutdown()
      }

      Then("the consumer sees only logical policy identities and both runtime-admitted catalogs")
      evidence._1.isSuccess shouldBe true
      evidence._2.isSuccess shouldBe true
      evidence._3.map(_.serverSetIds.map(_.print)) shouldBe Some(Vector("research"))
      evidence._4.map(_.toolSetIds) shouldBe Some(Vector(operationtoolsetid))
      evidence._5.map(_.tools.map(_.identity.print)) shouldBe Some(Vector("catalog/paper.search"))
      evidence._5.toVector.flatMap(_.tools).map(_.identity) should not contain deniedtool.identity
      evidence._6.map(_.definitions.map(_.identity.print)) shouldBe Some(Vector("org.goldenport.cncf.Admin.system.ping"))
      evidence._7 should not be empty
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
