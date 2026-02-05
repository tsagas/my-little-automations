#!groovy
@Library('hf-shared-library') _
import groovy.json.JsonSlurperClassic
import groovy.json.JsonOutput

def gitlabApi(String endpoint) {
    withCredentials([string(credentialsId: "${getLocalConfig()['gitlab_instances']['gitlab']['credentials_id']}", variable: 'api_token')]) {
        def response = sh(returnStdout: true, script: "curl -s --header \"PRIVATE-TOKEN: \${api_token}\" \"${getLocalConfig()['gitlab_instances']['gitlab']['url']}/api/v4${endpoint}\"")
        return (response && response.trim() != '' && response.trim() != 'null') ? new JsonSlurperClassic().parseText(response) : null
    }
}

def resolveTargets(String target) {
    def projectIds = []
    target.split(',').each { t ->
        t = t.trim()
        def resolved = false
        
        // Try as group first
        try {
            def groupId = t.isNumber() ? t : gitlabApi("/groups/${URLEncoder.encode(t, 'UTF-8')}")?.id?.toString()
            if (groupId) {
                def projects = gitlabApi("/groups/${groupId}/projects?per_page=100")
                if (projects instanceof List && projects.size() > 0) {
                    projectIds.addAll(projects.collect { it.id.toString() })
                    println "[INFO] Resolved group '${t}' to ${projects.size()} projects"
                    resolved = true
                }
            }
        } catch (Exception e) {
            // Not a group, will try as project
        }
        
        // Try as project if group failed
        if (!resolved) {
            try {
                def projectId = t.isNumber() ? t : gitlabApi("/projects/${URLEncoder.encode(t, 'UTF-8')}")?.id?.toString()
                if (projectId) {
                    projectIds.add(projectId)
                    println "[INFO] Resolved project '${t}' to ID ${projectId}"
                } else {
                    println "[WARN] Could not resolve '${t}'"
                }
            } catch (Exception ex) {
                println "[WARN] Could not resolve '${t}': ${ex.message}"
            }
        }
    }
    return projectIds.unique()
}

def gitlabApiCall(String endpoint, String method, Map data = null) {
    withCredentials([string(credentialsId: "${getLocalConfig()['gitlab_instances']['gitlab']['credentials_id']}", variable: 'api_token')]) {
        def url = "${getLocalConfig()['gitlab_instances']['gitlab']['url']}/api/v4${endpoint}"
        if (data) {
            writeFile file: 'payload.json', text: JsonOutput.toJson(data)
            return sh(returnStdout: true, script: "curl -s --header \"PRIVATE-TOKEN: \${api_token}\" -X ${method} \"${url}\" -H 'Content-Type: application/json' -d @payload.json")
        }
        return sh(returnStdout: true, script: "curl -s --header \"PRIVATE-TOKEN: \${api_token}\" -X ${method} \"${url}\"")
    }
}

def updateConfig(String projectId, String type, Map config, boolean dryRun) {
    def current = gitlabApi(type == 'push_rule' ? "/projects/${projectId}/push_rule" : "/projects/${projectId}")
    def isNew = !current || current.isEmpty()
    def changes = isNew ? config : config.findAll { k, v -> current[k] != v }
    if (!changes) { println "[${projectId}] No changes"; return }
    
    if (dryRun) {
        println "[${projectId}] DRY RUN - Would ${isNew ? 'create' : 'update'}:"
        changes.each { k, v -> println "  ${k}: ${current[k]} -> ${v}" }
    } else {
        gitlabApiCall("/projects/${projectId}${type == 'push_rule' ? '/push_rule' : ''}", (type == 'push_rule' && isNew) ? 'POST' : 'PUT', config)
        println "[${projectId}] Updated"
    }
}

