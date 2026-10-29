package dev.halftoast.stmobile.panel

import java.net.InetAddress

private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}""")
private val IPV6_CHARS = Regex("""[0-9a-fA-F:.]+""")
private val PREFIX = Regex("""\d{1,3}""")

/**
 * True for an IPv4/IPv6 address with an optional CIDR prefix (<=32 / <=128). A strict regex
 * pre-check runs first, and IPv6 literals always contain ':' so InetAddress never does a DNS lookup.
 */
fun isValidIpOrCidr(s: String): Boolean {
    val slash = s.indexOf('/')
    val addr = if (slash >= 0) s.substring(0, slash) else s
    val prefix = if (slash >= 0) s.substring(slash + 1) else null
    if (prefix != null && !PREFIX.matches(prefix)) return false
    val p = prefix?.toInt()
    return when {
        IPV4.matches(addr) -> p == null || p <= 32
        addr.count { it == ':' } >= 2 && IPV6_CHARS.matches(addr) ->
            (p == null || p <= 128) && runCatching { InetAddress.getByName(addr) }.isSuccess
        else -> false
    }
}
