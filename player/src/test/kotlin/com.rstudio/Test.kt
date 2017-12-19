package org.jetbrains.kotlin.testJunit.test

import com.rstudio.getTokens
import com.rstudio.tokenizeUrl
import org.junit.Assert
import org.junit.Test


class Test {

    val testUrl = "foo${'$'}{LOL}bar${'\$'}{LMAO}"
    val allowedTokens: HashSet<String> = hashSetOf("LOL", "DUCK", "LMAO")
    val urlDictionary: HashMap<String, String> = hashMapOf(Pair("LOL", " funny! "), Pair("LMAO", " very funny!!! "))

    @Test
    fun testGetTokens() {
        val tokens = getTokens(testUrl)
        Assert.assertEquals(hashSetOf("LOL", "LMAO"), tokens)
    }

    @Test
    fun testTokenizeUrl() {
        val url = tokenizeUrl(testUrl, allowedTokens, urlDictionary)
        Assert.assertEquals("foo funny! bar very funny!!! ", url)
    }

}