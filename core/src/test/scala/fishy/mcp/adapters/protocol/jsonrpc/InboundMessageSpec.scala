package fishy.mcp.adapters.protocol.jsonrpc

import fishy.mcp.domain.model.RequestId
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Locks the single inbound-classification taxonomy that both transports share. */
object InboundMessageSpec extends ZIOSpecDefault:

  private def json(s: String): Json = s.fromJson[Json].toOption.get

  def spec = suite("InboundMessage.parse")(
    test("request (method + id) -> Dispatch") {
      val r = InboundMessage.parse(json("""{"jsonrpc":"2.0","method":"tools/list","id":1}"""))
      assertTrue(r match
        case Right(InboundMessage.Dispatch(req)) => req.method == "tools/list" && !req.isNotification
        case _                                   => false
      )
    },
    test("notification (method, no id) -> Dispatch, isNotification") {
      val r = InboundMessage.parse(json("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
      assertTrue(r match
        case Right(InboundMessage.Dispatch(req)) => req.isNotification
        case _                                   => false
      )
    },
    test("response with result (id + result, no method) -> ClientResponse(Right)") {
      val r = InboundMessage.parse(json("""{"jsonrpc":"2.0","id":"srv-1","result":{"ok":true}}"""))
      assertTrue(r match
        case Right(InboundMessage.ClientResponse(RequestId.StringId("srv-1"), Right(_))) => true
        case _                                                                           => false
      )
    },
    test("response with error (id + error, no method) -> ClientResponse(Left)") {
      val r = InboundMessage.parse(json("""{"jsonrpc":"2.0","id":5,"error":{"code":-32000,"message":"boom"}}"""))
      assertTrue(r match
        case Right(InboundMessage.ClientResponse(RequestId.NumberId(n), Left(e))) =>
          n == 5L && e.code == -32000 && e.message == "boom"
        case _ => false
      )
    },
    test("neither method nor id -> Left") {
      assertTrue(InboundMessage.parse(json("""{"jsonrpc":"2.0"}""")).isLeft)
    }
  )
