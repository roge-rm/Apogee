package com.rm.apogee.render

/**
 * Pairs each item of a frame with itself in the frame before, for easing between them. It matches
 * by [RenderItem.key] where there is one, and otherwise by its place among the items with no key.
 * It's reused frame to frame, on the GL thread.
 */
class ItemMatcher {
    private val _partners = ArrayList<RenderItem?>()
    private val previousByKey = HashMap<Long, RenderItem>()
    private val previousUnkeyed = ArrayList<RenderItem>()

    /** From the last [match]: each item's self in the last frame, or null. */
    val partners: List<RenderItem?> get() = _partners

    fun match(items: List<RenderItem>, previousItems: List<RenderItem>?) {
        _partners.clear()
        previousByKey.clear()
        previousUnkeyed.clear()
        if (previousItems != null) for (item in previousItems) {
            if (item.key != 0L) previousByKey[item.key] = item else previousUnkeyed.add(item)
        }
        var unkeyed = 0
        for (item in items) {
            _partners.add(if (item.key != 0L) previousByKey[item.key] else previousUnkeyed.getOrNull(unkeyed++))
        }
    }
}
