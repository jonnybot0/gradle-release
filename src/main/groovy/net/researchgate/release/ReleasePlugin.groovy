/*
 * This file is part of the gradle-release plugin.
 *
 * (c) Eric Berry
 * (c) Dennis Schumann
 * (c) ResearchGate GmbH
 *
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 */

package net.researchgate.release

import net.researchgate.release.tasks.CheckCommitNeeded
import net.researchgate.release.tasks.CheckSnapshotDependencies
import net.researchgate.release.tasks.CheckUpdateNeeded
import net.researchgate.release.tasks.CheckoutAndMergeToReleaseBranch
import net.researchgate.release.tasks.CheckoutMergeFromReleaseBranch
import net.researchgate.release.tasks.CommitNewVersion
import net.researchgate.release.tasks.ConfirmReleaseVersion
import net.researchgate.release.tasks.CreateReleaseTag
import net.researchgate.release.tasks.InitScmAdapter
import net.researchgate.release.tasks.PreTagCommit
import net.researchgate.release.tasks.PrepareVersions
import net.researchgate.release.tasks.UnSnapshotVersion
import net.researchgate.release.tasks.UpdateVersion
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.GradleBuild
import org.gradle.build.event.BuildEventsListenerRegistry

import javax.inject.Inject

abstract class ReleasePlugin extends PluginHelper implements Plugin<Project> {

    static final String RELEASE_GROUP = 'Release'

    private BaseScmAdapter scmAdapter

    @Inject
    abstract BuildEventsListenerRegistry getBuildEventsListenerRegistry()

