package com.rm.apogee.server

/**
 * Entry point for the standalone dedicated server (`./gradlew :server:run`).
 *
 * The same [GameServer] class this will instantiate is also constructed
 * in-process when a phone hosts a game, so there is exactly one implementation
 * of the authoritative simulation. Filled in at M4/M5; the module exists now so
 * nothing in :core or :net can quietly grow an Android dependency.
 */
fun main(args: Array<String>) {
    println("Apogee dedicated server - not yet implemented (M5). args=${args.joinToString()}")
}
