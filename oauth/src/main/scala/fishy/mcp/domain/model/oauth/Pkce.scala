package fishy.mcp.domain.model.oauth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** PKCE (RFC 7636) primitives.
  *
  * Only S256 is supported -- `plain` is forbidden by OAuth 2.1. Challenge/
  * verifier lengths are validated at construction; verification is a pure
  * function so tests can exercise it directly.
  */
enum PkceMethod:
  case S256

  def name: String = "S256"

opaque type PkceChallenge = String
object PkceChallenge:

  /** Base64url(no-pad) of `SHA-256(verifier)`, per RFC 7636 §4.2.
    *
    * Length range 43..128 per the spec; anything else is rejected.
    */
  def fromString(value: String): Either[String, PkceChallenge] =
    if value.length < 43 || value.length > 128 then
      Left(s"code_challenge length out of range: ${value.length}")
    else Right(value)

  extension (c: PkceChallenge) def value: String = c

opaque type PkceVerifier = String
object PkceVerifier:

  def fromString(value: String): Either[String, PkceVerifier] =
    if value.length < 43 || value.length > 128 then
      Left(s"code_verifier length out of range: ${value.length}")
    else Right(value)

  extension (v: PkceVerifier) def value: String = v

object Pkce:

  private val b64Url = Base64.getUrlEncoder.withoutPadding

  /** Pure S256 verification: `BASE64URL(SHA-256(verifier)) == challenge`. */
  def verify(
      verifier: PkceVerifier,
      challenge: PkceChallenge,
      method: PkceMethod
  ): Boolean =
    method match
      case PkceMethod.S256 =>
        val digest = MessageDigest.getInstance("SHA-256")
          .digest(verifier.value.getBytes(StandardCharsets.US_ASCII))
        val computed = b64Url.encodeToString(digest)
        constantTimeEquals(computed, challenge.value)

  private def constantTimeEquals(a: String, b: String): Boolean =
    if a.length != b.length then false
    else
      var result = 0
      var i = 0
      while i < a.length do
        result |= a.charAt(i) ^ b.charAt(i)
        i += 1
      result == 0
