package com.evoforge.agent

import org.springframework.stereotype.Component

@Component
class SystemInfoTool implements AgentTool {
    @Override
    String name() { 'system.info' }

    @Override
    String description() {
        'Return stable local runtime facts such as OS name, OS family, user home, working directory, username, locale, and path separator.'
    }

    @Override
    Map<String, Object> inputSchema() { [type: 'object', properties: [:]] }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        String osName = System.getProperty('os.name') ?: ''
        Map<String, Object> output = [
            osName       : osName,
            osFamily     : osFamily(osName),
            osVersion    : System.getProperty('os.version') ?: '',
            architecture : System.getProperty('os.arch') ?: '',
            userHome     : System.getProperty('user.home') ?: '',
            userName     : System.getProperty('user.name') ?: '',
            userDirectory: System.getProperty('user.dir') ?: '',
            fileSeparator: System.getProperty('file.separator') ?: '/',
            locale       : Locale.default.toLanguageTag()
        ]
        return AgentToolResult.ok(output)
    }

    private static String osFamily(String osName) {
        String value = osName.toLowerCase(Locale.ROOT)
        if (value.contains('mac')) return 'macos'
        if (value.contains('win')) return 'windows'
        if (value.contains('linux')) return 'linux'
        return value ?: 'unknown'
    }
}
