package com.rstudio.proxyrec

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.neovisionaries.ws.client.WebSocket
import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import mu.KLogger
import mu.KotlinLogging
import net.moznion.uribuildertiny.URIBuilderTiny
import org.apache.log4j.ConsoleAppender
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import java.io.File
import java.io.PrintWriter
import java.lang.Exception
import java.lang.reflect.Modifier
import java.nio.file.Paths
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun readEventLog(logPath: String): ArrayList<Event> {
    return File(logPath).readLines()
            .asSequence()
            .filterNot { it.startsWith("#") }
            .fold(ArrayList()) { events, line ->
                events.also { it.add(Event.fromLine(line)) }
            }
}

fun randomHexString(numchars: Int): String {
    val r = SecureRandom()
    val sb = StringBuffer()
    while (sb.length < numchars) sb.append(java.lang.Integer.toHexString(r.nextInt()))
    return sb.toString().substring(0, numchars)
}

fun getTokens(url: String): HashSet<String> {
    val tokens = HashSet<String>()
    for (token in Regex("""\$\{([A-Z_]+)}""").findAll(url)) {
        // we know the next line is safe because: token.groups.forEach { println(it) }
        tokens.add(token.groups[1]!!.value)
    }
    return tokens
}

// Messages strings originating from the server are not escaped. Strings
// originating from the log file are escaped.
fun unescape(s: String) = """\\(.)""".toRegex().replace(s, """$1""")

fun replaceTokens(s: String,
                  allowedTokens: HashSet<String>,
                  tokenDictionary: HashMap<String, String>): String {

    val tokensInUrl = getTokens(s)

    if (allowedTokens.union(tokensInUrl) != allowedTokens) {
        val illegalTokens = tokensInUrl.filterNot { allowedTokens.contains(it) }
        throw Exception("$illegalTokens are illegal tokens")
    }

    return tokensInUrl.fold(s) { newS, tokenName ->
        if (!tokenDictionary.containsKey(tokenName))
            throw Exception("$tokenName is an allowed token, but it isn't present in the dictionary")
        newS.replace("\${$tokenName}", tokenDictionary[tokenName]!!, true)
    }
}

fun parseMessage(msg: String): JsonObject? {
    // If an unparsed message is from a reconnect-enabled server, it will have a
    // message ID on it. We want to ignore those for the purposes of looking at
    // matches, because they can vary sometimes (based on ignorable messages
    // sneaking into the message stream).
    val normalized = msg.replace("""^a\["[0-9A-F]+""".toRegex(), """a["*""")

    val re = Pattern.compile("""^a\["(\*#)?0\|m\|(.*)"\]${'$'}""")
    val matcher = re.matcher(normalized)
    val json = JsonParser()
    if (matcher.find()) {
        val inner = json.parse("\"${matcher.group(2)}\"").asString
        return json.parse(inner).asJsonObject
    } else {
        return null
    }
}

