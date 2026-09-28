package com.rm.apogee.core.craft

import com.rm.apogee.core.FileFolder
import java.io.File

/** Craft kept in [directory] on disk. */
fun CraftStore(directory: File): CraftStore = CraftStore(FileFolder(directory))

/** Saved pieces kept in [directory] on disk. */
fun AssemblyStore(directory: File): AssemblyStore = AssemblyStore(FileFolder(directory))
