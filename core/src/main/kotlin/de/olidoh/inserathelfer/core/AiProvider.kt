package de.olidoh.inserathelfer.core

/**
 * Ein KI-Anbieter, für den der Nutzer seinen eigenen API-Schlüssel hinterlegt.
 * Basis-URL und Modell sind Vorschläge und lassen sich in den Einstellungen ändern.
 */
enum class AiProvider(
    val label: String,
    val kind: Kind,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val hint: String,
) {
    CLAUDE(
        label = "Claude (Anthropic)",
        kind = Kind.CLAUDE,
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-opus-5-5",
        keyUrl = "https://console.anthropic.com/settings/keys",
        hint = "Schlüssel beginnt mit sk-ant-.",
    ),
    MISTRAL(
        label = "Mistral",
        kind = Kind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.mistral.ai/v1",
        defaultModel = "mistral-medium-2508",
        keyUrl = "https://console.mistral.ai/api-keys",
        hint = "Das Modell muss Bilder verstehen, z. B. mistral-medium-2508 oder mistral-small-2506.",
    ),
    LANGDOCK(
        label = "Langdock (GPT-Modelle)",
        kind = Kind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.langdock.com/openai/eu/v1",
        defaultModel = "gpt-5-mini",
        keyUrl = "https://app.langdock.com",
        hint = "Über diesen Zugang bietet Langdock OpenAI-Modelle an (z. B. gpt-5-mini), so wie sie euer " +
            "Workspace freigibt. Mistral-Modelle bietet Langdock derzeit nicht über die API an.",
    ),
    LANGDOCK_CLAUDE(
        label = "Langdock (Claude-Modelle)",
        kind = Kind.CLAUDE,
        defaultBaseUrl = "https://api.langdock.com/anthropic/eu",
        defaultModel = "claude-sonnet-4-6-default",
        keyUrl = "https://app.langdock.com",
        hint = "Claude-Modelle über euren Langdock-Workspace, z. B. claude-sonnet-4-6-default. " +
            "Derselbe Langdock-Schlüssel wie bei den GPT-Modellen.",
    ),
    OPENAI(
        label = "OpenAI",
        kind = Kind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o",
        keyUrl = "https://platform.openai.com/api-keys",
        hint = "Das Modell muss Bilder verstehen.",
    ),
    CUSTOM(
        label = "Anderer Anbieter (OpenAI-kompatibel)",
        kind = Kind.OPENAI_COMPATIBLE,
        defaultBaseUrl = "",
        defaultModel = "",
        keyUrl = "",
        hint = "Basis-URL bis einschließlich /v1 angeben. Der Anbieter braucht einen /chat/completions-Endpunkt mit Bildunterstützung.",
    ),
    ;

    enum class Kind { CLAUDE, OPENAI_COMPATIBLE }

    /** Nur beim eigenen Anbieter ist die Adresse frei einstellbar, sonst gilt die feste Vorgabe. */
    val hasEditableBaseUrl: Boolean get() = this == CUSTOM
}

/** Alles, was für eine Anfrage an einen Anbieter nötig ist. */
data class AiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val model: String = provider.defaultModel,
    val baseUrl: String = provider.defaultBaseUrl,
)