    void apply(Project project) {
        if (!project.plugins.hasPlugin(BasePlugin.class)) {
            project.plugins.apply(BasePlugin.class)
        }
        this.project = project
        extension = project.extensions.create('release', ReleaseExtension, project, attributes)

        String preCommitText = findProperty('release.preCommitText', null, 'preCommitText')
        if (preCommitText) {
            extension.preCommitText.convention(preCommitText)
        }

        // name tasks with an absolute path so subprojects can be released independently
        String p = project.path
        p = !p.endsWith(Project.PATH_SEPARATOR) ? p + Project.PATH_SEPARATOR : p

        project.task('release', description: 'Verify project, release, and update version to next.', group: RELEASE_GROUP, type: GradleBuild) {
            startParameter = project.getGradle().startParameter.newInstance()

            tasks = [
                "${p}createScmAdapter" as String,
                "${p}initScmAdapter" as String,
                "${p}checkCommitNeeded" as String,
                "${p}checkUpdateNeeded" as String,
                "${p}checkoutMergeToReleaseBranch" as String,
                "${p}unSnapshotVersion" as String,
                "${p}confirmReleaseVersion" as String,
                "${p}checkSnapshotDependencies" as String,
                "${p}runBuildTasks" as String,
                "${p}preTagCommit" as String,
                "${p}createReleaseTag" as String,
                "${p}checkoutMergeFromReleaseBranch" as String,
                "${p}updateVersion" as String,
                "${p}commitNewVersion" as String
            ]
            
            // Gradle 6 workaround (https://github.com/gradle/gradle/issues/12872)
            buildName = project.name + "-release"
        }

        project.task('beforeReleaseBuild', group: RELEASE_GROUP,
                description: 'Runs immediately before the build when doing a release') {}
        project.task('afterReleaseBuild', group: RELEASE_GROUP,
                description: 'Runs immediately after the build when doing a release') {}
        project.task('createScmAdapter', group: RELEASE_GROUP,
                description: 'Finds the correct SCM plugin') doLast this.&createScmAdapter

        project.tasks.create('initScmAdapter', InitScmAdapter)
        project.tasks.create('checkCommitNeeded', CheckCommitNeeded.class)
        project.tasks.create('checkUpdateNeeded', CheckUpdateNeeded.class)
        project.tasks.create('prepareVersions', PrepareVersions.class)
        project.tasks.create('checkoutMergeToReleaseBranch', CheckoutAndMergeToReleaseBranch.class) {
            onlyIf {
                extension.pushReleaseVersionBranch.isPresent()
            }
        }
        project.tasks.create('unSnapshotVersion', UnSnapshotVersion.class)
        project.tasks.create('confirmReleaseVersion', ConfirmReleaseVersion.class)
        project.tasks.create('checkSnapshotDependencies', CheckSnapshotDependencies.class)
        project.tasks.create('runBuildTasks', GradleBuild) {
            group: RELEASE_GROUP
            description: 'Runs the build process in a separate gradle run.'
            startParameter = project.getGradle().startParameter.newInstance()
            startParameter.projectProperties.putAll(project.getGradle().startParameter.projectProperties)
            startParameter.projectProperties.put('release.releasing', "true")
            startParameter.projectDir = project.projectDir
            startParameter.settingsFile = project.getGradle().startParameter.settingsFile
            startParameter.gradleUserHomeDir = project.getGradle().startParameter.gradleUserHomeDir
            buildName = project.name

            project.afterEvaluate {
                tasks = [
                        "${p}beforeReleaseBuild" as String,
                        extension.buildTasks.get().collect { it },
                        "${p}afterReleaseBuild" as String
                ].flatten()
            }
            
            // Gradle 6 workaround (https://github.com/gradle/gradle/issues/12872)
            buildName = project.name + "-release"
        }
        project.tasks.create('preTagCommit', PreTagCommit.class)
        project.tasks.create('createReleaseTag', CreateReleaseTag.class)
        project.tasks.create('checkoutMergeFromReleaseBranch', CheckoutMergeFromReleaseBranch) {
            onlyIf {
                extension.pushReleaseVersionBranch.isPresent()
            }
        }
        project.tasks.create('updateVersion', UpdateVersion.class)
        project.tasks.create('commitNewVersion', CommitNewVersion.class)

        project.tasks.initScmAdapter.dependsOn(project.tasks.createScmAdapter)
        project.tasks.checkCommitNeeded.dependsOn(project.tasks.initScmAdapter)
        project.tasks.checkUpdateNeeded.dependsOn(project.tasks.initScmAdapter)
        project.tasks.checkUpdateNeeded.mustRunAfter(project.tasks.checkCommitNeeded)
        project.tasks.checkoutMergeToReleaseBranch.dependsOn(project.tasks.initScmAdapter)
        project.tasks.checkoutMergeToReleaseBranch.mustRunAfter(project.tasks.checkUpdateNeeded)
        project.tasks.unSnapshotVersion.mustRunAfter(project.tasks.checkoutMergeToReleaseBranch)
        project.tasks.confirmReleaseVersion.mustRunAfter(project.tasks.unSnapshotVersion)
        project.tasks.checkSnapshotDependencies.mustRunAfter(project.tasks.confirmReleaseVersion)
        project.tasks.runBuildTasks.mustRunAfter(project.tasks.checkSnapshotDependencies)
        project.tasks.preTagCommit.dependsOn(project.tasks.initScmAdapter)
        project.tasks.preTagCommit.mustRunAfter(project.tasks.runBuildTasks)
        project.tasks.createReleaseTag.dependsOn(project.tasks.initScmAdapter)
        project.tasks.createReleaseTag.mustRunAfter(project.tasks.preTagCommit)
        project.tasks.preTagCommit.dependsOn(project.tasks.initScmAdapter)
        project.tasks.checkoutMergeFromReleaseBranch.mustRunAfter(project.tasks.createReleaseTag)
        project.tasks.updateVersion.mustRunAfter(project.tasks.checkoutMergeFromReleaseBranch)
        project.tasks.commitNewVersion.dependsOn(project.tasks.initScmAdapter)
        project.tasks.commitNewVersion.mustRunAfter(project.tasks.updateVersion)

        project.afterEvaluate {
            def buildTasks = extension.buildTasks.get()
            if (!buildTasks.empty) {
                project.tasks.findByPath(buildTasks.first()).mustRunAfter(project.tasks.beforeReleaseBuild)
                project.tasks.afterReleaseBuild.mustRunAfter(project.tasks.findByPath(buildTasks.last()))
            }
        }

        def cleanupServiceProvider = project.gradle.sharedServices
                .registerIfAbsent('cleanupBuildService', CleanupBuildService) {
                    it.parameters.extension.set(extension)
                    it.parameters.project.set(project)
                    it.parameters.scmAdapter.set(project.provider {
                        createScmAdapter()
                        return scmAdapter
                    })
                }
        buildEventsListenerRegistry.onTaskCompletion(cleanupServiceProvider)
    }

    void createScmAdapter() {
        scmAdapter = findScmAdapter()
        extension.scmAdapter = scmAdapter
    }

    void checkUpdateNeeded() {
        scmAdapter.checkUpdateNeeded()
    }

    /**
     * Recursively look for the type of the SCM we are dealing with, if no match is found look in parent directory
     * @param directory the directory to start from
     */
    protected BaseScmAdapter findScmAdapter() {
        BaseScmAdapter adapter
        File projectPath = project.projectDir.canonicalFile

        extension.scmAdapters.find {
            assert BaseScmAdapter.isAssignableFrom(it)

            BaseScmAdapter instance = it.getConstructor(Project.class, Map.class).newInstance(project, attributes)
            if (instance.isSupported(projectPath)) {
                adapter = instance
                return true
            }

            return false
        }

        if (adapter == null) {
            throw new GradleException(
                "No supported Adapter could be found. Are [${ projectPath }] or its parents are valid scm directories?")
        }

        adapter
    }
}
