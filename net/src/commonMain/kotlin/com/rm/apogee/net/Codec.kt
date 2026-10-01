package com.rm.apogee.net

import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.ServerMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Turns protocol messages into bytes and back, as Protobuf. Saves use JSON from the same
 * `@Serializable` classes, so there's no second schema.
 */
@OptIn(ExperimentalSerializationApi::class)
object Codec {

    private val format = ProtoBuf {
        // Must stay false. Protobuf can't send a null, so encoding a defaulted nullable field
        // (like PlacedPart's attach-node ids) throws. A missing field decodes to its default.
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
