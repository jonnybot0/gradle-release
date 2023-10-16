package net.researchgate.release

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import org.gradle.tooling.events.task.TaskFailureResult
import org.gradle.tooling.events.task.TaskFinishEvent
import org.slf4j.LoggerFactory

abstract class CleanupBuildService implements BuildService<Params>, OperationCompletionListener {

    interface Params extends BuildServiceParameters {
        Property<BaseScmAdapter> getScmAdapter()
        Property<ReleaseExtension> getExtension()
        Property<Project> getProject()
    }

    @Override
    void onFinish(FinishEvent finishEvent) {
        if (finishEvent instanceof TaskFinishEvent) {
            def result = finishEvent.result
            if (result instanceof TaskFailureResult) {
                def extension = parameters.extension.get()
                def scmAdapter = parameters.scmAdapter.get()
                def project = parameters.project.get()
                def log = project.logger ?: LoggerFactory.getLogger(this.getClass())

                if (finishEvent.descriptor.name == "release") {
                    if (scmAdapter && extension.revertOnFail && project.file(extension.versionPropertyFile)?.exists()) {
                        log.error('Release process failed, reverting back any changes made by Release Plugin.')
                        scmAdapter.revert()
                    } else {
                        log.error('Release process failed, please remember to revert any uncommitted changes made by the Release Plugin.')
                    }
                }
            }
        }
    }
}
