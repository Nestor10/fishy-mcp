package fishy.mcp.application.ports.oauth

import fishy.mcp.domain.model.oauth.JwksKey
import fishy.mcp.domain.model.oauth.Ids.JwkKid
import zio.*

/** Produces JWS-signed access tokens and the JWKS document for verification.
  *
  * The active key is used for signing; previous keys stay in the JWKS until
  * every token signed by them has expired. Rotation is an adapter concern --
  * the SDK only reads via this port.
  */
trait SigningKeySource:
  /** Sign a JWT with the active key and return the compact serialization. */
  def sign(claimsJson: String): IO[SigningKeySource.SigningError, String]

  /** Current active key id (embedded as `kid` in the JWS header). */
  def activeKid: UIO[JwkKid]

  /** All keys that clients should trust, including the active one. */
  def publishedKeys: UIO[List[JwksKey]]

object SigningKeySource:

  enum SigningError(val message: String):
    case NoActiveKey(override val message: String)   extends SigningError(message)
    case SigningFailed(override val message: String) extends SigningError(message)

  def sign(claimsJson: String) = ZIO.serviceWithZIO[SigningKeySource](_.sign(claimsJson))
  def activeKid                = ZIO.serviceWithZIO[SigningKeySource](_.activeKid)
  def publishedKeys            = ZIO.serviceWithZIO[SigningKeySource](_.publishedKeys)
