// Jenkinsfile support utilities
import BuildConfig.BuildConfig
import org.apache.commons.lang3.SerializationUtils


// Clone the source repository and examine the most recent commit message.
// If a '[ci skip]' or '[skip ci]' directive is present, immediately
// terminate the job with a success code.
// If no skip directive is found, or skip_disable is true, stash all the
// source files for efficient retrieval by subsequent nodes.
def scm_checkout(skip_disable=false) {
    skip_job = 0
    node("on-master") {
        stage("Setup") {
            checkout(scm)
            if (!skip_disable) {
                // Obtain the last commit message and examine it for skip directives.
                logoutput = sh(script:"git log -1 --pretty=%B", returnStdout: true).trim()
                if (logoutput.contains("[ci skip]") || logoutput.contains("[skip ci]")) {
                    skip_job = 1
                    currentBuild.result = 'SUCCESS'
                    println("\nBuild skipped due to commit message directive.\n")
                    return skip_job
                }
            }
            stash includes: '**/*', name: 'source_tree', useDefaultExcludes: false
        }
    }
    return skip_job
}


// Execute build/test task(s) based on passed-in configuration(s).
// Each task is defined by a BuildConfig object.
// A list of such objects is iterated over to process all configurations.
//   Arguments:
//             configs    - list of BuildConfig objects
// (optional)  concurrent - boolean, whether or not to run all build
//                          configurations in parallel. The default is
//                          true when no value is provided.
def run(configs, concurrent = true) {
    def tasks = [:]

    for (config in configs) {

        def myconfig = new BuildConfig() // MUST be inside for loop.
        myconfig = SerializationUtils.clone(config)

        // Staged deprecation of BuildConfig.build_mode
        def warning// = false
        def both// = false
        if (myconfig.name) {
            if (myconfig.build_mode) {
                warning = true
                both = true
            }
            name = myconfig.name
        } else {
            if (myconfig.build_mode) {
                myconfig.name = myconfig.build_mode
                warning = true
            }
        }

        // Code defined within 'tasks' is eventually executed on a separate node.
        // 'tasks' is a java.util.LinkedHashMap, which preserves insertion order.
        tasks["${myconfig.nodetype}/${myconfig.name}"] = {
            node(config.nodetype) {

                // Staged deprecation of BuildConfig '.build_mode' nomenclature in favor of
                // '.name'.
                if (warning) {
                    println("WARNING: BuildConfig.build_mode will be deprecated in favor" +
                        " of .name in a future release of this support library. " +
                        "Please replace all instances of '.build_mode' in your " +
                        "Jenkinsfile with '.name'.")
                }
                if (both) {
                    println("WARNING: Both BuildConfig.build_mode (to be deprecated) and " +
                        " BuildConfig.name have been used in the Jenkinsfile. The " +
                        "assignment to '.name' will be used for this job. " +
                        "  ASSIGNMENT TO '.build_mode' IS BEING IGNORED!")
                }

                def runtime = []
                // If conda packages were specified, create an environment containing
                // them and then 'activate' it.
                if (myconfig.conda_packages.size() > 0) {
                    def env_name = "tmp_env"
                    def conda_exe = sh(script: "which conda", returnStdout: true).trim()
                    def conda_root = conda_exe.replace("/bin/conda", "").trim()
                    def conda_prefix = "${conda_root}/envs/${env_name}".trim()
                    def packages = ""
                    for (pkg in myconfig.conda_packages) {
                        packages = "${packages} ${pkg}"
                    }
                    // Override removes the implicit 'defaults' channel from the channels
                    // to be used, The conda_channels list is then used verbatim (in
                    // (priority order) by conda.
                    def override = ""
                    if (myconfig.conda_override_channels.toString() == 'true') {
                        override = "--override-channels"
                    }
                    def chans = ""
                    for (chan in myconfig.conda_channels) {
                        chans = "${chans} -c ${chan}"
                    }
                    sh(script: "conda create -q -y -n ${env_name} ${override} ${chans} ${packages}")
                    // Configure job to use this conda environment.
                    myconfig.env_vars.add(0, "CONDA_SHLVL=1")
                    myconfig.env_vars.add(0, "CONDA_PROMPT_MODIFIER=${env_name}")
                    myconfig.env_vars.add(0, "CONDA_EXE=${conda_exe}")
                    myconfig.env_vars.add(0, "CONDA_PREFIX=${conda_prefix}")
                    myconfig.env_vars.add(0, "CONDA_PYTHON_EXE=${conda_prefix}/bin/python")
                    myconfig.env_vars.add(0, "CONDA_DEFAULT_ENV=${env_name}")
                    // Prepend the PATH var adjustment to the list that gets processed below.
                    def conda_path = "PATH=${conda_prefix}/bin:$PATH"
                    myconfig.env_vars.add(0, conda_path)
                }
                // Expand environment variable specifications by using the shell
                // to dereference any var references and then render the entire
                // value as a canonical path.
                for (var in myconfig.env_vars) {
                    // Process each var in an environment defined by all the prior vars.
                    withEnv(runtime) {
                        def varName = var.tokenize("=")[0].trim()
                        def varValue = var.tokenize("=")[1].trim()
                        // examine var value, if it contains var refs, expand them.
                        def expansion = varValue
                        if (varValue.contains("\$")) {
                            expansion = sh(script: "echo \"${varValue}\"", returnStdout: true)
                        }

                        // Change values of '.' and './' to the node's WORKSPACE.
                        // Replace a leading './' with the node's WORKSPACE.
                        if (expansion == '.' || expansion == './') {
                            expansion = env.WORKSPACE
                        } else if(expansion.size() > 2 && expansion[0..1] == './') {
                            expansion = "${env.WORKSPACE}/${expansion[2..-1]}"
                        }

                        // Replace all ':.' combinations with the node's WORKSPACE.
                        expansion = expansion.replaceAll(':\\.', ":${env.WORKSPACE}")

                        // Convert var value to canonical based on a WORKSPACE base directory.
                        if (expansion.contains('..')) {
                            expansion = new File(expansion).getCanonicalPath()
                        }
                        expansion = expansion.trim()
                        runtime.add("${varName}=${expansion}")
                    } // end withEnv
                }
                for (var in myconfig.env_vars_raw) {
                    runtime.add(var)
                }
                withEnv(runtime) {
                    stage("Build (${myconfig.name})") {
                        unstash "source_tree"
                        for (cmd in myconfig.build_cmds) {
                            sh(script: cmd)
                        }
                    }
                    if (myconfig.test_cmds.size() > 0) {
                        try {
                            stage("Test (${myconfig.name})") {
                                for (cmd in myconfig.test_cmds) {
                                    sh(script: cmd)
                                }
                            }
                        }
                        finally {
                            // If a non-JUnit format .xml file exists in the
                            // root of the workspace, the XUnitBuilder report
                            // ingestion will fail.
                            report_exists = sh(script: "test -e *.xml", returnStatus: true)
                            if (report_exists == 0) {
                                step([$class: 'XUnitBuilder',
                                    thresholds: [
                                    [$class: 'SkippedThreshold', unstableThreshold: "${myconfig.skippedUnstableThresh}"],
                                    [$class: 'SkippedThreshold', failureThreshold: "${myconfig.skippedFailureThresh}"],
                                    [$class: 'FailedThreshold', unstableThreshold: "${myconfig.failedUnstableThresh}"],
                                    [$class: 'FailedThreshold', failureThreshold: "${myconfig.failedFailureThresh}"]],
                                    tools: [[$class: 'JUnitType', pattern: '*.xml']]])
                            } else {
                                println("No .xml files found in workspace. Test report ingestion skipped.")
                            }
                        }
                    }
                } // end withEnv
            } // end node
        } //end tasks

    } //end for

    if (concurrent == true) {
        stage("Matrix") {
            parallel(tasks)
        }
    } else {
        // Run tasks sequentially. Any failure halts the sequence.
        def iter = 0 
        tasks.each{ key, value ->
            def localtask = [:]
            localtask[key] = tasks[key]
            stage("Serial-${iter}") {
                parallel(localtask)
            }
            iter++
        }
    }
}


// Convenience function that performs a deep copy on the supplied object.
def copy(obj) {
    return SerializationUtils.clone(obj)
}
