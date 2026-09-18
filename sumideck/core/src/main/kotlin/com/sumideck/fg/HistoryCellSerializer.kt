package com.sumideck.fg

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** `["2026-09-18", 28.54]` の要素は文字列と数値が混在する。どちらでも落ちないようにする。 */
object HistoryCellSerializer : KSerializer<HistoryCell> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("HistoryCell", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): HistoryCell {
        val input = decoder as? JsonDecoder ?: return HistoryCell(decoder.decodeString(), null)
        val element = input.decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return HistoryCell(null, null)
        return if (primitive.isString) HistoryCell(primitive.content, null)
        else HistoryCell(null, primitive.doubleOrNull)
    }

    override fun serialize(encoder: Encoder, value: HistoryCell) {
        value.text?.let { encoder.encodeString(it); return }
        encoder.encodeDouble(value.number ?: 0.0)
    }
}
