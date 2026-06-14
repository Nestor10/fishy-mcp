package fishy.mcp.application.usecase.oauth

import zio.*

import java.security.SecureRandom
import java.util.Base64

/** Cryptographically-strong token generator shared by OAuth use-cases.
  *
  * Uses `SecureRandom` (seeded lazily by the JVM) and URL-safe Base64 without
  * padding, matching what RFC 6749 / 7636 / 7009 expect for opaque strings.
  */
object SecureTokens:

  private val encoder = Base64.getUrlEncoder.withoutPadding

  /** One shared, thread-safe `SecureRandom`, seeded once at class load.
    * (Constructing a fresh instance per call is expensive and can block on
    * entropy.) */
  private val random = new SecureRandom()

  /** Return a URL-safe base64 string of `byteLength` entropy. 32 bytes is the
    * spec-recommended minimum for authorization codes and refresh tokens.
    */
  def urlSafe(byteLength: Int = 32): UIO[String] =
    ZIO.succeed {
      val buf = new Array[Byte](byteLength)
      random.nextBytes(buf)
      encoder.encodeToString(buf)
    }
