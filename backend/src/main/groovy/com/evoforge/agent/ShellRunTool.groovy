package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import org.springframework.stereotype.Component

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@Component
class ShellRunTool implements AgentTool {
    private final EvoForgeProperties properties

    ShellRunTool(EvoForgeProperties properties) {
        this.properties = properties
    }

    @Override
    String name() { 'shell.run' }

    @Override
    String description() {
        'Run a local command as an argv array. No command allowlist is enforced; the executor only applies the shell enable switch and timeout.'
    }

    @Override
    Map<String, Object> inputSchema() {
        [
            type      : 'object',
            properties: [
                argv   : [type: 'array', items: [type: 'string']],
                cwd    : [type: 'string'],
                timeout: [type: 'integer', minimum: 1, maximum: 60]
            ],
            required  : ['argv']
        ]
    }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        if (!properties.agent.shellEnabled) {
            return AgentToolResult.fail('shell.run is disabled by configuration')
        }
        List<String> argv = listArg(args.argv)
        if (argv.isEmpty()) {
            return AgentToolResult.fail('argv is required')
        }

        int timeout = Math.max(1, Math.min(intArg(args.timeout, properties.agent.shellTimeoutSeconds), 60))
        ProcessBuilder builder = new ProcessBuilder(argv)
        if (args.cwd) {
            builder.directory(PathResolveTool.resolve(args.cwd.toString()).toFile())
        }
        builder.redirectErrorStream(true)
        Process process = builder.start()
        boolean exited = process.waitFor(timeout, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            return AgentToolResult.fail("Command timed out after ${timeout}s".toString(), [argv: argv])
        }
        String output = process.inputStream.getText(StandardCharsets.UTF_8.name())
        int maxLength = 12000
        if (output.length() > maxLength) {
            output = output.take(maxLength) + '\n[truncated]'
        }
        Map<String, Object> payload = [
            argv    : argv,
            exitCode: process.exitValue(),
            output  : output
        ] as Map<String, Object>
        int exitCode = process.exitValue()
        if (exitCode != 0) {
            return new AgentToolResult(
                success: false,
                output: payload,
                error: "Command exited with code ${exitCode}".toString(),
                meta: [exitCode: exitCode]
            )
        }
        return AgentToolResult.ok(payload, [exitCode: exitCode])
    }

    private static List<String> listArg(Object value) {
        if (value instanceof Collection) {
            return value.collect { it.toString() }.findAll { it }
        }
        return []
    }

    private static int intArg(Object value, int fallback) {
        return value instanceof Number ? value.intValue() : value ? value.toString().toInteger() : fallback
    }
}
