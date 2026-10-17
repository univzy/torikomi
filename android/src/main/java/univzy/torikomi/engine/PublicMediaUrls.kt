package univzy.torikomi.engine

import java.net.InetAddress
import java.net.URI
import univzy.torikomi.data.model.SyncDocument

fun publicMediaUrl(raw: String): String = SyncDocument.mediaUrl(raw).also {
    val uri = URI(it)
    val host = uri.host.removePrefix("[").removeSuffix("]").lowercase()
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && host != "metadata.google.internal") { "Use a public media URL" }
    if (host.contains(':') || host.all { char -> char.isDigit() || char == '.' }) {
        require(isPublicMediaAddress(InetAddress.getByName(host))) { "Private network URLs are not supported" }
    }
}

fun isPublicMediaAddress(address: InetAddress): Boolean {
    val bytes = address.address
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    if (bytes.size == 16) return bytes[0].toInt() and 0xfe != 0xfc
    val first = bytes[0].toInt() and 255
    val second = bytes[1].toInt() and 255
    return first != 0 && first < 224 && !(first == 100 && second in 64..127) &&
        !(first == 198 && second in 18..19) && !(first == 192 && second == 0)
}
