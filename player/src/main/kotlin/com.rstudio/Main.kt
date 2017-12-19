package com.rstudio

import com.github.kittinunf.fuel.core.Response
import com.github.kittinunf.fuel.httpGet
import com.google.gson.JsonParser
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import mu.KLogger
import mu.KotlinLogging
import java.io.File
import java.lang.Exception
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.websocket.Session

sealed class Event

enum class HTTPEventType { REQ, REQ_HOME, REQ_SINF, REQ_TOK }
data class HTTPEvent(val type: HTTPEventType,
                     val created: Instant,
                     val url: String,
                     val method: String,
                     val statusCode: Int) : Event()

enum class WSEventType { WS_OPEN, WS_RECV, WS_RECV_INIT, WS_SEND }
data class WSEvent(val type: WSEventType,
                   val created: Instant,
                   val url: String?,
                   val message: String?) : Event()

fun parseLine(line: String): Event {
    val obj = JsonParser().parse(line).asJsonObject
    when (obj.get("type").asString) {
        in HTTPEventType.values().map { it.name } ->
            return HTTPEvent(HTTPEventType.valueOf(obj.get("type").asString),
                    Instant.parse(obj.get("created").asString),
                    obj.get("url").asString,
                    obj.get("method").asString,
                    obj.get("statusCode").asInt)
        in WSEventType.values().map { it.name } ->
            return WSEvent(WSEventType.valueOf(obj.get("type").asString),
                    Instant.parse(obj.get("created").asString),
                    obj.get("url")?.asString,
                    obj.get("message")?.asString)
        else -> {
            throw Exception("Failed to parse log entry: $line")
        }
    }
}

fun readEventLog(logPath: String): ArrayList<out Event> {
    return File(logPath).readLines()
            .asSequence()
            .filterNot { it.startsWith("#") }
            .fold(ArrayList<Event>()) { events, line ->
                events.also { it.add(parseLine(line)) }
            }
}

fun wsEventsEqual(e1: WSEvent, e2: WSEvent): Boolean {
    return true;
}

// mirNTMNTw2zWVwTu7P is an example
fun getRandomHexString(numchars: Int = 18): String {
    val r = SecureRandom()
    val sb = StringBuffer()
    while (sb.length < numchars) {
        sb.append(Integer.toHexString(r.nextInt()))
    }
    return sb.toString().substring(0, numchars)
}

fun getTokens(url: String): HashSet<String> {
    val tokens = HashSet<String>()
    for (token in Regex("""\$\{([A-Z_]+)}""" ).findAll(url)) {
        // we know the next line is safe because: token.groups.forEach { println(it) }
        tokens.add(token.groups[1]!!.value)
    }
    return tokens
}

fun tokenizeUrl(url: String,
                allowedTokens: HashSet<String>,
                tokenDictionary: HashMap<String, String>): String {

    val tokensInUrl = getTokens(url)
    if (allowedTokens.union(tokensInUrl) != allowedTokens) {
        val illegalTokens = tokensInUrl.filterNot { allowedTokens.contains(it) }
        throw Exception("$illegalTokens are illegal tokens")
    }

    return tokensInUrl.fold(url) { str, tokenName ->
        if (!tokenDictionary.containsKey(tokenName))
            throw Exception("$tokenName is an allowed token, but it isn't present in the dictionary")
        str.replace("\${$tokenName}", tokenDictionary[tokenName]!!, true)
    }
}

