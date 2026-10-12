package io.github.intramuros.weatherbuddy.data

import java.io.File

/** Writes the file through a temporary one, so readers never see it half-written. */
internal fun File.writeAtomically(write: (File) -> Unit) {
    val tmp = File(parentFile, "$name.tmp")
    write(tmp)
    if (!tmp.renameTo(this)) {
        tmp.delete()
        error("could not replace $this")
    }
}
