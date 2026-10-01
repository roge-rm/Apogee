package com.rm.apogee.core

/**
 * A place for text files: a directory on the JVM, browser storage on the web. The stores (craft,
 * assemblies, worlds) only need this.
 */
interface Folder {
    /** The names of the files in it. */
    fun names(): List<String>

    /** A file's text, or null if there's no such file. */
    fun read(name: String): String?

    /**
     * Writes a file safely: the old text stays until the new is complete, so a crash can't leave it
     * cut off.
     */
    fun write(name: String, text: String)

    fun delete(name: String): Boolean

    fun exists(name: String): Boolean

    /** When the file was last written, in ms since 1970, or 0 if it doesn't exist. */
    fun lastModified(name: String): Long

    /** Its size in bytes, or 0 if it doesn't exist. */
    fun size(name: String): Long

    /** Where the file is, to show someone. */
    fun path(name: String): String
}
