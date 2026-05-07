class DeviceCommandType {
  static const naturalLanguageTask = 'natural_language_task';
  static const codexTask = 'codex_task';
  static const approvalDecision = 'approval_decision';
}

class DeviceApprovalDecision {
  static const approve = 'approve';
  static const reject = 'reject';
}

class DeviceTaskStatus {
  static const queued = 'queued';
  static const accepted = 'accepted';
  static const needsApproval = 'needs_approval';
  static const approved = 'approved';
  static const rejected = 'rejected';
  static const running = 'running';
  static const completed = 'completed';
  static const failed = 'failed';

  static bool isTerminal(String status) {
    return switch (status) {
      completed || failed || rejected || needsApproval => true,
      _ => false,
    };
  }

  static bool needsApprovalNow(String status) {
    return status == needsApproval;
  }
}
