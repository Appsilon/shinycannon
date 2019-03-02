package com.rstudio.shinycannon

import net.moznion.uribuildertiny.URIBuilderTiny
import org.apache.http.HttpEntity
import org.apache.http.client.config.CookieSpecs
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.BasicCookieStore
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.message.BasicNameValuePair
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

data class HttpResponse(val statusCode: Int,
                        val headers: Map<String, String>,
                        val cookies: BasicCookieStore,
                        val body: String)

fun slurp(req: HttpUriRequest, cookies: BasicCookieStore = BasicCookieStore()): HttpResponse {
    val cfg = RequestConfig.custom()
            .setCookieSpec(CookieSpecs.STANDARD)
            .build()
    val client = HttpClientBuilder
            .create()
            .setDefaultCookieStore(cookies)
            .setDefaultRequestConfig(cfg)
            .build()
    client.execute(req).use { response ->
        return HttpResponse(response.statusLine.statusCode,
                response.allHeaders.map { Pair(it.name, it.value) }.toMap(),
                cookies,
                ByteArrayOutputStream().let { baos ->
                    response.entity.content.copyTo(baos)
                    baos.toString()
                })
    }
}

fun xpath(docString: String, query: String): Array<Node> {
    val dbf = DocumentBuilderFactory.newInstance()
    val db = dbf.newDocumentBuilder()
    val doc = db.parse(InputSource(StringReader(docString)))
    val xpathFactory = XPathFactory.newInstance()
    val xpath = xpathFactory.newXPath()
    val nodes = xpath.evaluate(query, doc, XPathConstants.NODESET) as NodeList
    return Array(nodes.length, { i -> nodes.item(i) })
}

// Helper function (overrides []) for getting keys from a NamedNodeList, a weird
// XML collection type produced by the XPath API.
operator fun NamedNodeMap.get(itemName: String): String = this.getNamedItem(itemName).nodeValue

enum class AppServer { RSC, SSP, UNKNOWN }

fun isProtected(appUrl: String): Boolean {
    return setOf(403, 404).contains(slurp(HttpGet(appUrl)).statusCode)
}

fun servedBy(resp: HttpResponse): AppServer {
    val headers = resp.headers
    return when {
    // TODO figure out why SSP-XSRF not served by 1.5.8.960
        headers["X-Powered-By"] == "Express" -> AppServer.SSP
        headers["X-Powered-By"] == "Shiny Server Pro" -> AppServer.SSP
        headers.containsKey("rscid") -> AppServer.RSC
        headers["Server"]?.startsWith("RStudio Connect") ?: false -> AppServer.RSC
        resp.cookies.cookies.any { it.name == "rscid" } -> AppServer.RSC
        else -> AppServer.UNKNOWN
    }
}

// Returns a Map of hidden inputs that must be posted along with
// username and password. The map is empty except for SSP.
fun getInputs(resp: HttpResponse, server: AppServer): Map<String, String> {
    return when (server) {
        AppServer.SSP -> {
            xpath(resp.body, "//input[@type='hidden']")
                    .map { it.attributes }
                    .map { attrs -> Pair(attrs["name"], attrs["value"]) }
                    .toMap()
        }
        else -> mapOf()
    }
}

fun loginUrlFor(appUrl: String, server: AppServer): String {
    return when (server) {
        AppServer.RSC -> {
            val url = URIBuilderTiny(appUrl)
            // If there are more path components than e.g. "content/1234", Connect must be behind a reverse proxy
            // and mounted at some sub-path. In that case, __login__ must be appended to that path.
            val loginPath = if (url.paths.size > 2) {
                // Drop "content/1234" or similar from the paths and append __login__
                url.paths.dropLast(2) + "__login__"
            } else {
                // The only path component should be __login__
                mutableListOf("__login__")
            }
            url.setPaths(loginPath).build().toString()
        }
        // TODO Determine if there's a reliable way to detect SSP login path even when proxied
        AppServer.SSP -> URIBuilderTiny(appUrl)
                .appendPaths("__login__")
                .build()
                .toString()
        else -> error("Don't know how to construct login URL for $server")
    }
}

data class AuthContext(val cookies: BasicCookieStore,
                       val inputs: Map<String, String>,
                       val loginUrl: String)

fun getCookies(request: HttpEntityEnclosingRequestBase,
               cookies: BasicCookieStore = BasicCookieStore(),
               entity: HttpEntity): BasicCookieStore {

    val cfg = RequestConfig.custom()
            .setCookieSpec(CookieSpecs.STANDARD)
            .build()
    val client = HttpClientBuilder
            .create()
            .setDefaultCookieStore(cookies)
            .setDefaultRequestConfig(cfg)
            .build()
    request.entity = entity
    client.execute(request).use {
        check(setOf(200, 302).contains(it.statusLine.statusCode), {
            "Received status ${it.statusLine.statusCode} attempting to get cookies"
        })
        return cookies
    }
}

fun loginRSC(context: AuthContext, username: String, password: String): BasicCookieStore {

    val entity = com.google.gson.JsonObject().also {
        it.addProperty("username", username)
        it.addProperty("password", password)
    }.let { StringEntity(it.toString()) }

    val post = HttpPost(context.loginUrl)

    return getCookies(post, context.cookies, entity).apply {
        val authCookie = cookies.firstOrNull { it.name == "rsconnect" }
        checkNotNull(authCookie, { "Couldn't find RSC auth cookie" })
    }
}

fun loginSSP(context: AuthContext, username: String, password: String): BasicCookieStore {

    val fields = mapOf(
            "username" to username,
            "password" to password
    ) + context.inputs

    val entity = fields
            .map { BasicNameValuePair(it.key, it.value) }
            .let { UrlEncodedFormEntity(it) }

    val post = HttpPost(context.loginUrl)

    return getCookies(post, context.cookies, entity).apply {
        val authCookie = cookies.firstOrNull { it.name == "session_state" }
        checkNotNull(authCookie, { "Couldn't find SSP auth cookie" })
    }
}

fun postLogin(appUrl: String, username: String, password: String, cookies: BasicCookieStore): BasicCookieStore {

    val resp = slurp(HttpGet(appUrl), cookies = cookies)
    val server = servedBy(resp)
    val inputs = getInputs(resp, server)
    val loginUrl = loginUrlFor(appUrl, server)
    val context = AuthContext(cookies, inputs, loginUrl)

    return when(server) {
        AppServer.RSC -> loginRSC(context, username, password)
        AppServer.SSP -> loginSSP(context, username, password)
        AppServer.UNKNOWN -> error("Can't log in to unknown server type")
    }
}