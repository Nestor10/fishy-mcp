package fishy.mcp.bootstrap.config

import zio.*

/** Deployment profile -- selects strictness for the startup audit.
  *
  *   - `MCP_PROFILE=dev` (or unset) -- warnings only; in-memory backends and
  *     allow-all auth are permitted with logged warnings.
  *   - `MCP_PROFILE=production` -- strict; refuses to boot with dev-only
  *     defaults (InMemory backend, allow-all auth).
  */
enum DeploymentProfile:
  case Dev
  case Production

final case class DeploymentConfig(profile: DeploymentProfile)

object DeploymentConfig:

  val config: Config[DeploymentConfig] =
    Config.string("MCP_PROFILE").withDefault("dev").mapOrFail { raw =>
      raw.trim.toLowerCase match
        case "" | "dev" | "development" => Right(DeploymentConfig(DeploymentProfile.Dev))
        case "prod" | "production"      => Right(DeploymentConfig(DeploymentProfile.Production))
        case other =>
          Left(Config.Error.InvalidData(
            Chunk("MCP_PROFILE"),
            s"Unknown profile: '$other'. Expected 'dev' or 'production'."
          ))
    }
