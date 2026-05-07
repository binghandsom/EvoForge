package com.evoforge.device

class DeviceProtocol {
    static final String TYPE_NATURAL_LANGUAGE_TASK = 'natural_language_task'
    static final String TYPE_CODEX_TASK = 'codex_task'
    static final String TYPE_APPROVAL_DECISION = 'approval_decision'
    static final String TYPE_CLIENT_REQUEST = 'client_request'

    static final String DECISION_APPROVE = 'approve'
    static final String DECISION_REJECT = 'reject'

    static final String STATUS_QUEUED = 'queued'
    static final String STATUS_ACCEPTED = 'accepted'
    static final String STATUS_NEEDS_APPROVAL = 'needs_approval'
    static final String STATUS_APPROVED = 'approved'
    static final String STATUS_REJECTED = 'rejected'
    static final String STATUS_RUNNING = 'running'
    static final String STATUS_COMPLETED = 'completed'
    static final String STATUS_FAILED = 'failed'
    static final String STATUS_CLIENT_RESPONSE = 'client_response'
    static final String STATUS_AGENT_PROGRESS = 'agent_progress'

    static final String CAPABILITY_NATURAL_LANGUAGE_TASK = TYPE_NATURAL_LANGUAGE_TASK
    static final String CAPABILITY_CODEX_TASK = TYPE_CODEX_TASK
    static final String CAPABILITY_APPROVAL_DECISION = TYPE_APPROVAL_DECISION
    static final String CAPABILITY_SKILL_EXECUTION = 'skill_execution'
    static final String CAPABILITY_LLM_RESPONSE = 'llm_response'
    static final String CAPABILITY_PROGRESS_EVENTS = 'progress_events'
    static final String CAPABILITY_APPROVAL_REQUESTS = 'approval_requests'
    static final String CAPABILITY_CLIENT_REQUESTS = 'client_requests'

    static List<String> commandTypes() {
        return [
            TYPE_NATURAL_LANGUAGE_TASK,
            TYPE_CODEX_TASK,
            TYPE_APPROVAL_DECISION,
            TYPE_CLIENT_REQUEST
        ]
    }

    static boolean isCommandTypeSupported(String type) {
        return commandTypes().contains(type)
    }

    static List<String> capabilities() {
        return [
            CAPABILITY_NATURAL_LANGUAGE_TASK,
            CAPABILITY_CODEX_TASK,
            CAPABILITY_APPROVAL_DECISION,
            CAPABILITY_SKILL_EXECUTION,
            CAPABILITY_LLM_RESPONSE,
            CAPABILITY_PROGRESS_EVENTS,
            CAPABILITY_APPROVAL_REQUESTS,
            CAPABILITY_CLIENT_REQUESTS
        ]
    }
}
