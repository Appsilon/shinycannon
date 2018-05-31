package com.rstudio.shinycannon

import com.google.gson.JsonParser
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketAdapter
import com.neovisionaries.ws.client.WebSocketFactory
import com.neovisionaries.ws.client.WebSocketState
import net.moznion.uribuildertiny.URIBuilderTiny
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.time.Instant

fun canIgnore(message: String):Boolean {
    // Don't ignore messages matching these exact strings. They're "special".
    val allow = setOf("o")
    if (allow.contains(message)) return false

    // Messages matching these regexes should be ignored.
    val ignorableRegexes = listOf("""^a\["ACK.*$""", """^\["ACK.*$""", """^h$""")
            .map(::Regex)
    for (re in ignorableRegexes) if (re.matches(message)) return true

    val messageObject = parseMessage(message)

    if (messageObject == null)
        throw IllegalStateException("Expected to be able to parse message: $message")

    // If the message contains any of ignorableKeys as a key, then we should ignore it.
    val ignorableKeys = setOf("busy", "progress", "recalculating")
    for (key in ignorableKeys) {
        if (messageObject.keySet().contains(key)) return true
    }

    // If the message has only one key, "custom", and if that key also points to
    // an object with one key, "reactlog", then ignore.
    if (messageObject.takeIf { it.keySet() == setOf("custom") }
            ?.get("custom")
            ?.asJsonObject
            ?.keySet() == setOf("reactlog")) {
        return true
    }

    val emptyMessage = JsonParser()
            .parse("""{"errors":[],"values":[],"inputMessages":[]}""")
            .asJsonObject
    if (messageObject == emptyMessage) return true

    return false
}

sealed class Event(open val created: Long, open val lineNumber: Int) {
    open fun sleepBefore(session: ShinySession): Long = 0
    // Returning true means the session is still valid, continue. False means handling has failed, stop.
    abstract fun handle(session: ShinySession, out: PrintWriter): Boolean
    fun name() = this::class.java.typeName.split("$").last()

    fun tryLog(session: ShinySession, out: PrintWriter, body: () -> Unit): Boolean {
        out.printCsv(session.sessionId, "${name()}_START", nowMs(), lineNumber)
        try {
            body()
            out.printCsv(session.sessionId, "${name()}_END", nowMs(), lineNumber)
        } catch (t: Throwable) {
            session.log.warn(t) { "${name()} failed (line: $lineNumber)" }
            return false
        }
        return true
    }

