package com.collage.app.model

import java.io.File

/** Writes through a temp file and a rename, so a crash never leaves a half-written file behind. */
fun File.writeTextAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    check(tmp.renameTo(this)) { "Could not write $name" }
}
