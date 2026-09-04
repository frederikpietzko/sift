package org.sift.plugins.detekt

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.Configurable
import org.jetbrains.amper.plugins.ModuleSources

@Configurable
interface CommonRunDetectSettings {
    val settings: Settings
    val sources: ModuleSources
    val moduleClasspath: Classpath
    val detektClasspath: Classpath
}