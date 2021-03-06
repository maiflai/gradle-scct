package org.scoverage

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ResolvedConfiguration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.scala.ScalaPlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.testing.Test

/**
 * Defines a new SourceSet for the code to be instrumented.
 * Defines a new Test Task which executes normal tests with the instrumented classes.
 * Defines a new Check Task which enforces an overall line coverage requirement.
 */
class ScoverageExtension {

    /** a directory to write working files to */
    File dataDir
    /** a directory to write final output to */
    File reportDir
    /** sources to highlight */
    File sources
    /** range positioning for highlighting */
    boolean highlighting = true

    ScoverageExtension(Project project) {

        project.plugins.apply(JavaPlugin.class);
        project.plugins.apply(ScalaPlugin.class);
        project.afterEvaluate(configureRuntimeOptions)

        project.configurations.create(ScoveragePlugin.CONFIGURATION_NAME) {
            visible = false
            transitive = true
            description = 'Scoverage dependencies'
        }

        project.sourceSets.create(ScoveragePlugin.CONFIGURATION_NAME) {
            def mainSourceSet = project.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

            java.source(mainSourceSet.java)
            scala.source(mainSourceSet.scala)

            compileClasspath += mainSourceSet.compileClasspath
            runtimeClasspath += mainSourceSet.runtimeClasspath
        }

        project.tasks.create(ScoveragePlugin.TEST_NAME, Test.class) {
            dependsOn(project.tasks[ScoveragePlugin.COMPILE_NAME])
        }

        project.tasks.create(ScoveragePlugin.REPORT_NAME, JavaExec.class) {
            dependsOn(project.tasks[ScoveragePlugin.TEST_NAME])
        }

        project.tasks.create(ScoveragePlugin.CHECK_NAME, OverallCheckTask.class) {
            dependsOn(project.tasks[ScoveragePlugin.REPORT_NAME])
        }

        dataDir = new File(project.buildDir, 'scoverage')
        reportDir = new File(project.buildDir, 'reports' + File.separatorChar + 'scoverage')
    }

    private Action<Project> configureRuntimeOptions = new Action<Project>() {

        @Override
        void execute(Project t) {

            def extension = ScoveragePlugin.extensionIn(t)
            extension.sources = t.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME).scala.srcDirs.iterator().next() as File
            extension.dataDir.mkdirs()
            extension.reportDir.mkdirs()

            ResolvedConfiguration s = t.configurations[ScoveragePlugin.CONFIGURATION_NAME].resolvedConfiguration
            String pluginPath = s.getFirstLevelModuleDependencies().iterator().next().moduleArtifacts.iterator().next().file.absolutePath

            t.tasks[ScoveragePlugin.COMPILE_NAME].configure {


                List<String> plugin = ['-Xplugin:' + pluginPath]
                List<String> parameters = scalaCompileOptions.additionalParameters
                if (parameters != null) {
                    plugin.addAll(parameters)
                }
                plugin.add("-P:scoverage:dataDir:${extension.dataDir.absolutePath}".toString())
                plugin.add('-P:scoverage:excludedPackages:')
                if (extension.highlighting) {
                    plugin.add('-Yrangepos')
                }
                scalaCompileOptions.additionalParameters = plugin
                // exclude the scala libraries that are added to enable scala version detection
                classpath += t.configurations[ScoveragePlugin.CONFIGURATION_NAME]
            }

            t.tasks[ScoveragePlugin.TEST_NAME].configure {
                def existingClasspath = classpath
                classpath = t.files(t.sourceSets[ScoveragePlugin.CONFIGURATION_NAME].output.classesDir) +
                        project.configurations[ScoveragePlugin.CONFIGURATION_NAME] +
                        existingClasspath
            }

            t.tasks[ScoveragePlugin.REPORT_NAME].configure {
                classpath = project.buildscript.configurations.classpath +
                        project.configurations[ScoveragePlugin.CONFIGURATION_NAME]
                main = 'org.scoverage.ScoverageReport'
                args = [
                        extension.sources,
                        extension.dataDir.absolutePath,
                        extension.reportDir.absolutePath
                ]
                inputs.dir(extension.dataDir)
                outputs.dir(extension.reportDir)
            }

        }
    }

}
