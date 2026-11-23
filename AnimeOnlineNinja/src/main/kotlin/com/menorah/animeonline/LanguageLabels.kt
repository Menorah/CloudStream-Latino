package com.menorah.animeonline

internal fun sourceLabel(language: String?, name: String): String =
    if (language.isNullOrBlank()) name else "[$language] $name"

/** Only label a group when the site supplies a recognisable language. */
internal fun languageLabel(value: String): String? {
    val code = value.trim().lowercase()
    return when {
        code.isBlank() -> null
        code == "lat" || code.contains("latin") -> "Latino"
        code == "es" || code.contains("castellano") || code.contains("españa") -> "Castellano"
        code == "sub" || code.contains("subtit") -> "Audio original · Sub español"
        code == "jap" || code == "jp" || code.contains("japon") || code.contains("japón") -> "Japonés"
        else -> null
    }
}
