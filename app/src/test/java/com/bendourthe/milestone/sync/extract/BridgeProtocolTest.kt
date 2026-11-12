package com.bendourthe.milestone.sync.extract

import com.bendourthe.milestone.domain.FailureReason
import com.bendourthe.milestone.domain.SelectorStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class BridgeProtocolTest {
    private val nonce = "n-123"

    private fun event(json: String) = (BridgeProtocol.parse(json, nonce) as? BridgeMessage.Event)?.event

    @Test
    fun parsesEachMessageType() {
        assertEquals(BridgeMessage.Ready, BridgeProtocol.parse("""{"type":"ready"}""", null))
        assertEquals(BridgeEvent.LoginRequired, event("""{"type":"login","nonce":"n-123"}"""))
        assertEquals(BridgeMessage.Timeout(awaitingInput = false), BridgeProtocol.parse("""{"type":"timeout","nonce":"n-123"}""", nonce))
        assertEquals(BridgeMessage.Timeout(awaitingInput = true), BridgeProtocol.parse("""{"type":"timeout","awaitingInput":true,"nonce":"n-123"}""", nonce))
        assertEquals(BridgeMessage.Timeout(awaitingInput = false), BridgeProtocol.parse("""{"type":"timeout","awaitingInput":"yes","nonce":"n-123"}""", nonce))
        assertEquals(BridgeEvent.Extracted(82_410), event("""{"type":"balance","raw":"82,410 pts","matchCount":1,"nonce":"n-123"}"""))
        assertEquals(BridgeEvent.Failed(FailureReason.Unparseable), event("""{"type":"balance","raw":"1,234.56","matchCount":1,"nonce":"n-123"}"""))
        assertEquals(BridgeEvent.Failed(FailureReason.SelectorAmbiguous), event("""{"type":"balance","raw":"82,410","matchCount":2,"nonce":"n-123"}"""))
    }

    @Test
    fun rejectsWrongNonceAndMalformedMessages() {
        val rejected = listOf(
            null,
            "",
            "not json",
            "[]",
            """{"type":"login"}""",
            """{"type":"login","nonce":"old"}""",
            """{"type":"login","nonce":123}""",
            """{"type":"balance","raw":"1","nonce":"n-123"}""",
            """{"type":"balance","raw":"1","matchCount":0,"nonce":"n-123"}""",
            """{"type":"balance","raw":"${"1".repeat(201)}","matchCount":1,"nonce":"n-123"}""",
            """{"type":"ready","extra":1}""",
            """{"type":"cookies","nonce":"n-123"}""",
        )
        for (message in rejected) assertEquals(message, null, BridgeProtocol.parse(message, nonce))
        assertEquals(null, BridgeProtocol.parse("""{"type":"login","nonce":"n-123"}""", null))
    }

    @Test
    fun configCarriesSelectorAndNonce() {
        val config = Json.parseToJsonElement(BridgeProtocol.config(SelectorStrategy.CSS, "span[data-x=\"y\"]", nonce)).jsonObject
        assertEquals("CSS", config["strategy"]!!.jsonPrimitive.content)
        assertEquals("span[data-x=\"y\"]", config["expression"]!!.jsonPrimitive.content)
        assertEquals("20000", config["timeoutMs"]!!.jsonPrimitive.content)
        assertEquals(nonce, config["nonce"]!!.jsonPrimitive.content)
    }

    @Test
    fun extractionScriptNeverTouchesCredentialsOrStorage() {
        val script = File("src/main/assets/extract.js").readText()
        val forbidden = listOf("document.cookie", "localStorage", "sessionStorage", "indexedDB", ".value", "FormData", "fetch(", "XMLHttpRequest", "innerHTML", "outerHTML")
        for (token in forbidden) assertFalse(token, script.contains(token))
    }
}
