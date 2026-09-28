package com.rm.apogee.core

import java.io.File

/** A [Folder] that's a directory on disk, made if it isn't there yet. */
class FileFolder(private val directory: File) : Folder {

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    override fun names(): List<String> = directory.listFiles { file -> file.isFile }?.map { it.name } ?: emptyList()

    override fun read(name: String): String? = File(directory, name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        directory.mkdirs()
        val file = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    override fun delete(name: String): Boolean = File(directory, name).delete()

    override fun exists(name: String): Boolean = File(directory, name).exists()

    override fun lastModified(name: String): Long = File(directory, name).let { if (it.exists()) it.lastModified() else 0L }

    override fun size(name: String): Long = File(directory, name).let { if (it.exists()) it.length() else 0L }

    override fun path(name: String): String = File(directory, name).absolutePath
}
