package com.rm.apogee.net

import com.rm.apogee.core.world.ClientMessage
import com.rm.apogee.core.world.ServerMessage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Turns protocol messages into bytes and back.
 *
 * I use Protobuf instead of JSON here. The same craft design travels as a save file (JSON, because
 * a person might want to read it) and as a network payload (binary, because nobody reads those and
 * every byte gets sent over and over). One `@Serializable` definition gives two encodings, with no
 * second schema to keep in step.
 */
@OptIn(ExperimentalSerializationApi::class)
object Codec {

    private val format = ProtoBuf {
        // This must stay false. Protobuf has no way to send an explicit null, so encoding a
        // defaulted nullable field (PlacedPart's optional attach-node ids, for example) throws
        // straight away. Leaving defaults off the wire is also how protobuf is meant to work,
        // because a missing field decodes back to its default.
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
