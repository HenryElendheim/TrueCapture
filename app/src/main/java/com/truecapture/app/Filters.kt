package com.truecapture.app

import android.graphics.ColorMatrix
import org.json.JSONArray
import org.json.JSONObject

// The slider values behind a custom filter, kept so it can be edited later.
data class CustomParams(
    val name: String,
    val warmth: Float,
    val brightness: Float,
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

    // Build the colour matrix for a set of slider values. warmth and brightness
    // are roughly -50 to 50, saturation is 0 (grey) to 2 (punchy).
    fun matrixFor(params: CustomParams): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(params.saturation)
        val warmAndBright = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, params.warmth + params.brightness,
                0f, 1f, 0f, 0f, params.brightness,
                0f, 0f, 1f, 0f, -params.warmth + params.brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(warmAndBright)
        return cm
    }

    fun toFilter(params: CustomParams): Filter = Filter(params.name, matrixFor(params), params)

    // Read the saved custom filters.
    fun loadCustom(json: String?): List<CustomParams> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                CustomParams(
                    o.getString("name"),
                    o.getDouble("warmth").toFloat(),
                    o.getDouble("brightness").toFloat(),
                    o.getDouble("saturation").toFloat()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Turn a list of custom filters back into JSON for storage.
    fun toJson(list: List<CustomParams>): String {
        val array = JSONArray()
        for (p in list) {
            array.put(
                JSONObject()
                    .put("name", p.name)
                    .put("warmth", p.warmth.toDouble())
                    .put("brightness", p.brightness.toDouble())
                    .put("saturation", p.saturation.toDouble())
            )
        }
        return array.toString()
    }
}
