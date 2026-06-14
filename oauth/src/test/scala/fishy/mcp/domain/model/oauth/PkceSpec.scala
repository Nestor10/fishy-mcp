package fishy.mcp.domain.model.oauth

import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

object PkceSpec extends ZIOSpecDefault:

  private val b64Url = Base64.getUrlEncoder.withoutPadding

  /** RFC 7636 example: verifier+challenge pair known to match. */
  private val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
  private val rfcChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

  /** Compute challenge for a given verifier (helper to generate fresh pairs). */
  private def challengeFor(verifier: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(verifier.getBytes(StandardCharsets.US_ASCII))
    b64Url.encodeToString(digest)

  override def spec = suite("Pkce")(
    test("S256 verification accepts the RFC 7636 example pair") {
      val v = PkceVerifier.fromString(rfcVerifier).toOption.get
      val c = PkceChallenge.fromString(rfcChallenge).toOption.get
      assertTrue(Pkce.verify(v, c, PkceMethod.S256))
    },
    test("S256 verification rejects a mismatched verifier") {
      val v = PkceVerifier.fromString("a" * 64).toOption.get
      val c = PkceChallenge.fromString(rfcChallenge).toOption.get
      assertTrue(!Pkce.verify(v, c, PkceMethod.S256))
    },
    test("verifier shorter than 43 chars is rejected at parse time") {
      assert(PkceVerifier.fromString("a" * 42))(isLeft(containsString("out of range")))
    },
    test("verifier longer than 128 chars is rejected at parse time") {
      assert(PkceVerifier.fromString("a" * 129))(isLeft(containsString("out of range")))
    },
    test("challenge length bounds enforced") {
      assert(PkceChallenge.fromString("a" * 42))(isLeft(anything)) &&
      assert(PkceChallenge.fromString("a" * 129))(isLeft(anything))
    },
    test("round-trip: computed challenge verifies against its verifier") {
      val verifier = "Q" * 64
      val v = PkceVerifier.fromString(verifier).toOption.get
      val c = PkceChallenge.fromString(challengeFor(verifier)).toOption.get
      assertTrue(Pkce.verify(v, c, PkceMethod.S256))
    }
  )
