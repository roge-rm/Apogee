package com.rm.apogee.core.math

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serialisation for the math types.
 *
 * [Vec3] and [Quat] are mutable classes - which is right for a simulation that
 * must not allocate per tick, but wrong for `@Serializable`, which wants an
 * immutable shape. These surrogates bridge the two: the wire and disk formats
 * are plain immutable records, and the conversion happens at the boundary.
 *
 * The same format serves part definitions (hand-edited JSON), craft designs
 * (save files) and network snapshots, so a field name chosen here is a field
 * name in all three.
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
        // Anything arriving from disk or the network is untrusted: a
        // non-normalised quaternion would quietly shear every mesh attached to
        // it, so normalise on the way in rather than trusting the source.
        return Quat(s.x, s.y, s.z, s.w).normalizeInPlace()
    }
}

/** Convenience aliases for `@Serializable` properties. */
typealias SerialVec3 = @Serializable(with = Vec3Serializer::class) Vec3
typealias SerialQuat = @Serializable(with = QuatSerializer::class) Quat
