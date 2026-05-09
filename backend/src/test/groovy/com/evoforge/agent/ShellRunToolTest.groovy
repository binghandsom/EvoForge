package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class ShellRunToolTest {

    @Test
    void runsCommandsWithoutAllowedCommandConfiguration() {
        ShellRunTool tool = new ShellRunTool(new EvoForgeProperties())

        AgentToolResult result = tool.execute([
            argv: ['sh', '-lc', 'printf unrestricted-shell']
        ] as Map<String, Object>, new AgentToolContext())

        assertTrue(result.success)
        assertEquals(0, result.meta.exitCode)
        assertEquals('unrestricted-shell', result.output.output)
    }

    @Test
    void preservesOutputForNonZeroExitCodes() {
        ShellRunTool tool = new ShellRunTool(new EvoForgeProperties())

        AgentToolResult result = tool.execute([
            argv: ['sh', '-lc', 'printf failed-output; exit 7']
        ] as Map<String, Object>, new AgentToolContext())

        assertFalse(result.success)
        assertEquals(7, result.meta.exitCode)
        assertEquals('failed-output', result.output.output)
        assertTrue(result.error.contains('7'))
    }
}
