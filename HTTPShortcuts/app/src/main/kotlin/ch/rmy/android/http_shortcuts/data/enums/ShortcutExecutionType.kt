package ch.rmy.android.http_shortcuts.data.enums

enum class ShortcutExecutionType(
    val type: String,
    val usesUrl: Boolean = false,
    val requiresHttpUrl: Boolean = false,
    val usesRequestOptions: Boolean = false,
    val usesResponse: Boolean = false,
    val usesScriptingEditor: Boolean = true,
) {

    APP(type = "app", usesUrl = true, requiresHttpUrl = true, usesRequestOptions = true, usesResponse = true),
    BROWSER(type = "browser", usesUrl = true, requiresHttpUrl = false),
    SCRIPTING(type = "scripting"),
    TRIGGER(type = "trigger", usesScriptingEditor = false),
    MATRIX(type = "matrix", usesUrl = true, usesResponse = false, usesRequestOptions = true, usesScriptingEditor = false);

    companion object {

        fun get(type: String) =
            values().first { it.type == type }
    }
}
