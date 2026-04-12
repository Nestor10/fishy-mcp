package fishy.mcp.domain.model

import zio.*
import zio.json.ast.Json

/** Caller identity extracted from an authenticated HTTP request.
  *
  * Populated by the security policy (JWT validation or trusted-header extraction) and propagated to
  * handlers via `ToolContext.auth`.
  *
  * All fields are optional except `sub` (the subject / principal identifier).
  */
final case class AuthContext(
    sub: String,
    email: Option[String] = None,
    name: Option[String] = None,
    groups: Set[String] = Set.empty,
    scopes: Set[String] = Set.empty,
    rawClaims: Option[Json] = None
):

  /** Check whether this identity has all the required scopes. */
  def hasScopes(required: Set[String]): Boolean =
    required.subsetOf(scopes)

/** Fiber-local carrier for the authenticated caller identity.
  *
  * Set by HTTP security middleware (adapter layer), read by ToolExecutor (application layer) when
  * building ToolContext. This keeps auth as an invisible cross-cutting concern -- no parameter
  * threading needed through the dispatch chain.
  *
  * Lives in domain because it depends only on ZIO (FiberRef) and AuthContext.
  */
object AuthFiberRef:

  val currentAuth: FiberRef[Option[AuthContext]] =
    Unsafe.unsafe { implicit u =>
      FiberRef.unsafe.make(None)
    }
