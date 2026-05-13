package fishy.mcp.adapters.inbound.http

import zio.*
import zio.http.*

/** Extension point for mounting additional HTTP routes alongside MCP routes.
  *
  * Default layer provides no extra routes. Adapters (e.g. the OAuth AS) supply
  * their own implementations. Routes returned here are mounted *before* the
  * security policy so they remain reachable without authentication -- suitable
  * for discovery endpoints and public health probes.
  */
trait HttpExtraRoutes:
  def routes: Routes[Any, Response]

object HttpExtraRoutes:

  val empty: ULayer[HttpExtraRoutes] =
    ZLayer.succeed(new HttpExtraRoutes:
      def routes: Routes[Any, Response] = Routes.empty
    )

  def fromRoutes(r: Routes[Any, Response]): ULayer[HttpExtraRoutes] =
    ZLayer.succeed(new HttpExtraRoutes:
      def routes: Routes[Any, Response] = r
    )

  def layer[R](
      build: ZIO[R, Nothing, Routes[Any, Response]]
  ): URLayer[R, HttpExtraRoutes] =
    ZLayer.fromZIO(build.map(r =>
      new HttpExtraRoutes:
        def routes: Routes[Any, Response] = r
    ))
