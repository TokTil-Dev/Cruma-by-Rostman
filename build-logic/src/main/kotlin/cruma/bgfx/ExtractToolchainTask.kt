package cruma.bgfx

import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RelativePath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

abstract class ExtractToolchainTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archives: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun extract() {
        val target = outputDirectory.get().asFile
        val prepared = temporaryDir.resolve("sources")
        fileSystemOperations.sync {
            into(prepared)
            for (archive in archives.files.sortedBy { it.name }) {
                val name = archive.name.substringBefore('-')
                val tree = archiveOperations.tarTree(archiveOperations.gzip(archive))
                val subdirectory = if (name == "bgfxCmake") "" else name
                into(subdirectory) {
                    from(tree) {
                        includeEmptyDirs = false
                        eachFile {
                            val root = sourcePath.substringBefore('/')
                            val segments = relativePath.segments.toMutableList()
                            val index = segments.indexOf(root)
                            if (index < 0 || segments.size <= index + 1) {
                                exclude()
                            } else {
                                segments.removeAt(index)
                                relativePath = RelativePath(true, *segments.toTypedArray())
                            }
                        }
                    }
                }
            }
        }
        StableSourceTree.sync(prepared, target)
        fileSystemOperations.delete { delete(prepared) }
    }
}
