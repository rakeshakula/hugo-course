package io.pivotal.hugocourse

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jp.classmethod.aws.gradle.AwsPluginExtension
import jp.classmethod.aws.gradle.s3.AmazonS3PluginExtension
import jp.classmethod.aws.gradle.s3.AmazonS3DeleteAllFilesTask
import jp.classmethod.aws.gradle.s3.BulkUploadTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Zip

class HugoCoursePlugin implements Plugin<Project> {
    String hugoCourseGroup = "Hugo course"

    @Override
    void apply(Project project) {

        project.extensions.create("hugoCourse", HugoCoursePluginExtension)
        project.extensions.create("aws", AwsPluginExtension, project)
        project.extensions.create("s3", AmazonS3PluginExtension, project)

        project.aws.profileName = "default"

        project.task('info') {
            description = "Print course info"
            group = hugoCourseGroup

            doLast {
                println "=" * 43
                println "=" * 15 + " Hugo Course " + "=" * 15
                println "=" * 43
                println "name: $project.hugoCourse.name"
                println "bucket: $project.hugoCourse.bucket"
                println "=" * 43
            }
        }

        def hugoContentDirectory = "$project.rootDir/hugo-content"
        def tmpDirectory = "$project.rootDir/tmp"
        def buildDirectory = "$project.rootDir/build"

        project.task("initSubmodules", type: Exec) {
            workingDir project.rootDir
            commandLine "git", "submodule", "init"
        }

        project.task("updateSubmodules", type: Exec, dependsOn: project.tasks.initSubmodules) {
            workingDir project.rootDir
            commandLine "git", "submodule", "update"
        }

        project.task("update", type: Exec, dependsOn: project.tasks.updateSubmodules) {
            description = "Update all submodules"
            group = hugoCourseGroup

            workingDir project.rootDir
            commandLine "git", "submodule", "foreach", "git", "pull", "--rebase"
        }

        project.task("cleanHugoContentDirectory", type: Delete) {
            delete hugoContentDirectory
        }

        project.task("createHugoContentDirectory",) {
            doLast {
                new File("$hugoContentDirectory/data").mkdirs()
            }
        }

        project.tasks.createHugoContentDirectory.mustRunAfter project.tasks.cleanHugoContentDirectory

        project.task("cleanHugoContent", dependsOn: [project.tasks.cleanHugoContentDirectory, project.tasks.createHugoContentDirectory])

        project.task("assembleHugoContent", dependsOn: project.tasks.cleanHugoContent) {
            doLast {
                def indexOfContentCollection = []
                def jsonSlurper = new JsonSlurper()

                def courseModules = jsonSlurper.parseText(project.file("$project.rootDir/modules.json").text)
                    .collect {
                    new File("$project.rootDir/modules/$it")
                }

                courseModules.each { courseModule ->
                    def indexJson = jsonSlurper.parseText(project.file("$courseModule.path/indexOfContent.json").text)
                    indexJson.prefix = courseModule.name
                    indexOfContentCollection.add(indexJson)

                    project.copy {
                        from "$courseModule.path/content"
                        into "$hugoContentDirectory/content/$courseModule.name"
                    }

                    project.copy {
                        from "$courseModule.path/static"
                        into "$hugoContentDirectory/static/$courseModule.name"
                    }
                }

                project.copy {
                    from "$project.rootDir/themes"
                    into "$hugoContentDirectory/themes"
                }

                project.copy {
                    from "$project.rootDir/static"
                    into "$hugoContentDirectory/static"
                }

                project.copy {
                    from "$project.rootDir/config.toml"
                    into hugoContentDirectory
                }

                project.copy {
                    from "$project.rootDir/index.md"
                    into "$hugoContentDirectory/content"
                }

                def indexOfContent = new File("$hugoContentDirectory/data/indexOfContent.json")

                indexOfContent.withWriter {
                    it.append JsonOutput.toJson(indexOfContentCollection)
                }
            }
        }

        project.task("serve", type: Exec, dependsOn: project.tasks.assembleHugoContent) {
            description = "Serve a local copy of the course"
            group = hugoCourseGroup

            workingDir hugoContentDirectory

            doFirst {
                environment "HUGO_BASEURL", "https://pivotal.io/$project.hugoCourse.name"
            }

            commandLine "hugo", "serve"
        }

        project.task("buildSite", type: Exec, dependsOn: project.tasks.assembleHugoContent) {
            workingDir hugoContentDirectory

            doFirst {
                environment "HUGO_BASEURL", "https://pivotal.io/$project.hugoCourse.name"
            }

            commandLine "hugo", "-d", tmpDirectory
        }

        project.task("cleanCourse", type: Delete) {
            description = "Remove local build files"
            group = hugoCourseGroup
            delete buildDirectory
        }

        project.task("copySite", type: Copy, dependsOn: [project.tasks.buildSite, project.tasks.cleanCourse]) {
            from tmpDirectory
            into buildDirectory
        }

        project.task("buildArchive", type: Zip, dependsOn: [project.tasks.buildSite, project.tasks.cleanCourse]) {
            from project.file(tmpDirectory)

            doFirst {
                into project.hugoCourse.name
            }

            include '**/*'
            destinationDir project.file(buildDirectory)

            baseName "course"
        }

        project.task("deleteTmp", type: Delete) {
            delete tmpDirectory
        }
        project.tasks.buildArchive.finalizedBy project.tasks.deleteTmp
        project.tasks.copySite.finalizedBy project.tasks.deleteTmp

        project.task("buildCourse", dependsOn: [project.tasks.copySite, project.tasks.buildArchive]) {
            description = "Build static site and create archive"
            group = hugoCourseGroup
        }

        project.task("remoteClean", type: AmazonS3DeleteAllFilesTask) {
            description = "Remove remote files"
            group = hugoCourseGroup


            doFirst {
                prefix project.hugoCourse.name
                bucketName project.hugoCourse.bucket
            }
        }

        project.task("remoteUpload", type: BulkUploadTask) {
            description = "Upload files in build directory to remote"
            group = hugoCourseGroup

            source = project.fileTree(dir: buildDirectory)

            doFirst {
                prefix project.hugoCourse.name
                bucketName project.hugoCourse.bucket
            }
        }

        project.tasks.remoteUpload.mustRunAfter project.tasks.remoteClean

        project.task("deploy", dependsOn: [project.tasks.cleanCourse, project.tasks.buildCourse, project.tasks.remoteClean, project.tasks.remoteUpload]) {
            description = "Deploy static site"
            group = hugoCourseGroup
        }

        project.tasks.deploy.mustRunAfter project.tasks.buildCourse
    }
}
