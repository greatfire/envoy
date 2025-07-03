import org.greatfire.envoy.UrlUtil

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class UrlUtilTest {

    @Test
    fun sanitizeHostname_empty() {
        val expected = "???Empty?"
        val result = UrlUtil.sanitizeHostname("")
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitizeHostname_noDot() {
        val expected = "???"
        val result = UrlUtil.sanitizeHostname("foo")
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitizeHostname_oneDot() {
        val expected = "greatfire.???"
        val result = UrlUtil.sanitizeHostname("greatfire.org")
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitizeHostname_twoDots() {
        val expected = "???.guardianproject.???"
        val result = UrlUtil.sanitizeHostname("www.guardianproject.info")
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitizeHostname_threeDots() {
        val expected = "???.long.defo.???"
        val result = UrlUtil.sanitizeHostname("fake.long.defo.ie")
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitizeUrl_empty() {
        val result = UrlUtil.sanitizeUrl("")
        assertTrue("Empty???".equals(result))
    }

    @Test
    fun sanitizeUrl_https() {
        val result = UrlUtil.sanitizeUrl("https://www.greatfire.org/path/")
        val expected = "https://???.greatfire.???/…"
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitizeUrl_invalid() {
        val result = UrlUtil.sanitizeUrl("http:/blah/blah")
        val expected = "?Invalid??"
        assertTrue(expected.equals(result))
    }

    @Test
    fun sanitiszeUrl_hysteria2() {
        val result = UrlUtil.sanitizeUrl("hysteria2://fake_auth_info@api2.example.com:443/")
        val expected = "hysteria2://???.example.???/…"
        assertTrue(expected.equals(result))
    }
}