    companion object {
        fun fromLine(lineNumber: Int, line: String): Event {
            val obj = JsonParser().parse(line).asJsonObject
            val created = Instant.parse(obj.get("created").asString).toEpochMilli()
            val type = obj.get("type").asString
            return when (type) {
                "REQ" -> Http.REQ(created,
                        lineNumber,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "REQ_HOME" -> Http.REQ_HOME(created,
                        lineNumber,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "REQ_SINF" -> Http.REQ_SINF(created,
                        lineNumber,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "REQ_TOK" -> Http.REQ_TOK(created,
                        lineNumber,
                        obj.get("url").asString,
                        obj.get("method").asString,
                        obj.get("statusCode").asInt)
                "WS_OPEN" -> WS_OPEN(created, lineNumber, obj.get("url").asString)
                "WS_RECV" -> WS_RECV(created, lineNumber, obj.get("message").asString)
                "WS_RECV_INIT" -> WS_RECV_INIT(created, lineNumber, obj.get("message").asString)
                "WS_SEND" -> WS_SEND(created, lineNumber, obj.get("message").asString)
                "WS_CLOSE" -> WS_CLOSE(created, lineNumber)
                else -> throw Exception("Unknown event type: $type")
            }
        }
    }

    sealed class Http(override val created: Long,
                      override val lineNumber: Int,
                      open val url: String,
                      open val method: String,
                      open val statusCode: Int) : Event(created, lineNumber) {

        fun get(session: ShinySession): String {
            val renderedUrl = session.replaceTokens(this.url)
            val url = URIBuilderTiny(session.httpUrl)
                    .appendRawPathsByString(renderedUrl)
                    .build()
                    .toString()

            val cfg = RequestConfig.custom()
                    .setCookieSpec(CookieSpecs.STANDARD)
                    .build()
            val client = HttpClientBuilder
                    .create()
                    .setDefaultCookieStore(session.cookieStore)
                    .setDefaultRequestConfig(cfg)
                    .build()
            val get = HttpGet(url)
            client.execute(get).use { response ->
                val baos = ByteArrayOutputStream()
                response.entity.content.copyTo(baos)
                val body = String(baos.toByteArray())
                val gotStatus = response.statusLine.statusCode
                if (response.statusLine.statusCode != statusCode)
                    error("Status $gotStatus received, expected $statusCode, Response body: $body, URL: $url")
                return body
            }
        }

        class REQ(override val created: Long,
                  override val lineNumber: Int,
                  override val url: String,
                  override val method: String,
                  override val statusCode: Int) : Http(created, lineNumber, url, method, statusCode) {
            override fun sleepBefore(session: ShinySession) =
                    if (session.webSocket == null) 0 else (created - (session.lastEventCreated ?: created))

            override fun handle(session: ShinySession, out: PrintWriter): Boolean {
                return tryLog(session, out) { get(session) }
            }
        }

        class REQ_HOME(override val created: Long,
                       override val lineNumber: Int,
                       override val url: String,
                       override val method: String,
                       override val statusCode: Int) : Http(created, lineNumber, url, method, statusCode) {
            override fun handle(session: ShinySession, out: PrintWriter): Boolean {
                return tryLog(session, out) {
                    val response = get(session)
                    val re = """.*<base href="_w_([0-9a-z]+)/.*"""
                            .toRegex(options = setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
                    val match = re.matchEntire(response)
                    val workerId = match?.groupValues?.getOrNull(1)
                    workerId?.let {
                        // Note: If workerId is null, we're probably running against dev server or SSO
                        session.tokenDictionary["WORKER"] = it
                    }
                }
            }
        }

        class REQ_SINF(override val created: Long,
                       override val lineNumber: Int,
                       override val url: String,
                       override val method: String,
                       override val statusCode: Int) : Http(created, lineNumber, url, method, statusCode) {
            override fun handle(session: ShinySession, out: PrintWriter): Boolean {
                return tryLog(session, out) { get(session) }
            }
        }

        class REQ_TOK(override val created: Long,
                      override val lineNumber: Int,
                      override val url: String,
                      override val method: String,
                      override val statusCode: Int) : Http(created, lineNumber, url, method, statusCode) {
            override fun handle(session: ShinySession, out: PrintWriter): Boolean {
                return tryLog(session, out) {
                    session.tokenDictionary["TOKEN"] = get(session)
                }
            }
        }
    }

    class WS_OPEN(override val created: Long,
                  override val lineNumber: Int,
                  val url: String) : Event(created, lineNumber) {
        override fun handle(session: ShinySession, out: PrintWriter): Boolean {
            check(session.webSocket == null) { "Tried to WS_OPEN but already have a websocket" }

            return tryLog(session, out) {
                val wsUrl = session.wsUrl + session.replaceTokens(url)
                session.webSocket = WebSocketFactory().createSocket(wsUrl).also {
                    it.addListener(object : WebSocketAdapter() {
                        override fun onTextMessage(sock: WebSocket, msg: String) {
                            if (canIgnore(msg)) {
                                session.log.debug { "%%% Ignoring $msg" }
                            } else {
                                session.log.debug { "%%% Received: $msg" }
                                if (!session.receiveQueue.offer(session.replaceTokens(msg))) {
                                    throw Exception("receiveQueue is full (max = ${session.receiveQueueSize})")
                                }
                            }
                        }

                        override fun onStateChanged(websocket: WebSocket?, newState: WebSocketState?) =
                                session.log.debug { "%%% State $newState" }
                    })

                    it.addHeader("Cookie", session
                            .cookieStore
                            .cookies
                            .map { "${it.name}=${it.value}" }
                            .joinToString("; "))

                    it.connect()
                }
            }
        }
    }

    class WS_RECV(override val created: Long,
                  override val lineNumber: Int,
                  val message: String) : Event(created, lineNumber) {
        override fun handle(session: ShinySession, out: PrintWriter): Boolean {
            return tryLog(session, out) {
                // Waits indefinitely for a message to become available
                val receivedStr = session.receiveQueue.take()
                session.log.debug { "WS_RECV received: $receivedStr" }
                // Because the messages in our log file are extra-escaped, we need to unescape once.
                val expectingStr = session.replaceTokens(message)
                val expectingObj = parseMessage(expectingStr)
                if (expectingObj == null) {
                    check(expectingStr == receivedStr) {
                        "Expected string $expectingStr but got $receivedStr"
                    }
                } else {
                    val receivedObj = parseMessage(receivedStr)
                    check(expectingObj.keySet() == receivedObj?.keySet()) {
                        "Objects don't have same keys: $expectingObj, $receivedObj"
                    }
                }
            }
        }
    }

    class WS_RECV_INIT(override val created: Long,
                       override val lineNumber: Int,
                       val message: String) : Event(created, lineNumber) {
        override fun handle(session: ShinySession, out: PrintWriter): Boolean {
            return tryLog(session, out) {
                // Waits indefinitely for a message to become available
                val receivedStr = session.receiveQueue.take()
                session.log.debug { "WS_RECV_INIT received: $receivedStr" }

                val sessionId = parseMessage(receivedStr)
                        ?.get("config")
                        ?.asJsonObject
                        ?.get("sessionId")
                        ?.asString
                        ?: throw IllegalStateException("Expected sessionId from WS_RECV_INIT message")

                session.tokenDictionary["SESSION"] = sessionId
                session.log.debug { "WS_RECV_INIT got SESSION: ${session.tokenDictionary["SESSION"]}" }
            }
        }
    }

    class WS_SEND(override val created: Long,
                  override val lineNumber: Int,
                  val message: String) : Event(created, lineNumber) {
        override fun sleepBefore(session: ShinySession) =
                created - (session.lastEventCreated ?: created)

        override fun handle(session: ShinySession, out: PrintWriter): Boolean {
            return tryLog(session, out) {
                val text = session.replaceTokens(message)
                session.webSocket!!.sendText(text)
                session.log.debug { "WS_SEND sent: $text" }
            }
        }
    }

    class WS_CLOSE(override val created: Long,
                   override val lineNumber: Int) : Event(created, lineNumber) {
        override fun sleepBefore(session: ShinySession) =
                created - (session.lastEventCreated ?: created)

        override fun handle(session: ShinySession, out: PrintWriter): Boolean {
            return tryLog(session, out) {
                session.webSocket!!.sendClose()
                session.log.debug { "WS_CLOSE sent" }
            }
        }
    }
}