class ShinySession(val appUrl: String,
                   var script: ArrayList<out Event>,
                   val log: KLogger) {

    val allowedTokens: HashSet<String> = hashSetOf("WORKER", "TOKEN", "ROBUST_ID", "SOCKJSID")
    var urlDictionary: HashMap<String, String> = hashMapOf(Pair("ROBUST_ID", getRandomHexString()))

    var workerId: String? = null
    var sessionToken: String? = null
    var robustId: String = getRandomHexString()
    var expecting: WSEvent? = null
    var wsSession: Session? = null
    val receivedEvent: LinkedBlockingQueue<WSEvent> = LinkedBlockingQueue(1)

    init {
        log.debug { "Hello ok!" }
    }

    fun isDone(): Boolean {
        return script.size == 0
    }

    fun wsEventsEqual(event1: WSEvent, event2: WSEvent): Boolean {
        return false
    }

    fun handle(event: Event) {
        when(event) {
            is WSEvent -> handle(event)
            is HTTPEvent -> handle(event)
        }
    }

    fun handle(event: WSEvent) {
    }

    fun handle(event: HTTPEvent) {

        fun getResponse(event: HTTPEvent, workerIdRequired: Boolean = true): Response {
            val url = tokenizeUrl(event.url, allowedTokens, urlDictionary)
            val response = (appUrl + url).httpGet().responseString().second
            if (response.statusCode != event.statusCode)
                throw Exception("Status code was ${response.statusCode} but expected ${event.statusCode}")
            return response
        }

        when (event.type) {
            // {"type":"REQ_HOME","created":"2017-12-14T16:43:32.748Z","method":"GET","url":"/","statusCode":200}
            HTTPEventType.REQ_HOME -> {
                val response = getResponse(event, false)
                val re = Pattern.compile("<base href=\"_w_([0-9a-z]+)/")
                val matcher = re.matcher(response.toString())
                if (matcher.find()) {
                    urlDictionary["WORKER"] = matcher.group(1)
                } else {
                    throw Exception("Unable to parse worker ID from response to REQ_HOME event. (Perhaps you're running SS Open Source or in local development?)")
                }
            }
            // {"type":"REQ","created":"2017-12-14T16:43:34.045Z","method":"GET","url":"/_w_${WORKER}/__assets__/shiny-server.css","statusCode":200}
            HTTPEventType.REQ -> {
                val response = getResponse(event)
            }
            // {"type":"REQ_TOK","created":"2017-12-14T16:43:34.182Z","method":"GET","url":"/_w_${WORKER}/__token__?_=1513269814000","statusCode":200}
            HTTPEventType.REQ_TOK -> {
                sessionToken = String(getResponse(event).data)
            }
            // {"type":"REQ_SINF","created":"2017-12-14T16:43:34.244Z","method":"GET","url":"/__sockjs__/n=${ROBUST_ID}/t=${TOKEN}/w=${WORKER}/s=0/info","statusCode":200}
            HTTPEventType.REQ_SINF -> {
                val response = getResponse(event)
            }
        }

        log.debug { "Handled ${event}" }
    }

    fun step() {
        if (expecting != null) {
            log.debug { "Expecting a websocket response..." }
            val received = receivedEvent.poll(5, TimeUnit.SECONDS)
            if (wsEventsEqual(expecting!!, received)) {
                expecting = null
                log.debug { "Expected ${expecting} and was pleasantly surprised to receive ${received}" }
            } else {
                throw IllegalStateException("Expected ${expecting} but received ${received}")
            }
        } else if (script.size > 0) {
            handle(script.get(0))
            script.removeAt(0)
        } else {
            throw IllegalStateException("Can't step; not expecting an event, and out of events to send")
        }
    }
}

class Args(parser: ArgParser) {
    val logPath by parser.positional("Path to Shiny interaction log file")
    val users by parser.storing("Number of users to simulate. Default is 1.") { toInt() }
            .default("1")
    val appUrl by parser.storing("URL of the Shiny application to interact with")
}

fun copyLog(oldScript: ArrayList<out Event>): ArrayList<out Event> {
    return oldScript.fold(ArrayList<Event>()) { copy, item ->
        copy.also { it.add(item) }
    }
}

fun _main(args: Array<String>) = mainBody("player") {
    Args(ArgParser(args)).run {
        val log = readEventLog(logPath)
        val session = ShinySession(appUrl, copyLog(log), KotlinLogging.logger {})
        session.step()
        session.step()
        session.step()
//        while (!session.isDone())
//            session.step()
//        val log = readEventLog(logPath)
//        log.listIterator().forEach { println(it) }
    }
}

fun main(args: Array<String>) {
    if (System.getProperty("user.name") == "alandipert") {
        _main(arrayOf("--users", "1", "--app-url", "http://localhost:3838/sample-apps/hello/", "geyser-short.log"))
    } else {
        _main(arrayOf("--users", "1", "--app-url", "http://10.211.55.6:3838/sample-apps/hello/", "hello.log"))
    }
}