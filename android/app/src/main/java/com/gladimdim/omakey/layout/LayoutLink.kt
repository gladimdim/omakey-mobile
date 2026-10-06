package com.gladimdim.omakey.layout

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** `omakey://layout?d=<raw DEFLATE, base64url, no padding>` (LAYOUT.md, "Sharing"). */
object LayoutLink {
    fun isLayoutLink(text: String) = text.trim().startsWith("omakey://layout?")

    fun decode(link: String): String {
        val d = link.trim().substringAfter("omakey://layout?", "")
            .split('&').firstOrNull { it.startsWith("d=") }?.substring(2)
            ?: throw LayoutException("Layout link has no data")
        val bytes = try {
            Base64.getUrlDecoder().decode(d.trimEnd('='))
        } catch (e: IllegalArgumentException) {
            throw LayoutException("Layout link is damaged")
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw LayoutException("Layout link is damaged")
                }
                out.write(buf, 0, n)
                if (out.size() > LayoutParser.MAX_BYTES) throw LayoutException("Layout is larger than 256 KB")
            }
            return out.toString(Charsets.UTF_8.name())
        } catch (e: java.util.zip.DataFormatException) {
            throw LayoutException("Layout link is damaged")
        } finally {
            inflater.end()
        }
    }

    fun encode(json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setInput(json.toByteArray(Charsets.UTF_8))
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return "omakey://layout?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        } finally {
            deflater.end()
        }
    }
}
