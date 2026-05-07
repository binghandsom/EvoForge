package com.evoforge.agent

import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path

@Component
class PathResolveTool implements AgentTool {
    @Override
    String name() { 'path.resolve' }

    @Override
    String description() {
        'Resolve a concrete filesystem path. Expands ~ and returns absolute normalized path plus existence/type metadata. It does not guess semantic names; use knowledge/search/system tools first when unsure.'
    }

    @Override
    Map<String, Object> inputSchema() {
        [
            type      : 'object',
            properties: [
                path: [type: 'string'],
                base: [type: 'string']
            ],
            required  : ['path']
        ]
    }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        String rawPath = args.path?.toString()?.trim()
        if (!rawPath) {
            return AgentToolResult.fail('path is required')
        }
        Path path = resolve(rawPath, args.base?.toString())
        Map<String, Object> output = [
            input       : rawPath,
            path        : path.toString(),
            exists      : Files.exists(path),
            directory   : Files.isDirectory(path),
            regularFile : Files.isRegularFile(path),
            readable    : Files.isReadable(path),
            writable    : Files.isWritable(path),
            parent      : path.parent?.toString()
        ]
        return AgentToolResult.ok(output)
    }

    static Path resolve(String rawPath, String rawBase = null) {
        String expanded = rawPath
        if (expanded == '~') {
            expanded = System.getProperty('user.home')
        } else if (expanded.startsWith('~/')) {
            expanded = System.getProperty('user.home') + expanded.substring(1)
        }
        Path path = Path.of(expanded)
        if (!path.absolute && rawBase) {
            path = Path.of(rawBase).resolve(path)
        }
        return path.toAbsolutePath().normalize()
    }
}
