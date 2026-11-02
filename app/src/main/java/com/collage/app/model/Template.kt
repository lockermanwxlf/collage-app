package com.collage.app.model

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved, possibly partly filled collage. Branching off a template copies it into a new editor
 * session; the template itself is never modified.
 *
 * [slots] holds one entry per cell, in layout order; null means the cell is waiting for media.
 * Saved clips keep their trim, volume, mute and framing, but their ids are meaningless: new ones
 * are assigned when a session branches off the template.
 */
data class Template(
    val id: String,
    val name: String,
    val createdAt: Long,
    val aspect: AspectOption,
    val customRatio: Ratio,
    val resolution: Int,
    val customDurationMs: Long?,
    val slots: List<ClipItem?>,
) {
    val emptyCount get() = slots.count { it == null }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("aspect", aspectToJson(aspect))
        put("customRatio", ratioToJson(customRatio))
        put("resolution", resolution)
        customDurationMs?.let { put("customDurationMs", it) }
        put("slots", JSONArray().apply { slots.forEach { put(it?.let(::clipToJson) ?: JSONObject.NULL) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Template {
            val slots = o.getJSONArray("slots")
            return Template(
                id = o.getString("id"),
                name = o.getString("name"),
                createdAt = o.getLong("createdAt"),
                aspect = aspectFromJson(o.getJSONObject("aspect")),
                customRatio = ratioFromJson(o.getJSONObject("customRatio")),
                resolution = o.getInt("resolution"),
                customDurationMs = if (o.has("customDurationMs")) o.getLong("customDurationMs") else null,
                slots = List(slots.length()) { i -> if (slots.isNull(i)) null else clipFromJson(slots.getJSONObject(i)) },
            )
        }

        private fun ratioToJson(r: Ratio) = JSONObject().put("w", r.w).put("h", r.h)
        private fun ratioFromJson(o: JSONObject) = Ratio(o.getInt("w"), o.getInt("h"))

        private fun aspectToJson(a: AspectOption): JSONObject = when (a) {
            AspectOption.Screen -> JSONObject().put("kind", "screen")
            AspectOption.Custom -> JSONObject().put("kind", "custom")
            is AspectOption.Preset -> JSONObject().put("kind", "preset").put("ratio", ratioToJson(a.ratio))
        }

        private fun aspectFromJson(o: JSONObject): AspectOption = when (o.getString("kind")) {
            "custom" -> AspectOption.Custom
            "preset" -> AspectOption.Preset(ratioFromJson(o.getJSONObject("ratio")))
            else -> AspectOption.Screen
        }

        private fun clipToJson(c: ClipItem) = JSONObject().apply {
            put("uri", c.uri.toString())
            put("type", c.type.name)
            put("width", c.width)
            put("height", c.height)
            put("durationMs", c.durationMs)
            put("trimStartMs", c.trimStartMs)
            put("trimEndMs", c.trimEndMs)
            put("scale", c.scale.toDouble())
            put("offsetX", c.offsetX.toDouble())
            put("offsetY", c.offsetY.toDouble())
            put("hasAudio", c.hasAudio)
            put("volume", c.volume.toDouble())
            put("muted", c.muted)
        }

        private fun clipFromJson(o: JSONObject) = ClipItem(
            id = 0,
            uri = Uri.parse(o.getString("uri")),
            type = MediaType.valueOf(o.getString("type")),
            width = o.getInt("width"),
            height = o.getInt("height"),
            durationMs = o.getLong("durationMs"),
            trimStartMs = o.getLong("trimStartMs"),
            trimEndMs = o.getLong("trimEndMs"),
            scale = o.getDouble("scale").toFloat(),
            offsetX = o.getDouble("offsetX").toFloat(),
            offsetY = o.getDouble("offsetY").toFloat(),
            hasAudio = o.getBoolean("hasAudio"),
            volume = o.getDouble("volume").toFloat(),
            muted = o.getBoolean("muted"),
        )
    }
}
