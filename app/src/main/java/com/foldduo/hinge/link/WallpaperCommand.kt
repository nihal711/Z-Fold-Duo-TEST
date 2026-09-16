package com.foldduo.hinge.link

import java.lang.reflect.Modifier

/**
 * Samsung's `IWallpaperManager.semSendWallpaperCommand(int which, String action)`
 * is what makes FoldInteractive log its hinge angle. `service call` addresses
 * Binder methods by transaction number, and Samsung inserts methods between
 * One UI releases: on One UI 8 the method sits at 90, on the Fold8 Ultra's
 * One UI 9 build it is 92 and 90 has become `getWallpaperBackgroundRegion`.
 * The number is therefore read from the generated Stub of the running build.
 */
object WallpaperCommand {
    const val DEFAULT_TRANSACTION = 90
    const val METHOD_NAME = "semSendWallpaperCommand"
    const val WHICH = 5
    private const val STUB_CLASS = "android.app.IWallpaperManager\$Stub"
    private const val PREFIX = "TRANSACTION_"

    data class Resolution(val transaction: Int, val methodName: String, val source: String) {
        override fun toString(): String = "$transaction ($methodName, $source)"
    }

    fun resolve(): Resolution = runCatching {
        val fields = transactionTable()
        fields[METHOD_NAME]?.let { return Resolution(it, METHOD_NAME, "stub") }
        fields.entries.firstOrNull { it.key.endsWith("SendWallpaperCommand", ignoreCase = true) }
            ?.let { return Resolution(it.value, it.key, "stub-by-suffix") }
        Resolution(DEFAULT_TRANSACTION, "unknown", "default: method not in stub")
    }.getOrElse { error ->
        Resolution(DEFAULT_TRANSACTION, "unknown", "default: ${error.javaClass.simpleName}")
    }

    /** method name -> transaction code, read via reflection from the framework Stub. */
    fun transactionTable(): Map<String, Int> {
        val stub = Class.forName(STUB_CLASS)
        return stub.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith(PREFIX) && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                runCatching {
                    field.isAccessible = true
                    field.name.removePrefix(PREFIX) to field.getInt(null)
                }.getOrNull()
            }
            .toMap()
    }

    fun single(transaction: Int, action: String): String =
        "service call wallpaper $transaction i32 $WHICH s16 $action"

    fun probeLoop(transaction: Int): String =
        "while :; do ${single(transaction, PrivateAngleStream.ANGLE_ACTION)} >/dev/null; sleep 0.025; done"

    fun wake(transaction: Int): String = "${single(transaction, PrivateAngleStream.WAKE_ACTION)} >/dev/null"

    /**
     * A correctly addressed `semSendWallpaperCommand` returns an empty parcel,
     * printed as `Result: Parcel(00000000    '....')`. Wrong methods answer
     * with data the tool cannot consume and exceptions start with an error
     * word, so the reply is judged by its parcel words rather than by text:
     * `service call` prints strings with a dot after every character.
     */
    fun isCleanReply(serviceCallOutput: String): Boolean {
        val text = serviceCallOutput.trim()
        val marker = "Result: Parcel("
        val start = text.indexOf(marker)
        if (start < 0) return false
        val body = text.substring(start + marker.length).substringBeforeLast(')')
        val cleaned = body
            .replace(OFFSET, " ")
            .replace(ASCII_COLUMN, " ")
        val words = WORD.findAll(cleaned).map { it.value.lowercase() }.toList()
        return words == listOf("00000000")
    }

    private val OFFSET = Regex("0x[0-9a-fA-F]{8}:")
    private val ASCII_COLUMN = Regex("'[^']*'")
    private val WORD = Regex("\\b[0-9a-fA-F]{8}\\b")
}
