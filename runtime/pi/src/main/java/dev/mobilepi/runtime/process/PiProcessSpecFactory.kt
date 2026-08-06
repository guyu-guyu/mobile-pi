package dev.mobilepi.runtime.process

import java.io.File
import java.util.UUID

data class PiAgentConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val workspaceDirectory: String,
    val sessionDirectory: String,
    val resumeExistingSession: Boolean,
)

object PiProcessSpecFactory {
    fun create(config: PiAgentConfig, paths: RuntimePaths): RawProcessSpec {
        val provider = config.provider.trim()
        val model = config.model.trim()
        val apiKey = config.apiKey.trim()
        require(provider.matches(SAFE_VALUE)) { "Provider contains unsupported characters" }
        require(model.matches(SAFE_VALUE)) { "Model contains unsupported characters" }
        require(apiKey.isNotEmpty()) { "API key is required" }

        val keyVariable = providerApiKeyVariable(provider)
            ?: throw IllegalArgumentException("Unsupported API-key provider: $provider")
        val workspaceDirectory = File(config.workspaceDirectory).canonicalFile
        val sessionDirectory = File(config.sessionDirectory).canonicalFile
        val workspacesRoot = File(paths.files, "workspaces").canonicalFile
        val workspaceId = workspaceDirectory.parentFile?.name
        require(
            workspaceDirectory.name == "files" &&
                workspaceDirectory.parentFile?.parentFile == workspacesRoot &&
                workspaceId != null &&
                runCatching { UUID.fromString(workspaceId).toString() == workspaceId }.getOrDefault(false),
        ) {
            "Agent working directory must be a canonical managed workspace"
        }
        require(
            sessionDirectory.parentFile == paths.sessions.canonicalFile &&
                sessionDirectory.name == workspaceId,
        ) {
            "Agent Session directory must match the managed workspace"
        }
        val command = buildList {
            addAll(listOf(
                "/usr/bin/pi",
                "--mode",
                "rpc",
                "--provider",
                provider,
                "--model",
                model,
                "--session-dir",
                TerminalCoreRawProcessLauncher.GUEST_SESSIONS,
                "--no-extensions",
                "--no-skills",
                "--no-prompt-templates",
                "--no-themes",
                "--no-context-files",
            ))
            if (config.resumeExistingSession) add("--continue")
        }
        return RawProcessSpec(
            command = command,
            environment = mapOf(keyVariable to apiKey),
            workingDirectory = workspaceDirectory.absolutePath,
            sessionDirectory = sessionDirectory.absolutePath,
        )
    }

    fun providerApiKeyVariable(provider: String): String? = when (provider.trim().lowercase()) {
        "anthropic" -> "ANTHROPIC_API_KEY"
        "openai" -> "OPENAI_API_KEY"
        "deepseek" -> "DEEPSEEK_API_KEY"
        "google" -> "GEMINI_API_KEY"
        "groq" -> "GROQ_API_KEY"
        "cerebras" -> "CEREBRAS_API_KEY"
        "xai" -> "XAI_API_KEY"
        "openrouter" -> "OPENROUTER_API_KEY"
        "mistral" -> "MISTRAL_API_KEY"
        "nvidia" -> "NVIDIA_API_KEY"
        "zai" -> "ZAI_API_KEY"
        "fireworks" -> "FIREWORKS_API_KEY"
        "together" -> "TOGETHER_API_KEY"
        "huggingface" -> "HF_TOKEN"
        else -> null
    }

    private val SAFE_VALUE = Regex("[A-Za-z0-9._:/@+-]+")
}