pipeline {
    agent any
    stages {
        stage('Update Projects') {
            steps {
                script {
                    def input1 = input(message: 'Configure GitLab Settings', parameters: [
                        string(name: 'TARGET', defaultValue: '', description: 'Groups/Projects (comma-separated). LIMITATION: If a group or more than 1 projects are defines, there will be a blind bulk operation without showing the existing settings'),
                        booleanParam(name: 'READ_ONLY', defaultValue: false, description: 'Read-only mode (If checked, diting changes in next screen will not persist)')
                    ])
                    
                    if (!input1.TARGET) error('TARGET required')
                    def projectIds = resolveTargets(input1.TARGET)
                    println "[INFO] Resolved ${projectIds.size()} project(s): ${projectIds}"
                    if (!projectIds) error('No projects found')
                    
                    def currentPush = projectIds.size() == 1 ? gitlabApi("/projects/${projectIds[0]}/push_rule") : null
                    def currentMr = projectIds.size() == 1 ? gitlabApi("/projects/${projectIds[0]}") : null
                    
                    def settings = input(message: input1.READ_ONLY ? 'View GitLab Settings (Read-Only)' : 'Configure GitLab Settings', parameters: [
                        booleanParam(name: 'REJECT_UNVERIFIED_USERS', defaultValue: currentPush?.commit_committer_check ?: false, description: '--- PUSH RULES START HERE--- Reject unverified users'),
                        booleanParam(name: 'REJECT_INCONSISTENT_USER_NAME', defaultValue: currentPush?.commit_committer_name_check ?: false, description: 'Reject inconsistent user name'),
                        booleanParam(name: 'REJECT_UNSIGNED_COMMITS', defaultValue: currentPush?.reject_unsigned_commits ?: false, description: 'Reject unsigned commits'),
                        booleanParam(name: 'REJECT_NON_DCO_COMMITS', defaultValue: currentPush?.reject_non_dco_commits ?: false, description: 'Reject non-DCO commits'),
                        booleanParam(name: 'DENY_DELETE_TAG', defaultValue: currentPush?.deny_delete_tag ?: false, description: 'Deny delete tag'),
                        booleanParam(name: 'PREVENT_SECRETS', defaultValue: currentPush?.prevent_secrets ?: false, description: 'Prevent secrets'),
                        booleanParam(name: 'CHECK_AUTHOR_IS_GITLAB_USER', defaultValue: currentPush?.member_check ?: false, description: 'Check author is GitLab user'),
                        string(name: 'COMMIT_MESSAGE_REGEX', defaultValue: currentPush?.commit_message_regex ?: '', description: 'Commit message regex'),
                        string(name: 'COMMIT_MESSAGE_NEGATIVE_REGEX', defaultValue: currentPush?.commit_message_negative_regex ?: '', description: 'Commit message negative regex'),
                        string(name: 'BRANCH_NAME_REGEX', defaultValue: currentPush?.branch_name_regex ?: '', description: 'Branch name regex'),
                        string(name: 'AUTHOR_EMAIL_REGEX', defaultValue: currentPush?.author_email_regex ?: '', description: 'Author email regex'),
                        string(name: 'FILE_NAME_REGEX', defaultValue: currentPush?.file_name_regex ?: '', description: 'File name regex'),
                        string(name: 'MAX_FILE_SIZE', defaultValue: currentPush?.max_file_size?.toString() ?: '0', description: '--- PUSH RULES END HERE--- Max file size MB'),
                        choice(name: 'MERGE_METHOD', choices: [currentMr?.merge_method ?: 'merge', 'merge', 'rebase_merge', 'ff'], description: '--- MERGE REQUEST SETTINGS START HERE--- Merge method'),
                        choice(name: 'SQUASH_OPTION', choices: [currentMr?.squash_option ?: 'default_off', 'never', 'default_off', 'default_on', 'always'], description: 'Squash option'),
                        booleanParam(name: 'PIPELINE_MUST_SUCCEED', defaultValue: currentMr?.only_allow_merge_if_pipeline_succeeds ?: false, description: 'Pipelines must succeed'),
                        booleanParam(name: 'SKIPPED_PIPELINES_SUCCEED', defaultValue: currentMr?.allow_merge_on_skipped_pipeline ?: false, description: 'Skipped pipelines are considered successful'),
                        booleanParam(name: 'ALL_DISCUSSIONS_RESOLVED', defaultValue: currentMr?.only_allow_merge_if_all_discussions_are_resolved ?: false, description: 'All threads must be resolved'),
                        booleanParam(name: 'STATUS_CHECKS_MUST_SUCCEED', defaultValue: currentMr?.only_allow_merge_if_all_status_checks_passed ?: false, description: 'Status checks must succeed'),
                        booleanParam(name: 'DELETE_SOURCE_BRANCH', defaultValue: currentMr?.remove_source_branch_after_merge ?: false, description: 'Enable delete source branch by default'),
                        booleanParam(name: 'AUTO_CANCEL_PIPELINES', defaultValue: currentMr?.auto_cancel_pending_pipelines == 'enabled', description: 'Auto-cancel pipelines'),
                        booleanParam(name: 'SHOW_MR_LINK', defaultValue: currentMr?.printing_merge_request_link_enabled ?: false, description: 'Show link to create/view MR when pushing'),
                        booleanParam(name: 'RESOLVE_OUTDATED_DISCUSSIONS', defaultValue: currentMr?.resolve_outdated_diff_discussions ?: false, description: 'Automatically resolve outdated diff threads'),
                        booleanParam(name: 'MERGE_PIPELINES_ENABLED', defaultValue: currentMr?.merge_pipelines_enabled ?: false, description: 'Enable merged results pipelines'),
                        booleanParam(name: 'MERGE_TRAINS_ENABLED', defaultValue: currentMr?.merge_trains_enabled ?: false, description: 'Enable merge trains'),
                        booleanParam(name: 'MERGE_TRAINS_SKIP_TRAIN_ALLOWED', defaultValue: currentMr?.merge_trains_skip_train_allowed ?: false, description: '--- MERGE REQUEST SETTINGS START HERE--- Allow skipping the merge train')
                    ])
                    
                    if (input1.READ_ONLY) return
                    
                    projectIds.each { projectId ->
                        println "[INFO] Processing project ${projectId}"
                        def pushConfig = [
                            commit_committer_check: settings.REJECT_UNVERIFIED_USERS,
                            commit_committer_name_check: settings.REJECT_INCONSISTENT_USER_NAME,
                            reject_unsigned_commits: settings.REJECT_UNSIGNED_COMMITS,
                            reject_non_dco_commits: settings.REJECT_NON_DCO_COMMITS,
                            deny_delete_tag: settings.DENY_DELETE_TAG,
                            member_check: settings.CHECK_AUTHOR_IS_GITLAB_USER,
                            prevent_secrets: settings.PREVENT_SECRETS,
                            max_file_size: settings.MAX_FILE_SIZE.toInteger()
                        ]
                        if (settings.COMMIT_MESSAGE_REGEX) pushConfig.commit_message_regex = settings.COMMIT_MESSAGE_REGEX
                        if (settings.COMMIT_MESSAGE_NEGATIVE_REGEX) pushConfig.commit_message_negative_regex = settings.COMMIT_MESSAGE_NEGATIVE_REGEX
                        if (settings.BRANCH_NAME_REGEX) pushConfig.branch_name_regex = settings.BRANCH_NAME_REGEX
                        if (settings.AUTHOR_EMAIL_REGEX) pushConfig.author_email_regex = settings.AUTHOR_EMAIL_REGEX
                        if (settings.FILE_NAME_REGEX) pushConfig.file_name_regex = settings.FILE_NAME_REGEX
                        updateConfig(projectId, 'push_rule', pushConfig, false)
                        
                        def mrConfig = [
                            merge_method: settings.MERGE_METHOD,
                            squash_option: settings.SQUASH_OPTION,
                            only_allow_merge_if_pipeline_succeeds: settings.PIPELINE_MUST_SUCCEED,
                            allow_merge_on_skipped_pipeline: settings.SKIPPED_PIPELINES_SUCCEED,
                            only_allow_merge_if_all_discussions_resolved: settings.ALL_DISCUSSIONS_RESOLVED,
                            only_allow_merge_if_all_status_checks_passed: settings.STATUS_CHECKS_MUST_SUCCEED,
                            remove_source_branch_after_merge: settings.DELETE_SOURCE_BRANCH,
                            auto_cancel_pending_pipelines: settings.AUTO_CANCEL_PIPELINES ? 'enabled' : 'disabled',
                            printing_merge_request_link_enabled: settings.SHOW_MR_LINK,
                            resolve_outdated_diff_discussions: settings.RESOLVE_OUTDATED_DISCUSSIONS,
                            merge_pipelines_enabled: settings.MERGE_PIPELINES_ENABLED,
                            merge_trains_enabled: settings.MERGE_TRAINS_ENABLED,
                            merge_trains_skip_train_allowed: settings.MERGE_TRAINS_SKIP_TRAIN_ALLOWED
                        ]
                        updateConfig(projectId, 'project', mrConfig, false)
                        sh 'sleep 0.1'
                    }
                }
            }
        }
    }
}
