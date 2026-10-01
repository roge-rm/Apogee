package com.rm.apogee.core.math

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serialisation for the math types. [Vec3] and [Quat] are mutable so the simulation doesn't
 * allocate, but `@Serializable` wants immutable records, so these surrogates convert at the
 * boundary.
 *
 * The same format covers part definitions (hand-edited JSON), craft designs (saves) and network
 * snapshots, so a field name here is one in all three.
 */
@Serializable
private data class Vec3Surrogate(val x: Double, val y: Double, val z: Double)

object Vec3Serializer : KSerializer<Vec3> {
    override val descriptor: SerialDescriptor = Vec3Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Vec3) =
        encoder.encodeSerializableValue(
            Vec3Surrogate.serializer(),
            Vec3Surrogate(value.x, value.y, value.z),
        )

    override fun deserialize(decoder: Decoder): Vec3 {
        val s = decoder.decodeSerializableValue(Vec3Surrogate.serializer())
        return Vec3(s.x, s.y, s.z)
    }
}

@Serializable
private data class QuatSurrogate(val x: Double, val y: Double, val z: Double, val w: Double)

object QuatSerializer : KSerializer<Quat> {
    override val descriptor: SerialDescriptor = QuatSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Quat) =
        encoder.encodeSerializableValue(
            QuatSurrogate.serializer(),
            QuatSurrogate(value.x, value.y, value.z, value.w),
        )

    override fun deserialize(decoder: Decoder): Quat {
        val s = decoder.decodeSerializableValue(QuatSurrogate.serializer())
        // Disk and network input can't be trusted, and an unnormalised quaternion shears every mesh
        // on it, so normalise on the way in.
        return Quat(s.x, s.y, s.z, s.w).normalizeInPlace()
    }
}

/** Shorthand aliases for `@Serializable` properties. */
typealias SerialVec3 = @Serializable(with = Vec3Serializer::class) Vec3
typealias SerialQuat = @Serializable(with = QuatSerializer::class) Quat
