package com.rm.apogee.net

import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.ServerMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Turns protocol messages into bytes and back.
 *
 * Protobuf rather than JSON: the same craft design travels as a save file (JSON,
 * because a human may want to read it) and as a network payload (binary,
 * because nobody reads those and every byte is sent repeatedly). One
 * `@Serializable` definition, two encodings, no second schema to keep in step.
 */
@OptIn(ExperimentalSerializationApi::class)
object Codec {

    private val format = ProtoBuf {
        // Must stay false. Protobuf has no wire representation for an explicit
        // null, so encoding a defaulted nullable field - PlacedPart's optional
        // attach-node ids, for instance - throws outright. Leaving defaults off
        // the wire is also how protobuf is meant to work: an absent field
        // decodes back to its default.
        encodeDefaults = false
    }

    fun encode(message: ClientMessage): ByteArray =
        format.encodeToByteArray(ClientMessage.serializer(), message)

    fun encode(message: ServerMessage): ByteArray =
        format.encodeToByteArray(ServerMessage.serializer(), message)

    fun decodeClientMessage(bytes: ByteArray): ClientMessage =
        format.decodeFromByteArray(ClientMessage.serializer(), bytes)

    fun decodeServerMessage(bytes: ByteArray): ServerMessage =
        format.decodeFromByteArray(ServerMessage.serializer(), bytes)
}
