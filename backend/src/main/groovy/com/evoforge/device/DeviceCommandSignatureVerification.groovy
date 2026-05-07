package com.evoforge.device

class DeviceCommandSignatureVerification {
    boolean valid
    String reason

    static DeviceCommandSignatureVerification ok() {
        return new DeviceCommandSignatureVerification(valid: true)
    }

    static DeviceCommandSignatureVerification rejected(String reason) {
        return new DeviceCommandSignatureVerification(valid: false, reason: reason)
    }
}
