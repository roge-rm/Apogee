package com.rm.apogee.core

/**
 * Somewhere to keep text files: a directory on disk on the JVM, the browser's storage on the web.
 * It's all the stores need (craft, assemblies, worlds), so they're the same everywhere.
 */
interface Folder {
    /** The names of the files in it. */
    fun names(): List<String>

    /** A file's text, or null if there's no such file. */
    fun read(name: String): String?

    /**
     * Writes a file, safely: until the new text is all there, the old stays, so a crash in the
     * middle of writing leaves the previous version instead of a cut-off one.
     */
    fun write(name: String, text: String)

    fun delete(name: String): Boolean

    fun exists(name: String): Boolean

    /** When the file was last written, in milliseconds since 1970, or 0 if there's no such file. */
    fun lastModified(name: String): Long

    /** Its size in bytes, or 0 if there's no such file. */
    fun size(name: String): Long

    /** Where the file is, to show someone. */
    fun path(name: String): String
}
