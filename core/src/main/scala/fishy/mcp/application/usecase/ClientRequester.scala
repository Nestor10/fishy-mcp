package fishy.mcp.application.usecase

import fishy.mcp.domain.model.mcp.ClientCapabilities
import fishy.mcp.adapters.protocol.jsonrpc.{Error as JsonRpcError}
import fishy.mcp.application.ports.MessageRouter
import fishy.mcp.domain.model.{ClientRequesterError, RequestId}
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Sends JSON-RPC requests from server to client and correlates responses.
  *
  * Server-to-client requests (sampling/createMessage, roots/list,
  * elicitation/create) are sent over the SSE stream via `MessageRouter`. The
  * client POSTs back a JSON-RPC response with matching ID. This service
  * correlates outbound request IDs with `Promise`s that waiting fibers can
  * await.
  *
  * Errors are typed via [[ClientRequesterError]] (no `Throwable` channel).
  */
trait ClientRequester:

  /** Send a JSON-RPC request to a session's client and await the response. */
  def sendRequest(
      sessionId: String,
      method: String,
      params: Json,
      timeout: Duration = 30.seconds
  ): IO[ClientRequesterError, Json]

  /** Complete a pending request when the client POSTs back a response. */
  def completeRequest(requestId: String, result: Either[JsonRpcError, Json]): UIO[Boolean]

  /** Register client capabilities from the initialize handshake. */
  def registerClientCapabilities(sessionId: String, caps: ClientCapabilities): UIO[Unit]

  /** Get stored client capabilities for a session. */
  def getClientCapabilities(sessionId: String): UIO[Option[ClientCapabilities]]

  /** Cancel all pending requests for a session (on disconnect). */
  def cancelPendingRequests(sessionId: String): UIO[Unit]

object ClientRequester:

  // ---------------------------------------------------------------------------
  // Accessors
  // ---------------------------------------------------------------------------

  def sendRequest(
      sessionId: String,
      method: String,
      params: Json,
      timeout: Duration = 30.seconds
  ): ZIO[ClientRequester, ClientRequesterError, Json] =
    ZIO.serviceWithZIO(_.sendRequest(sessionId, method, params, timeout))

  def completeRequest(
      requestId: String,
      result: Either[JsonRpcError, Json]
  ): URIO[ClientRequester, Boolean] =
    ZIO.serviceWithZIO(_.completeRequest(requestId, result))

  def registerClientCapabilities(
      sessionId: String,
      caps: ClientCapabilities
  ): URIO[ClientRequester, Unit] =
    ZIO.serviceWithZIO(_.registerClientCapabilities(sessionId, caps))

  def getClientCapabilities(sessionId: String): URIO[ClientRequester, Option[ClientCapabilities]] =
    ZIO.serviceWithZIO(_.getClientCapabilities(sessionId))

  def cancelPendingRequests(sessionId: String): URIO[ClientRequester, Unit] =
    ZIO.serviceWithZIO(_.cancelPendingRequests(sessionId))

  // ---------------------------------------------------------------------------
  // Layer constructors
  // ---------------------------------------------------------------------------

  val layer: URLayer[MessageRouter, ClientRequester] =
    ZLayer {
      for
        router    <- ZIO.service[MessageRouter]
        pending   <- Ref.make(Map.empty[String, Promise[ClientRequesterError, Json]])
        caps      <- Ref.make(Map.empty[String, ClientCapabilities])
        idCounter <- Ref.make(0L)
      yield Live(router, pending, caps, idCounter)
    }

  // ---------------------------------------------------------------------------
  // Live implementation
  // ---------------------------------------------------------------------------

  private final case class Live(
      messageRouter: MessageRouter,
      pending: Ref[Map[String, Promise[ClientRequesterError, Json]]],
      clientCaps: Ref[Map[String, ClientCapabilities]],
      idCounter: Ref[Long]
  ) extends ClientRequester:

    def sendRequest(
        sessionId: String,
        method: String,
        params: Json,
        timeout: Duration
    ): IO[ClientRequesterError, Json] =
      for
        hasSub  <- messageRouter.hasSubscribers(sessionId)
        _       <- ZIO.when(!hasSub)(
                     ZIO.fail(ClientRequesterError.NoActiveConnection(sessionId))
                   )
        id      <- nextId
        promise <- Promise.make[ClientRequesterError, Json]
        request  = buildRequest(id, method, params)
        _       <- pending.update(_ + (id -> promise))
        _       <- ZIO.logDebug(s"Sending server-to-client request: method=$method, id=$id")
        sent    <- messageRouter.publish(sessionId, request.toJson)
        _       <- ZIO.when(!sent)(
                     pending.update(_ - id) *>
                       ZIO.fail(ClientRequesterError.PublishFailed(sessionId))
                   )
        result  <- promise.await
                     .timeoutFail(ClientRequesterError.RequestTimeout(method, id, timeout))(timeout)
                     .ensuring(pending.update(_ - id))
      yield result

    def completeRequest(requestId: String, result: Either[JsonRpcError, Json]): UIO[Boolean] =
      pending.get.flatMap(_.get(requestId) match
        case None => ZIO.succeed(false)
        case Some(promise) =>
          val effect = result match
            case Right(json) => promise.succeed(json)
            case Left(err) =>
              promise.fail(ClientRequesterError.ClientReturnedError(err.code, err.message, err.data))
          effect *> pending.update(_ - requestId) *> ZIO.succeed(true)
      )

    def registerClientCapabilities(sessionId: String, caps: ClientCapabilities): UIO[Unit] =
      clientCaps.update(_ + (sessionId -> caps))

    def getClientCapabilities(sessionId: String): UIO[Option[ClientCapabilities]] =
      clientCaps.get.map(_.get(sessionId))

    def cancelPendingRequests(sessionId: String): UIO[Unit] =
      pending.modify { map =>
        // All pending request IDs are server-generated; we cancel them all on
        // session disconnect. (When per-session keying is added, filter here.)
        val toCancel = map.values.toList
        (toCancel, Map.empty[String, Promise[ClientRequesterError, Json]])
      }.flatMap { promises =>
        ZIO.foreachDiscard(promises)(
          _.fail(ClientRequesterError.SessionDisconnected(sessionId)).ignore
        )
      }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private def nextId: UIO[String] =
      idCounter.modify(n => (s"srv-${n + 1}", n + 1))

    private def buildRequest(id: String, method: String, params: Json): Json =
      Json.Obj(
        "jsonrpc" -> Json.Str("2.0"),
        "id"      -> Json.Str(id),
        "method"  -> Json.Str(method),
        "params"  -> params
      )
