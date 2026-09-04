package org.sift.crds

import io.fabric8.crd.generator.collector.CustomResourceCollector
import io.fabric8.crdv2.generator.CRDGenerator
import org.jetbrains.amper.plugins.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

@TaskAction
fun generateCrds(
    @Input compilationArtifact: CompilationArtifact,
    @Input compileClasspath: Classpath,
    @Output outputDir: Path,
) {
    val outputParent = requireNotNull(outputDir.parent)
    outputParent.createDirectories()
    val temporaryOutputDir =
        Files.createTempDirectory(
            outputParent,
            "${outputDir.fileName}-",
        )

    try {
        val compiledArtifact = compilationArtifact.artifact
        val classpathElements =
            (compileClasspath.resolvedFiles + listOf(compiledArtifact))
                .distinct()
                .map(Path::toString)

        val collector =
            CustomResourceCollector()
                .withParentClassLoader(Thread.currentThread().contextClassLoader)
                .withClasspathElements(classpathElements)
                .withFilesToScan(listOf(compiledArtifact.toFile()))

        val customResourceClasses = collector.findCustomResourceClasses()
        check(customResourceClasses.isNotEmpty()) {
            "No Fabric8 custom resources found in ${compiledArtifact.fileName}"
        }

        val generationInfo =
            CRDGenerator()
                .customResourceClasses(customResourceClasses)
                .inOutputDir(temporaryOutputDir.toFile())
                .detailedGenerate()

        outputDir.createDirectories()
        syncGeneratedFiles(temporaryOutputDir, outputDir)

        generationInfo.crdDetailsPerNameAndVersion.forEach { [crdName, versionToInfo] ->
            println("Generated CRD $crdName:")
            versionToInfo.forEach { [version, info] ->
                println(" $version -> ${outputDir.resolve(Path.of(info.filePath).fileName)}")
            }
        }
    } finally {
        temporaryOutputDir.toFile().deleteRecursively()
    }
}

private fun syncGeneratedFiles(sourceDir: Path, targetDir: Path) {
    val sourceFiles =
        sourceDir.toFile().walkTopDown().filter(File::isFile).map(File::toPath).toList()
    val generatedPaths = sourceFiles.map(sourceDir::relativize).toSet()

    targetDir.toFile().walkTopDown().filter(File::isFile).map(File::toPath).forEach { targetFile ->
        if (targetDir.relativize(targetFile) !in generatedPaths) {
            Files.deleteIfExists(targetFile)
        }
    }

    sourceFiles.forEach { sourceFile ->
        val targetFile = targetDir.resolve(sourceDir.relativize(sourceFile))
        targetFile.parent.createDirectories()
        if (!Files.exists(targetFile) || Files.mismatch(sourceFile, targetFile) != -1L) {
            Files.copy(sourceFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
