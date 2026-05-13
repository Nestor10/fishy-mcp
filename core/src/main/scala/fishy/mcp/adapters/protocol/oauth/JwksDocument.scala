package fishy.mcp.adapters.protocol.oauth

import fishy.mcp.domain.model.oauth.JwksKey
import fishy.mcp.domain.model.oauth.Ids.JwkKid
import zio.json.*
import zio.json.ast.Json

/** JWKS document wrapper (`{ "keys": [...] }`).
  *
  * Encoded with explicit field filtering so the wire format is stable and
  * independent of the domain `JwksKey` case class -- we do not want optional
  * `None` fields appearing as `null` on the wire.
  */
final case class JwksDocument(keys: List[JwksKey])

object JwksDocument:

  given JsonEncoder[JwksDocument] =
    JsonEncoder[Json].contramap { doc =>
      Json.Obj("keys" -> Json.Arr(doc.keys.map(encodeKey)*))
    }

  private def encodeKey(k: JwksKey): Json =
    val base = List(
      "kid" -> Json.Str(k.kid.value),
      "kty" -> Json.Str(k.kty),
      "use" -> Json.Str(k.use),
      "alg" -> Json.Str(k.alg)
    )
    val optional = List(
      k.n.map("n"     -> Json.Str(_)),
      k.e.map("e"     -> Json.Str(_)),
      k.crv.map("crv" -> Json.Str(_)),
      k.x.map("x"     -> Json.Str(_)),
      k.y.map("y"     -> Json.Str(_))
    ).flatten
    Json.Obj((base ++ optional)*)
