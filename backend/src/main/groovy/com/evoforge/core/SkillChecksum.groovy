package com.evoforge.core

import java.security.MessageDigest

class SkillChecksum {
    static String sha256(String text) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        byte[] hash = digest.digest((text ?: '').getBytes('UTF-8'))
        return hash.collect { String.format('%02x', it) }.join()
    }
}
