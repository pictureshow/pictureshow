/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * HOME-23: a BlurHash (the published format Jellyfin sends in ImageBlurHashes) decoded to a tiny picture that stands in
 * for artwork while it loads, so a tile or backdrop arrives as its own colours instead of flat grey.
 * Written from the format description; an invalid hash returns null and the caller keeps its tinted placeholder.
 */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"

    private fun decode83(text: String, from: Int, to: Int): Int? {
        var value = 0
        for (index in from until to) {
            val digit = CHARS.indexOf(text[index])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    private fun toLinear(channel: Int): Double {
        val v = channel / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val srgb = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (srgb * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    /** The hash's components (x, y), or null when it isn't a well-formed BlurHash. */
    fun components(hash: String): Pair<Int, Int>? {
        if (hash.length < 6) return null
        val size = decode83(hash, 0, 1) ?: return null
        val x = size % 9 + 1
        val y = size / 9 + 1
        return if (hash.length == 4 + 2 * x * y) x to y else null
    }

    /** The average colour as 0xRRGGBB: the hash's DC term, which is what a viewer sees first. */
    fun averageColor(hash: String): Int? {
        components(hash) ?: return null
        return decode83(hash, 2, 6)
    }

    /**
     * [width] x [height] pixels, row-major, each 0xFFRRGGBB. [punch] above 1 deepens the colour detail.
     * Keep the size small (32 x 32 is plenty); the image is drawn scaled and the cost is width x height x components.
     */
    fun decode(hash: String, width: Int, height: Int, punch: Double = 1.0): IntArray? {
        if (width <= 0 || height <= 0) return null
        val (numX, numY) = components(hash) ?: return null
        val quantMax = decode83(hash, 1, 2) ?: return null
        val maxValue = (quantMax + 1) / 166.0
        val colors = Array(numX * numY) { DoubleArray(3) }
        val dc = decode83(hash, 2, 6) ?: return null
        colors[0][0] = toLinear(dc shr 16 and 0xFF)
        colors[0][1] = toLinear(dc shr 8 and 0xFF)
        colors[0][2] = toLinear(dc and 0xFF)
        for (index in 1 until numX * numY) {
            val value = decode83(hash, 4 + index * 2, 6 + index * 2) ?: return null
            fun ac(quantized: Int): Double {
                val unit = (quantized - 9) / 9.0
                return (abs(unit).pow(2.0)).withSign(unit) * maxValue * punch
            }
            colors[index][0] = ac(value / (19 * 19))
            colors[index][1] = ac(value / 19 % 19)
            colors[index][2] = ac(value % 19)
        }
        val cosX = Array(numX) { i -> DoubleArray(width) { x -> cos(PI * x * i / width) } }
        val cosY = Array(numY) { j -> DoubleArray(height) { y -> cos(PI * y * j / height) } }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        val basis = cosX[i][x] * cosY[j][y]
                        val color = colors[i + j * numX]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or (toSrgb(r) shl 16) or (toSrgb(g) shl 8) or toSrgb(b)
            }
        }
        return pixels
    }
}

/** 0xRRGGBB as "#RRGGBB", for vectors and logs. */
fun rgbHex(color: Int): String = "#" + (color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')
