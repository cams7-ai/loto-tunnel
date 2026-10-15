package br.com.lotobot.tunnel.infrastructure

import java.net.Inet6Address
import java.net.InetAddress

object Ipv6 {
    fun normalize(value: String): String {
        val text = value.trim()
        require(text.contains(':') && text.matches(Regex("[0-9a-fA-F:.]+"))) { "A resposta não é IPv6." }
        val address = InetAddress.getByName(text)
        require(address is Inet6Address && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress && (address.address[0].toInt() and 0xfe) != 0xfc) { "A resposta não é um IPv6 público." }
        return address.address.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun validate(value: String): String { normalize(value); return value.trim() }
}
