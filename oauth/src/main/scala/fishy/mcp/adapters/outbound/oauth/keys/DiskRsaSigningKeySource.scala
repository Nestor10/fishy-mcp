package fishy.mcp.adapters.outbound.oauth.keys

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.{JWK, KeyUse, RSAKey}
import fishy.mcp.application.ports.oauth.SigningKeySource
import zio.*

import java.nio.file.{Files, Path}

/** RSA signing key loaded from a PEM file at startup.
  *
  * Accepts any PEM-encoded RSA private key Nimbus's `JWK.parseFromPEMEncodedObjects`
  * can read (PKCS#8 or traditional PKCS#1). If the file carries a `kid`, it is
  * preserved; otherwise a stable hash of the path becomes the kid, so tokens
  * issued across restarts stay verifiable.
  *
  * Production-safe (no `StubMarker`) — the counterpart to
  * [[RsaSigningKeySource.generated]], which mints a throwaway per-JVM key.
  */
object DiskRsaSigningKeySource:

  final case class Config(path: String, kid: Option[String])

  object Config:
    val config: zio.Config[Config] =
      zio.Config.string("OAUTH_SIGNING_KEY_PATH")
        .zip(zio.Config.string("OAUTH_SIGNING_KEY_KID").optional)
        .map(Config.apply)

  def layer(cfg: Config): ZLayer[Any, Throwable, SigningKeySource] =
    ZLayer.fromZIO(load(cfg)).flatMap(env => RsaSigningKeySource.fromRsaKey(env.get[RSAKey]))

  private def load(cfg: Config): Task[RSAKey] =
    ZIO.attemptBlocking {
      val pem = Files.readString(Path.of(cfg.path))
      JWK.parseFromPEMEncodedObjects(pem) match
        case rsa: RSAKey if rsa.isPrivate =>
          val kid = cfg.kid.getOrElse(Option(rsa.getKeyID).getOrElse(stableKid(cfg.path)))
          new RSAKey.Builder(rsa.toRSAPublicKey)
            .privateKey(rsa.toRSAPrivateKey)
            .keyID(kid)
            .keyUse(Option(rsa.getKeyUse).getOrElse(KeyUse.SIGNATURE))
            .algorithm(Option(rsa.getAlgorithm).getOrElse(JWSAlgorithm.RS256))
            .build()
        case _: RSAKey =>
          throw new IllegalStateException(s"PEM at ${cfg.path} contains only a public key; private key required")
        case other =>
          throw new IllegalStateException(s"PEM at ${cfg.path} is not an RSA key: ${other.getKeyType}")
    }

  private def stableKid(path: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes("UTF-8"))
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(digest).take(16)
