package com.rm.apogee.core.world

import com.rm.apogee.core.FileFolder
import java.io.File

/** A world kept in [file] on disk, with its backup beside it. */
fun WorldStore(file: File): WorldStore = WorldStore(FileFolder(file.absoluteFile.parentFile), file.name)
