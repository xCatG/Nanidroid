package com.cattailsw.nanidroid.llmghost

import java.net.URI
import java.nio.file.Path

data class SpikeCliConfig(
    val nar: Path,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val candidates: Int = 1,
    val seed: Long = 0,
    val stream: Boolean = true,
    val connectTimeoutMillis: Long = 10_000,
    val requestTimeoutMillis: Long = 180_000,
    val reportRoot: Path = Path.of("build", "reports", "llm-ghost-spike"),
    val apiKey: String? = null,
)

sealed interface CliParseResult {
    data class Success(val value: SpikeCliConfig) : CliParseResult
    data class Help(val text: String) : CliParseResult
    data class Error(val code: String, val message: String) : CliParseResult
}

object SpikeCliArguments {
    fun parse(args: List<String>, environment: Map<String, String>): CliParseResult {
        if (args.any { it == "--help" || it == "-h" }) return CliParseResult.Help(HELP)
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val flag = args[index]
            if (flag !in VALUE_FLAGS) return error("unknown-option", "Unknown command-line option.")
            if (flag in values) return error("duplicate-option", "A command-line option was provided more than once.")
            if (index + 1 >= args.size || args[index + 1].startsWith("--")) {
                return error("missing-option-value", "A command-line option is missing its value.")
            }
            values[flag] = args[index + 1]
            index += 2
        }

        val nar = values["--nar"]?.takeIf { it.isNotBlank() }
            ?: return error("nar-required", "A NAR path is required.")
        val baseUrl = values["--base-url"] ?: DEFAULT_BASE_URL
        if (!isSafeAbsoluteHttpUrl(baseUrl)) {
            return error("invalid-base-url", "The base URL must be an absolute HTTP(S) URL without credentials, query, or fragment.")
        }
        val model = (values["--model"] ?: DEFAULT_MODEL).takeIf { it.isNotBlank() }
            ?: return error("invalid-model", "The model identifier must not be blank.")
        val candidates = positiveInt(values["--candidates"] ?: "1")
            ?: return error("invalid-candidates", "Candidates must be a positive integer.")
        val seed = values["--seed"]?.toLongOrNull() ?: if ("--seed" in values) {
            return error("invalid-seed", "Seed must be a signed integer.")
        } else {
            0L
        }
        val stream = when (values["--stream"] ?: "true") {
            "true" -> true
            "false" -> false
            else -> return error("invalid-stream", "Stream must be true or false.")
        }
        val connectTimeout = positiveLong(values["--connect-timeout-ms"] ?: "10000")
            ?: return error("invalid-timeout", "Connect timeout must be a finite positive integer.")
        val requestTimeout = positiveLong(values["--request-timeout-ms"] ?: "180000")
            ?: return error("invalid-timeout", "Request timeout must be a finite positive integer.")
        val reportRoot = values["--report-root"]?.takeIf { it.isNotBlank() }?.let(::pathOrNull)
            ?: if ("--report-root" in values) {
                if (!values.getValue("--report-root").isBlank()) {
                    return error("invalid-path", "A command-line path is invalid.")
                }
                return error("invalid-report-root", "The report root must not be blank.")
            } else {
                Path.of("build", "reports", "llm-ghost-spike")
            }
        val apiKey = values["--api-key-env"]?.let { environmentName ->
            if (!ENVIRONMENT_NAME.matches(environmentName)) {
                return error("invalid-api-key-env", "The bearer environment variable name is invalid.")
            }
            environment[environmentName]?.takeIf { it.isNotBlank() }
                ?: return error("api-key-unavailable", "The requested bearer credential is unavailable.")
        }
        return CliParseResult.Success(
            SpikeCliConfig(
                nar = pathOrNull(nar) ?: return error("invalid-path", "A command-line path is invalid."),
                baseUrl = baseUrl.trimEnd('/'),
                model = model,
                candidates = candidates,
                seed = seed,
                stream = stream,
                connectTimeoutMillis = connectTimeout,
                requestTimeoutMillis = requestTimeout,
                reportRoot = reportRoot,
                apiKey = apiKey,
            ),
        )
    }

    private fun isSafeAbsoluteHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.isAbsolute &&
            uri.scheme.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            hasValidPort(uri)
    }.getOrDefault(false)

    private fun hasValidPort(uri: URI): Boolean {
        val authority = uri.rawAuthority ?: return false
        val portText = if (authority.startsWith('[')) {
            val bracket = authority.indexOf(']')
            if (bracket < 0) return false
            val remainder = authority.substring(bracket + 1)
            if (remainder.isEmpty()) return true
            if (!remainder.startsWith(':')) return false
            remainder.substring(1)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon < 0) return true
            authority.substring(colon + 1)
        }
        return portText.isNotEmpty() && portText.all(Char::isDigit) &&
            portText.toIntOrNull()?.let { it in 0..65_535 } == true
    }

    private fun positiveInt(value: String): Int? = value.toIntOrNull()?.takeIf { it > 0 }
    private fun positiveLong(value: String): Long? = value.toLongOrNull()?.takeIf { it > 0 }
    private fun pathOrNull(value: String): Path? = runCatching { Path.of(value) }.getOrNull()
    private fun error(code: String, message: String) = CliParseResult.Error(code, message)

    private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val VALUE_FLAGS = setOf(
        "--nar",
        "--base-url",
        "--model",
        "--candidates",
        "--seed",
        "--stream",
        "--connect-timeout-ms",
        "--request-timeout-ms",
        "--report-root",
        "--api-key-env",
    )
}

const val DEFAULT_BASE_URL = "http://gx10-5e5d:10101/v1"
const val DEFAULT_MODEL = "nemotron-3-super"

private val HELP = """
Usage: llm-ghost-spike --nar <path> [options]

Options:
  --base-url <http(s)://host/path>  OpenAI-compatible API base URL
  --model <id>                      Model identifier
  --candidates <count>              Candidates per scenario/language (default: 1)
  --seed <integer>                  Deterministic base seed (default: 0)
  --stream <true|false>             Request streaming responses (default: true)
  --connect-timeout-ms <ms>         Positive connect timeout (default: 10000)
  --request-timeout-ms <ms>         Positive request timeout (default: 180000)
  --report-root <path>              Generated report root
  --api-key-env <name>              Read an optional bearer token from this environment variable
  --help                            Show this help
""".trimIndent()
