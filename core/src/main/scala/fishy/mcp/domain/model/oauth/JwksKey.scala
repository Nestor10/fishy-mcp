package fishy.mcp.domain.model.oauth

import fishy.mcp.domain.model.oauth.Ids.JwkKid

/** Library-neutral public JWK descriptor.
  *
  * Used to serialize the JWKS document. Only the fields clients need for
  * signature verification are modeled -- adapters hold the actual key material.
  */
final case class JwksKey(
    kid: JwkKid,
    kty: String, // "RSA", "EC", "OKP"
    use: String, // "sig"
    alg: String, // "RS256", "ES256", "EdDSA"
    // RSA
    n: Option[String] = None,
    e: Option[String] = None,
    // EC / OKP
    crv: Option[String] = None,
    x: Option[String] = None,
    y: Option[String] = None
)
