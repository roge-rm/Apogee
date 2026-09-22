package com.rm.apogee.game

import com.rm.apogee.core.math.Vec3
import com.rm.apogee.core.orbit.CelestialBody
import com.rm.apogee.render.PlanetMesh
import com.rm.apogee.render.QualityTier
import com.rm.apogee.render.TerrainSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Builds terrain geometry off the render thread and publishes it.
 *
 * Sampling the height field tens of thousands of times takes long enough that
 * doing it on the GL thread would drop a frame every time the craft moved far
 * enough to need new ground. Everything here runs on a worker and hands
 * finished arrays over through a [TerrainSource].
 *
 * Two meshes at two scales. The globe is built once and never changes - a
 * planet does not. The patch follows the craft, and is only rebuilt when it
 * has travelled far enough to be looking at ground the current one does not
 * cover well.
 */
class TerrainBuilder(
    private val source: TerrainSource,
    private val quality: QualityTier,
) {
    private var job: Job? = null
    private var globeRevision = 0
    private var patchRevision = 0

    /** Where the current patch is centred, in the body-fixed frame. */
    private val patchCentreDirection = Vec3()
    private var patchExtent = 0.0
    private var hasPatch = false

    /**
     * Whether the distant surface - globe and sea sphere - should be drawn
     * as well as the patch.
     *
     * False when the patch already reaches past the horizon, because then
     * both are entirely behind ground the patch has drawn. They are not
     * merely redundant: each is a full-screen fill on a screen the patch is
     * about to paint over, and on a soft rasteriser that is most of a frame.
     */
    var farSurfaceNeeded: Boolean = true
        private set

    /**
     * Whether there is ground under the craft yet.
     *
     * Building a patch samples the height field tens of thousands of times
     * and takes seconds on a phone. Until it lands there is nothing beneath
     * the craft but the globe, which at this resolution sits hundreds of
     * metres off - so the craft appears to hang in the air on a world that
     * reads as broken rather than as still loading. The flight view waits on
     * this.
     *
     * True also when no patch is wanted at all - no height field, or too high
     * for one to add anything - because then there is nothing to wait for.
     */
    @Volatile
    var patchReady: Boolean = false
        private set

    private val globeRings: Int
        get() = when (quality) {
            QualityTier.LOW -> 64
            QualityTier.MEDIUM -> 96
            QualityTier.HIGH -> 128
        }

    /**
     * Vertices along each edge of the near patch.
     *
     * Sized against the finest thing the height field contains. The hill
     * band bottoms out near a hundred and twenty-five metres, so facets have
     * to be under about sixty for none of it to be aliased away - which at a
     * four-kilometre patch means these counts.
     *
     * Coarser was tried, for bigger facets and a stronger low-poly read, and
     * it threw away the only detail close enough to see: the ground within a
     * kilometre of the craft went flat, which looks like terrain that has not
     * finished loading rather than like a style.
     */
    private val patchResolution: Int
        get() = when (quality) {
            QualityTier.LOW -> 96
            QualityTier.MEDIUM -> 128
            QualityTier.HIGH -> 144
        }

    /** Builds the whole body once. Safe to call repeatedly. */
    fun requestGlobe(body: CelestialBody, scope: CoroutineScope) {
        if (globeRevision != 0) return
        globeRevision = 1
        scope.launch(Dispatchers.Default) {
            val data = PlanetMesh.buildGlobe(body.terrain, body.radius, globeRings)
            source.publishGlobe(globeRevision, data)
        }
    }

    /**
     * Rebuilds the near patch if the craft has moved off the current one.
     *
     * @param bodyFixedDirection where the craft is, in the body's own frame -
     *   the patch is a piece of ground and stays with the ground, not with
     *   the inertial position the craft happens to occupy.
     */
    fun followCraft(
        body: CelestialBody,
        bodyFixedDirection: Vec3,
        altitude: Double,
        scope: CoroutineScope,
    ) {
        val field = body.terrain ?: run {
            patchReady = true
            return
        }

        // Size the patch to cover what can actually be seen. The horizon on a
        // sphere is sqrt(2Rh) away, so a craft on the pad needs a few
        // kilometres and one at 40km needs two hundred.
        val horizon = kotlin.math.sqrt(2.0 * body.radius * altitude.coerceAtLeast(1.0))
        val wanted = (horizon * HORIZON_MARGIN)
            .coerceIn(MIN_PATCH_EXTENT_METRES, MAX_PATCH_EXTENT_METRES)

        // The horizon is the test, not the patch size: the distant surface
        // is needed exactly when there is a gap between where the patch stops
        // and where the ground disappears over the edge of the world.
        farSurfaceNeeded = wanted < horizon || altitude > PATCH_CEILING_METRES

        if (altitude > PATCH_CEILING_METRES) {
            hasPatch = false
            patchReady = true
            return
        }
        if (job?.isActive == true) return

        val direction = bodyFixedDirection.normalized()
        if (hasPatch) {
            val cosine = (direction dot patchCentreDirection).coerceIn(-1.0, 1.0)
            val travelled = kotlin.math.acos(cosine) * body.radius
            val scaleChange = wanted / patchExtent
            // Rebuild when the craft has crossed a quarter of the patch, or
            // when its size should change appreciably - climbing out is the
            // case that matters, and a patch sized for the pad looks like a
            // postage stamp from ten kilometres up.
            if (travelled < patchExtent * REBUILD_FRACTION &&
                scaleChange > 1.0 / RESIZE_FACTOR && scaleChange < RESIZE_FACTOR
            ) {
                return
            }
        }

        patchCentreDirection.setTo(direction)
        patchExtent = wanted
        hasPatch = true
        val revision = ++patchRevision

        job = scope.launch(Dispatchers.Default) {
            val centre = Vec3()
            val data = PlanetMesh.buildPatch(
                field = field,
                bodyRadius = body.radius,
                centreDirection = direction,
                extentMetres = wanted,
                resolution = patchResolution,
                outCentre = centre,
            )
            if (isActive) {
                source.publishPatch(revision, data, centre)
                patchReady = true
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        hasPatch = false
        patchReady = false
    }

    private companion object {
        /** A little past the horizon, so its edge is never on screen. */
        const val HORIZON_MARGIN = 1.3

        const val MIN_PATCH_EXTENT_METRES = 4_000.0

        /** Matched to the near pass's far plane; past it nothing is drawn. */
        const val MAX_PATCH_EXTENT_METRES = 250_000.0

        /** Above this the globe alone is as much as the eye can resolve. */
        const val PATCH_CEILING_METRES = 60_000.0

        /** Fraction of the patch the craft may cross before a rebuild. */
        const val REBUILD_FRACTION = 0.25

        /** Size change that justifies a rebuild on its own. */
        const val RESIZE_FACTOR = 1.6
    }
}
