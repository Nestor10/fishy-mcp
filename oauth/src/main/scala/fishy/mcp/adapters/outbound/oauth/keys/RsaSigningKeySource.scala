package fishy.mcp.adapters.outbound.oauth.keys

import fishy.mcp.application.ports.oauth.{SigningKeySource, StubMarker}
import fishy.mcp.domain.model.oauth.JwksKey
import fishy.mcp.domain.model.oauth.Ids.JwkKid
import zio.*

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.{KeyUse, JWK}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

import java.util.UUID

/** RSA JWT signer backed by nimbus-jose-jwt.
  *
  * Generates a fresh 2048-bit RSA keypair on layer build. The `rotate` method
  * promotes a freshly generated key to active and moves the previous one to
  * the published-only list; clients can still verify in-flight tokens until
  * they expire.
  *
  * Deployments that need to load keys from a secret instead of generating
  * should supply the `RSAKey` directly via `fromRsaKey`.
  */
object RsaSigningKeySource:

  /** Generate a fresh key on startup. Suitable for tests and ephemeral
    * demos. Marked as a stub: tokens issued by one JVM won't validate on
    * another (each instance gets its own key), so production refuses to
    * boot with this wired.
    */
  val generated: ULayer[SigningKeySource] =
    ZLayer.fromZIO(generateKey.flatMap(make).map(GeneratedStub(_)))

  /** Build from a pre-existing RSA private JWK (as produced by nimbus). */
  def fromRsaKey(key: RSAKey): ULayer[SigningKeySource] =
    ZLayer.fromZIO(make(key))

  private def generateKey: UIO[RSAKey] =
    ZIO.succeedBlocking {
      new RSAKeyGenerator(2048)
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .keyID(UUID.randomUUID().toString)
        .generate()
    }

  private def make(initial: RSAKey): UIO[SigningKeySource] =
    for
      activeRef   <- Ref.make(initial)
      previousRef <- Ref.make(List.empty[RSAKey])
    yield Live(activeRef, previousRef)

  private final case class Live(
      active: Ref[RSAKey],
      previous: Ref[List[RSAKey]]
  ) extends SigningKeySource:

    override def sign(claimsJson: String): IO[SigningKeySource.SigningError, String] =
      ZIO.scoped {
        for
          key   <- active.get
          jwt   <- ZIO.attemptBlocking {
            val claims = JWTClaimsSet.parse(claimsJson)
            val header = new JWSHeader.Builder(JWSAlgorithm.RS256)
              .keyID(key.getKeyID)
              .build()
            val signer: JWSSigner = new RSASSASigner(key)
            val jwt = new SignedJWT(header, claims)
            jwt.sign(signer)
            jwt.serialize()
          }.mapError(t => SigningKeySource.SigningError.SigningFailed(t.getMessage))
        yield jwt
      }

    override def activeKid: UIO[JwkKid] =
      active.get.map(k => JwkKid(k.getKeyID))

    override def publishedKeys: UIO[List[JwksKey]] =
      for
        a    <- active.get
        prev <- previous.get
      yield (a :: prev).map(toJwksKey)

  /** Forwarder that adds the [[StubMarker]] mixin without touching the
    * underlying signing logic.
    */
  private final class GeneratedStub(underlying: SigningKeySource)
      extends SigningKeySource with StubMarker:
    override val stubId: String = "RsaSigningKeySource.generated"
    override def sign(claimsJson: String) = underlying.sign(claimsJson)
    override def activeKid = underlying.activeKid
    override def publishedKeys = underlying.publishedKeys

  private def toJwksKey(key: RSAKey): JwksKey =
    JwksKey(
      kid = JwkKid(key.getKeyID),
      kty = "RSA",
      use = "sig",
      alg = key.getAlgorithm.getName,
      n = Some(key.toPublicJWK.asInstanceOf[JWK].toJSONObject.get("n").toString),
      e = Some(key.toPublicJWK.asInstanceOf[JWK].toJSONObject.get("e").toString)
    )