// Represents a single "user" during the course of a LoadTest.
class ShinySession(val sessionId: Int,
                   val sessionCount: AtomicInteger,
                   val outputDir: File,
                   val httpUrl: String,
                   var script: ArrayList<Event>,
                   val log: KLogger,
                   val wsConnectTimeoutMs: Int = 5000,
                   val awaitTimeoutMs: Int = 5000) {

    val wsUrl: String = URIBuilderTiny(httpUrl).setScheme("ws").build().toString()

    val allowedTokens: HashSet<String> = hashSetOf("WORKER", "TOKEN", "ROBUST_ID", "SOCKJSID", "SESSION")
    val tokenDictionary: HashMap<String, String> = hashMapOf(
            Pair("ROBUST_ID", randomHexString(18)),
            Pair("SOCKJSID", "000/${randomHexString(8)}")
    )

    var webSocket: WebSocket? = null
    val receiveQueueSize = 5
    val receiveQueue: LinkedBlockingQueue<String> = LinkedBlockingQueue(receiveQueueSize)

    var lastEventCreated: Long? = null

    fun replaceTokens(s: String) = replaceTokens(s, allowedTokens, tokenDictionary)

    fun waitForMessage(): String {
        return receiveQueue.poll(awaitTimeoutMs.toLong(), TimeUnit.MILLISECONDS) ?:
                throw TimeoutException("Timed out waiting for message")
    }

    fun end() {
        log.debug { "Ending session" }
        // TODO either assert that there are no pending inbound messages, OR warn about them?
        webSocket?.sendClose()
    }

    fun recordResult(out: PrintWriter, type: String, startedAt: Long, finishedAt: Long) {
        out.println("$type, $startedAt, $finishedAt")
    }

    fun recordResult(out: PrintWriter, event: Event, body: () -> Any) {
        recordResult(out, event.name(), body)
    }

    fun recordResult(out: PrintWriter, type: String, body: () -> Any) {
        val startedAt = Instant.now().toEpochMilli()
        body()
        recordResult(out, type, startedAt, Instant.now().toEpochMilli())
    }

    fun recordResult(out: PrintWriter, type: String) {
        val now = Instant.now().toEpochMilli()
        recordResult(out, type, now, now)
    }

    fun run(startDelayMs: Int = 0) {
        lastEventCreated = Instant.now().toEpochMilli()
        val outputFile = outputDir.toPath().resolve("$sessionId.log").toFile()
        outputFile.printWriter().use { out ->
            recordResult(out, "PLAYER_START_DELAY") {
                Thread.sleep(startDelayMs.toLong())
            }
            log.info { "Started session $sessionId (${sessionCount.incrementAndGet()} running)" }
            for (i in 0 until script.size) {
                val currentEvent = script[i]
                val sleepFor = currentEvent.sleepBefore(this)
                if (sleepFor > 0) {
                    recordResult(out, "PLAYER_SLEEP") { Thread.sleep(sleepFor) }
                }
                recordResult(out, currentEvent) {
                    currentEvent.handle(this)
                }
                lastEventCreated = currentEvent.created
            }
            recordResult(out, "PLAYER_DONE")
            log.info { "Finishing session $sessionId (${sessionCount.get()} running)" }
        }
    }
}

// Represents many users over the course of a test.
class LoadTest(val httpUrl: String,
        // Path to input file created by proxyrec record
               val logPath: String,
               val wsConnectTimeoutMs: Int = 5000,
               val awaitTimeoutMs: Int = 5000,
        // Number of milliseconds to wait between starting sessions.
               val startDelayMs: Int = 0,
        // Number of sessions to start
               val numSessions: Int,
        // Path of directory to place session logs
               val outputDir: File) {

    fun run() {
        val log = readEventLog(logPath)
        check(log.size > 0) { "input log must not be empty" }
        val logger = KotlinLogging.logger {}
        val sessionCount = AtomicInteger(0)

        for (i in 1..numSessions) {
            thread {
                val session = ShinySession(i, sessionCount, outputDir, httpUrl, log, logger)
                try {
                    session.run(startDelayMs*i)
                } finally {
                    session.end()
                    sessionCount.decrementAndGet()
                }
            }
        }
    }
}

class Args(parser: ArgParser) {
    val logPath by parser.positional("Path to Shiny interaction log file")
    val sessions by parser.storing("Number of sessions to simulate. Default is 1.") { toInt() }
            .default(1)
    val appUrl by parser.storing("URL of the Shiny application to interact with")
    val outputDir by parser.storing("Path to directory to store session logs in for this test run")
            .default("test-logs-${Instant.now()}")
    val startDelay by parser.storing("Number of milliseconds to wait between starting sessions") { toInt() }
            .default(0)
    val logLevel by parser.storing("Log level (default: warn, available include: debug, info, warn, error)") {
        Level.toLevel(this.toUpperCase(), Level.WARN) as Level
    }
}

fun main(args: Array<String>) = mainBody("player") {
    Args(ArgParser(args)).run {
        val ca = ConsoleAppender()
        ca.layout = PatternLayout("%5p [%t] %d (%F:%L) - %m%n")
        ca.threshold = logLevel
        ca.activateOptions()
        Logger.getRootLogger().addAppender(ca)

        val output = File(outputDir)
        check(!output.exists()) { "Output dir already exists" }
        output.mkdirs()
        val loadTest = LoadTest(appUrl, logPath, numSessions = sessions, outputDir = output, startDelayMs = startDelay)
        loadTest.run()
    }
}