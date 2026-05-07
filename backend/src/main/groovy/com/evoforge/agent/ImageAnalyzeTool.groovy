package com.evoforge.agent

import com.evoforge.llm.ModelHub
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@Component
class ImageAnalyzeTool implements AgentTool {
    private final ModelHub modelHub

    ImageAnalyzeTool(ModelHub modelHub) {
        this.modelHub = modelHub
    }

    @Override
    String name() { 'image.analyze' }

    @Override
    String description() {
        'Analyze a local image file with the configured vision-capable LLM. Provide a concrete path and an explicit evaluation prompt.'
    }

    @Override
    Map<String, Object> inputSchema() {
        [
            type      : 'object',
            properties: [
                path  : [type: 'string'],
                prompt: [type: 'string'],
                llm   : [type: 'string']
            ],
            required  : ['path', 'prompt']
        ]
    }

    @Override
    AgentToolResult execute(Map<String, Object> args, AgentToolContext context) {
        Path path = PathResolveTool.resolve(args.path?.toString() ?: '')
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return AgentToolResult.fail("Image file is not readable: ${path}".toString(), [path: path.toString()])
        }
        long size = Files.size(path)
        if (size > 12L * 1024L * 1024L) {
            return AgentToolResult.fail("Image file is too large: ${size} bytes".toString(), [path: path.toString(), size: size])
        }
        String mime = Files.probeContentType(path) ?: mimeFromName(path.fileName.toString())
        if (!mime.startsWith('image/')) {
            return AgentToolResult.fail("File is not an image: ${mime}".toString(), [path: path.toString(), mimeType: mime])
        }
        String data = Base64.encoder.encodeToString(Files.readAllBytes(path))
        String prompt = args.prompt?.toString() ?: 'Analyze this image.'
        String result = modelHub.getLlm(args.llm?.toString()).chat(prompt, [
            images: [[mimeType: mime, data: data]],
            maxTokens: 1200
        ] as Map<String, Object>)
        return AgentToolResult.ok([
            path    : path.toString(),
            mimeType: mime,
            size    : size,
            analysis: result
        ])
    }

    private static String mimeFromName(String name) {
        String lower = name.toLowerCase(Locale.ROOT)
        if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg'
        if (lower.endsWith('.webp')) return 'image/webp'
        if (lower.endsWith('.gif')) return 'image/gif'
        return 'image/png'
    }
}
