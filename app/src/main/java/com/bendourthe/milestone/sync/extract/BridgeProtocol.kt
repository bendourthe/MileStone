package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.BalanceUnit
import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SelectorStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface BridgeEvent {
    data class Extracted(val balance: Long) : BridgeEvent
    data object LoginRequired : BridgeEvent
    data class Failed(val reason: FailureReason) : BridgeEvent

    /** This device's WebView lacks the message listener or document-start script feature. */
    data object Unsupported : BridgeEvent

    /** MileStone's own sign-in was refused or could not finish; [why] decides whether it may try again. */
    data class AutoSignInStopped(val why: SignInStop) : BridgeEvent
}

enum class SignInStop {
    /** The sign-in form came back showing the site's error after the password was submitted. */
    REFUSED,

    /** A texted code was needed and none could be entered. */
    NO_CODE,

    /** The form could not be filled, so no password was sent. */
    NOT_FILLED,

    /** The sign-in ended short of the account: on another page, or back on the form with no error shown. */
    UNFINISHED,
}

/** Messages posted by `assets/extract.js`. */
sealed interface BridgeMessage {
    /** A new document or single-page navigation; the app answers with [BridgeProtocol.config]. */
    data object Ready : BridgeMessage
    data class Event(val event: BridgeEvent) : BridgeMessage

    /** No balance appeared in time; [awaitingInput] is true when the page shows a field to type into. */
    data class Timeout(val awaitingInput: Boolean) : BridgeMessage

    /** The page asks for a one-time code and shows no password field. */
    data object CodeRequested : BridgeMessage

    /** The script filled and submitted a form for a fill or code command; [submitted] is false when it found no form. */
    data class Filled(val submitted: Boolean, val fields: String? = null) : BridgeMessage

    /** The submitted form stayed on screen without an error, so the script pressed its button once more. */
    data object PressedAgain : BridgeMessage

    /** After MileStone's submit, the sign-in form came back showing the site's error. */
    data object Refused : BridgeMessage
}

object BridgeProtocol {
    const val JS_OBJECT_NAME = "MileStoneBridge"
    const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val MAX_TEXT = 200

    /** Field names only, from a fixed list, so a filled report can never carry a typed value. */
    private val FILLED_FIELDS = Regex("^(username|lastName|password)(,(username|lastName|password)){0,2}$")

    /**
     * [watchCodes] asks the script to report code fields; [afterSignIn] lets it pick a texted code and confirm
     * "remember this device" once MileStone has submitted the sign-in itself; [openSignIn] lets it press a "Sign in"
     * control on a page that shows no sign-in form, such as a site whose form opens from its header.
     */
    fun config(
        strategy: SelectorStrategy,
        expression: String,
        nonce: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        watchCodes: Boolean = false,
        afterSignIn: Boolean = false,
        openSignIn: Boolean = false,
        reveal: String? = null,
    ): String =
        buildJsonObject {
            if (reveal != null) put("reveal", reveal)
            put("strategy", strategy.name)
            put("expression", expression)
            put("timeoutMs", timeoutMs)
            put("nonce", nonce)
            put("watchCodes", watchCodes)
            put("afterSignIn", afterSignIn)
            put("openSignIn", openSignIn)
        }.toString()

    /** Sent only to a page on the program's own hosts, in reply to its sign-in report. */
    fun fillCommand(nonce: String, username: String, password: String, lastName: String = ""): String = buildJsonObject {
        put("command", "fill")
        put("nonce", nonce)
        put("username", username)
        put("password", password)
        if (lastName.isNotEmpty()) put("lastName", lastName)
    }.toString()

    fun codeCommand(nonce: String, code: String): String = buildJsonObject {
        put("command", "code")
        put("nonce", nonce)
        put("code", code)
    }.toString()

    /** Returns null for anything malformed or carrying a nonce other than [expectedNonce]. */
    fun parse(data: String?, expectedNonce: String?, unit: BalanceUnit = BalanceUnit.POINTS): BridgeMessage? {
        val json = runCatching { Json.parseToJsonElement(data ?: return null) as? JsonObject }.getOrNull() ?: return null
        val type = json.string("type") ?: return null
        if (type == "ready") return if (json.size == 1) BridgeMessage.Ready else null
        if (expectedNonce == null || json.string("nonce") != expectedNonce) return null
        return when (type) {
            "login" -> BridgeMessage.Event(BridgeEvent.LoginRequired)
            "refused" -> BridgeMessage.Refused
            "timeout" -> BridgeMessage.Timeout((json["awaitingInput"] as? JsonPrimitive)?.booleanOrNull == true)
            "code" -> BridgeMessage.CodeRequested
            "pressedAgain" -> BridgeMessage.PressedAgain
            "filled" -> BridgeMessage.Filled(
                (json["submitted"] as? JsonPrimitive)?.booleanOrNull == true,
                json.string("fields")?.takeIf { FILLED_FIELDS.matches(it) },
            )
            "balance" -> {
                val raw = json.string("raw")?.takeIf { it.length <= MAX_TEXT } ?: return null
                val matchCount = (json["matchCount"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 } ?: return null
                val event = when {
                    matchCount > 1 -> BridgeEvent.Failed(FailureReason.SelectorAmbiguous)
                    else -> BalanceParser.parse(raw, unit)?.let { BridgeEvent.Extracted(it) } ?: BridgeEvent.Failed(FailureReason.Unparseable)
                }
                BridgeMessage.Event(event)
            }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.jsonPrimitive?.content
}
