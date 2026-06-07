package com.cattailsw.nanidroid.test.support

import com.cattailsw.nanidroid.shiori.Shiori

class FakeShiori(
    private val responses: Map<String, String> = emptyMap(),
    private val defaultResponse: String = ""
) : Shiori {
    
    data class Call(val request: String, val thread: Thread)
    
    val calls = mutableListOf<Call>()

    override fun getModuleName(): String = "FakeShiori"

    override fun request(req: String): String {
        calls.add(Call(req, Thread.currentThread()))

        val idLine = req.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ID:") }
        val eventId = idLine?.substringAfter("ID:")?.trim() ?: ""

        val script = responses[eventId] ?: defaultResponse
        return "SHIORI/3.0 200 OK\r\nSender: FakeShiori\r\nValue: $script\r\nCharset: UTF-8\r\n\r\n"
    }

    override fun terminate() {}
    override fun unloadShiori() {}
}
