package com.pocketcode

/** Готовые настройки популярных провайдеров: пользователю остаётся вставить ключ. */
data class ProviderTemplate(
    val name: String,
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val context: Int,
    val maxOutput: Int = 0,
    val note: String,
    val keyPlaceholder: String = "",
)

val PROVIDER_TEMPLATES = listOf(
    ProviderTemplate("Claude", Provider.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-5-5", 200_000, 16_384, "Ключ с console.anthropic.com. Самые сильные агентные модели, платно."),
    ProviderTemplate("OpenAI", Provider.OPENAI, "https://api.openai.com/v1", "gpt-4.1", 200_000, 0, "Ключ с platform.openai.com. Подписка ChatGPT к API не относится."),
    ProviderTemplate("DeepSeek", Provider.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", 64_000, 8_000, "Ключ с platform.deepseek.com. Очень дёшево, кэш префикса автоматический."),
    ProviderTemplate("OpenRouter", Provider.OPENAI, "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5", 200_000, 0, "Один ключ на сотни моделей, есть бесплатные (имена с :free)."),
    ProviderTemplate("Gemini", Provider.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", 200_000, 0, "Бесплатный ключ в Google AI Studio (есть дневной лимит)."),
    ProviderTemplate("Groq", Provider.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", 32_000, 8_000, "Быстро и есть бесплатный уровень, но маленькие лимиты по токенам."),
    ProviderTemplate("Mistral", Provider.OPENAI, "https://api.mistral.ai/v1", "mistral-large-latest", 128_000, 0, "Ключ с console.mistral.ai."),
    ProviderTemplate("Локальный (Ollama)", Provider.OPENAI, "http://192.168.1.10:11434/v1", "qwen2.5-coder:14b", 32_000, 4_000, "Модель на твоём ПК в той же Wi-Fi сети. Замени адрес на адрес ПК. Ключ любой.", "ollama"),
)
