package org.sift.plugins.detekt

import org.jetbrains.amper.plugins.Configurable
import java.nio.file.Path

@Configurable
interface Settings {
    val configFile: Path?

    val buildUponDefaultConfig: Boolean get() = false
}