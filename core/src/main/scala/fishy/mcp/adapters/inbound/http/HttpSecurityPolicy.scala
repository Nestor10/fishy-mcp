package fishy.mcp.adapters.inbound.http

import fishy.mcp.domain.model.{AuthContext, AuthFiberRef}
import zio.*
import zio.http.*

/** Pluggable HTTP security policy.
  *
  * Extracts caller identity from the request and sets the `AuthFiberRef`. Implementations can
  * reject requests (401/403) or allow them through with or without identity.
  */
trait HttpSecurityPolicy:

  def middleware: HandlerAspect[Any, Unit]

object HttpSecurityPolicy:

  def middleware: URIO[HttpSecurityPolicy, HandlerAspect[Any, Unit]] =
    ZIO.serviceWith(_.middleware)

  /** No authentication -- `ToolContext.auth` will be `None`. */
  val allowAll: ULayer[HttpSecurityPolicy] =
    ZLayer.succeed(new HttpSecurityPolicy:
      def middleware: HandlerAspect[Any, Unit] = HandlerAspect.identity
    )

  /** Build a policy from a function that extracts `AuthContext` from a request.
    *
    * Returns 401 if the extractor fails. Sets `AuthFiberRef` on success.
    */
  def fromExtractor(extract: Request => IO[String, AuthContext]): ULayer[HttpSecurityPolicy] =
    ZLayer.succeed(new HttpSecurityPolicy:
      def middleware: HandlerAspect[Any, Unit] =
        HandlerAspect.interceptIncomingHandler(
          Handler.fromFunctionZIO[Request] { request =>
            extract(request)
              .foldZIO(
                error => ZIO.fail(Response.text(error).status(Status.Unauthorized)),
                auth => AuthFiberRef.currentAuth.set(Some(auth)).as((request, ()))
              )
          }
        )
    )

  /** Build a policy from an aspect directly (escape hatch for advanced use). */
  def fromAspect(aspect: HandlerAspect[Any, Unit]): ULayer[HttpSecurityPolicy] =
    ZLayer.succeed(new HttpSecurityPolicy:
      def middleware: HandlerAspect[Any, Unit] = aspect
    )
