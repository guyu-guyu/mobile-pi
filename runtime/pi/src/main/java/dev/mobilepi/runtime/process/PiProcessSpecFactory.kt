package dev.mobilepi.runtime.process

data class PiAgentConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
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
        return RawProcessSpec(
            command = listOf(
                "/usr/bin/pi",
                "--mode",
                "rpc",
                "--provider",
                provider,
                "--model",
                model,
                "--no-session",
                "--no-extensions",
                "--no-skills",
                "--no-prompt-templates",
                "--no-themes",
                "--no-context-files",
            ),
            environment = mapOf(keyVariable to apiKey),
            workingDirectory = paths.workspace.absolutePath,
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
