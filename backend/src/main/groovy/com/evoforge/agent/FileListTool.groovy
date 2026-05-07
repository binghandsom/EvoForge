package com.evoforge.agent

import com.evoforge.core.EvoForgeProperties
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

@Component
class FileListTool implements AgentTool {
    private final EvoForgeProperties properties

    FileListTool(EvoForgeProperties properties) {
        this.properties = properties
    }

    @Override
    String name() { 'file.list' }

    @Override
    String description() {
        'List files in a concrete directory, optionally recursively and by extension. Returns metadata only, not file contents.'
    }

    @Override
    Map<String, Object> inputSchema() {
        [
            type      : 'object',
            properties: [
                path      : [type: 'string'],
                recursive : [type: 'boolean'],
                extensions: [type: 'array', items: [type: 'string']],
                limit     : [type: 'integer', minimum: 1, maximum: 1000]
            ],
            required  : ['path']
        ]
    }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        Path root = PathResolveTool.resolve(args.path?.toString() ?: '')
        if (!Files.isDirectory(root)) {
            return AgentToolResult.fail("Directory does not exist: ${root}".toString(), [path: root.toString()])
        }
        boolean recursive = args.recursive == true
        Set<String> extensions = extensionSet(args.extensions)
        int limit = Math.max(1, Math.min(intArg(args.limit, properties.agent.fileListLimit), 1000))
        List<Map<String, Object>> entries = []
        def stream = recursive ? Files.walk(root, 3) : Files.list(root)
        stream.withCloseable { s ->
            s.filter { it != root }
                .filter { extensions.isEmpty() || Files.isDirectory(it) || extensions.contains(extension(it.fileName.toString())) }
                .limit(limit)
                .forEach { entries << describe(it) }
        }
        return AgentToolResult.ok([
            path     : root.toString(),
            recursive: recursive,
            count    : entries.size(),
            truncated: entries.size() >= limit,
            entries  : entries
        ])
    }

    private static Map<String, Object> describe(Path path) {
        boolean directory = Files.isDirectory(path)
        return [
            name        : path.fileName?.toString(),
            path        : path.toString(),
            directory   : directory,
            regularFile : Files.isRegularFile(path),
            size        : directory ? null : safeSize(path),
            extension   : extension(path.fileName?.toString() ?: ''),
            lastModified: Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toString()
        ]
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path)
        } catch (Exception ignored) {
            return -1L
        }
    }

    private static Set<String> extensionSet(Object raw) {
        Collection values = raw instanceof Collection ? raw : raw ? raw.toString().split(',') : []
        return values.collect { it.toString().toLowerCase(Locale.ROOT).replaceAll('^\\.', '').trim() }.findAll { it } as Set
    }

    private static String extension(String name) {
        int index = name.lastIndexOf('.')
        return index >= 0 ? name.substring(index + 1).toLowerCase(Locale.ROOT) : ''
    }

    private static int intArg(Object value, int fallback) {
        return value instanceof Number ? value.intValue() : value ? value.toString().toInteger() : fallback
    }
}
