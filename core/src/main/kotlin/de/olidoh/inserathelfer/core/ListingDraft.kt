package de.olidoh.inserathelfer.core

/** Preisart, wie sie Kleinanzeigen beim Aufgeben einer Anzeige anbietet. */
enum class PriceType(val label: String) {
    FIXED("Festpreis"),
    NEGOTIABLE("VB"),
    FREE("Zu verschenken"),
}

/** Zustand, wie ihn Kleinanzeigen in vielen Kategorien abfragt. */
val CONDITIONS = listOf("Neu", "Sehr gut", "Gut", "In Ordnung", "Defekt")

/** Ein von der KI erzeugter, vom Nutzer noch bearbeitbarer Anzeigenentwurf. */
data class ListingDraft(
    val title: String,
    val description: String,
    val priceEur: Int,
    val priceType: PriceType,
    val category: String,
    val condition: String,
    val priceReasoning: String,
) {
    /** Preis so formatiert, wie man ihn in Kleinanzeigen eintragen würde. */
    val priceLabel: String
        get() = when (priceType) {
            PriceType.FREE -> PriceType.FREE.label
            else -> "$priceEur € ${priceType.label}"
        }

    /** Kompletter Text zum Einfügen oder Teilen. */
    fun asShareText(): String = buildString {
        appendLine(title)
        appendLine()
        appendLine(description)
        appendLine()
        appendLine("Preis: $priceLabel")
        append("Kategorie: $category · Zustand: $condition")
    }

    companion object {
        /** Kleinanzeigen erlaubt höchstens 65 Zeichen im Titel. */
        const val MAX_TITLE_LENGTH = 65

        /** Kleinanzeigen erlaubt höchstens 4000 Zeichen in der Beschreibung. */
        const val MAX_DESCRIPTION_LENGTH = 4000
    }
}

/** Kürzt den Text an einer Wortgrenze auf höchstens [max] Zeichen. */
internal fun String.clampAtWord(max: Int): String {
    val trimmed = trim()
    if (trimmed.length <= max) return trimmed
    val cut = trimmed.substring(0, max)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace > max / 2) cut.substring(0, lastSpace) else cut).trimEnd(' ', ',', '-', '–')
}
