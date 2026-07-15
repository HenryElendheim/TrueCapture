package com.truecapture.app

import android.graphics.ColorMatrix
import org.json.JSONArray
import org.json.JSONObject

// The slider values behind a custom filter, kept so it can be edited later.
// warmth and tint and brightness are roughly -50..50, contrast is 0.5..1.5,
// saturation is 0 (grey) to 2 (punchy).
data class CustomParams(
    val name: String,
    val warmth: Float,
    val tint: Float,
    val brightness: Float,
    val contrast: Float,
    val saturation: Float
)

// A colour look. matrix == null means the original photo (no change). params is
// set only for custom filters, so they can be edited or removed.
data class Filter(
    val name: String,
    val matrix: ColorMatrix?,
    val params: CustomParams? = null
)

object Filters {

    // At most this many filters in the picker (built-in plus custom).
    const val MAX_FILTERS = 10

    // The five built-in looks, plus the untouched original.
    val standard: List<Filter> = listOf(
        Filter("Original", null),
        Filter(
            "Warm",
            ColorMatrix(
                floatArrayOf(
                    1.15f, 0f, 0f, 0f, 12f,
                    0f, 1.02f, 0f, 0f, 4f,
                    0f, 0f, 0.9f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        Filter(
            "Cool",
            ColorMatrix(
                floatArrayOf(
                    0.9f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1.15f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        Filter(
            "Vintage",
            ColorMatrix(
                floatArrayOf(
                    0.9f, 0.45f, 0.18f, 0f, 0f,
                    0.32f, 0.8f, 0.16f, 0f, 0f,
                    0.24f, 0.34f, 0.6f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        ),
        Filter("Mono", ColorMatrix().apply { setSaturation(0f) }),
        Filter("Vivid", ColorMatrix().apply { setSaturation(1.6f) })
    )

    // How many custom filters are allowed on top of the built-in ones.
    fun customLimit(): Int = MAX_FILTERS - standard.size

    // Build the colour matrix for a set of slider values.
    fun matrixFor(p: CustomParams): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(p.saturation)

        // Contrast pivots around mid grey.
        val c = p.contrast
        val offset = 128f * (1f - c)
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, offset,
                    0f, c, 0f, 0f, offset,
                    0f, 0f, c, 0f, offset,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )

        // Warmth shifts red vs blue, tint shifts green vs magenta, brightness
        // lifts everything.
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, p.warmth + p.tint + p.brightness,
                    0f, 1f, 0f, 0f, -p.tint + p.brightness,
                    0f, 0f, 1f, 0f, -p.warmth + p.tint + p.brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return cm
    }

    fun toFilter(params: CustomParams): Filter = Filter(params.name, matrixFor(params), params)

    // Read the saved custom filters. Older saves may not have tint or contrast.
    fun loadCustom(json: String?): List<CustomParams> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                CustomParams(
                    o.getString("name"),
                    o.optDouble("warmth", 0.0).toFloat(),
                    o.optDouble("tint", 0.0).toFloat(),
                    o.optDouble("brightness", 0.0).toFloat(),
                    o.optDouble("contrast", 1.0).toFloat(),
                    o.optDouble("saturation", 1.0).toFloat()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toJson(list: List<CustomParams>): String {
        val array = JSONArray()
        for (p in list) {
            array.put(
                JSONObject()
                    .put("name", p.name)
                    .put("warmth", p.warmth.toDouble())
                    .put("tint", p.tint.toDouble())
                    .put("brightness", p.brightness.toDouble())
                    .put("contrast", p.contrast.toDouble())
                    .put("saturation", p.saturation.toDouble())
            )
        }
        return array.toString()
    }
}
