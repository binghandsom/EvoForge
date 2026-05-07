package com.evoforge.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
class ConfiguredModelClientFactory {
    private final ObjectMapper objectMapper
    private final HttpClient httpClient

    ConfiguredModelClientFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build()
    }

    LlmClient llmClient(ModelProviderConfig config) {
        return new ConfiguredChatClient(config, objectMapper, httpClient)
    }

    CodeModelClient codeModelClient(ModelProviderConfig config) {
        LlmClient client = llmClient(config)
        return new CodeModelClient() {
            @Override
            String generateSkill(String prompt) {
                return generateSkill(prompt, [:])
            }

            @Override
            String generateSkill(String prompt, Map<String, Object> options) {
                return client.chat(prompt, options)
            }
        }
    }

    private static class ConfiguredChatClient implements LlmClient {
        private final ModelProviderConfig config
        private final ObjectMapper objectMapper
        private final HttpClient httpClient

        ConfiguredChatClient(ModelProviderConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
            this.config = config
            this.objectMapper = objectMapper
            this.httpClient = httpClient
        }

        @Override
        String chat(String prompt) {
            return chat(prompt, [:])
        }

        @Override
        String chat(String prompt, Map<String, Object> options) {
            if (!config.apiKey) {
                throw new IllegalStateException("Model provider '${config.id}' has no API key configured")
            }
            switch (config.providerType) {
                case 'anthropic':
                    return anthropicChat(prompt, options ?: [:])
                case 'openai':
                case 'openai-compatible':
                default:
                    return openAiChat(prompt, options ?: [:])
            }
        }

        private String openAiChat(String prompt, Map<String, Object> options) {
            Map<String, Object> body = [
                model   : config.modelName,
                messages: [[role: 'user', content: openAiContent(prompt, images(options))]]
            ]
            applyOpenAiOptions(body, options)
            Map<String, Object> data = postJson(
                endpoint('/chat/completions'),
                [
                    'Authorization': "Bearer ${config.apiKey}".toString(),
                    'Content-Type' : 'application/json'
                ],
                body
            )
            def content = data?.choices?.getAt(0)?.message?.content
            return content == null ? '' : content.toString()
        }

        private String anthropicChat(String prompt, Map<String, Object> options) {
            Map<String, Object> body = [
                model     : config.modelName,
                max_tokens: intOption(options, 'maxTokens', 2048),
                messages  : [[role: 'user', content: anthropicContent(prompt, images(options))]]
            ]
            if (options.temperature instanceof Number) {
                body.temperature = (options.temperature as Number).doubleValue()
            }
            Map<String, Object> data = postJson(
                endpoint('/messages'),
                [
                    'x-api-key'        : config.apiKey,
                    'anthropic-version': options.anthropicVersion?.toString() ?: '2023-06-01',
                    'Content-Type'     : 'application/json'
                ],
                body
            )
            def textPart = data?.content?.find { it?.type?.toString() == 'text' }
            return textPart?.text == null ? '' : textPart.text.toString()
        }

        private Map<String, Object> postJson(String uri, Map<String, String> headers, Map<String, Object> body) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            headers.each { key, value -> builder.header(key, value) }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("Model provider '${config.id}' returned HTTP ${response.statusCode()}: ${truncate(response.body())}")
            }
            return objectMapper.readValue(response.body(), Map)
        }

        private String endpoint(String path) {
            String baseUrl = (config.baseUrl ?: ModelProviderConfigService.defaultBaseUrl(config.providerType)).replaceAll('/+$', '')
            if (baseUrl.endsWith(path)) {
                return baseUrl
            }
            return "${baseUrl}${path}"
        }

        private static void applyOpenAiOptions(Map<String, Object> body, Map<String, Object> options) {
            if (options.temperature instanceof Number) {
                body.temperature = (options.temperature as Number).doubleValue()
            }
            Integer maxTokens = optionalInt(options, 'maxTokens')
            if (maxTokens != null) {
                body.max_tokens = maxTokens
            }
        }

        private static Object openAiContent(String prompt, List<Map<String, Object>> images) {
            if (!images) {
                return prompt
            }
            List<Object> content = [[type: 'text', text: prompt]]
            images.each { image ->
                content << [
                    type     : 'image_url',
                    image_url: [url: "data:${image.mimeType};base64,${image.data}".toString()]
                ]
            }
            return content
        }

        private static Object anthropicContent(String prompt, List<Map<String, Object>> images) {
            if (!images) {
                return prompt
            }
            List<Object> content = [[type: 'text', text: prompt]]
            images.each { image ->
                content << [
                    type  : 'image',
                    source: [
                        type      : 'base64',
                        media_type: image.mimeType,
                        data      : image.data
                    ]
                ]
            }
            return content
        }

        private static List<Map<String, Object>> images(Map<String, Object> options) {
            Object raw = options.images
            if (!(raw instanceof Collection)) {
                return []
            }
            return raw.collect { item ->
                if (item instanceof Map && item.data && item.mimeType) {
                    return [mimeType: item.mimeType.toString(), data: item.data.toString()]
                }
                return null
            }.findAll { it != null } as List<Map<String, Object>>
        }

        private static int intOption(Map<String, Object> options, String key, int fallback) {
            return optionalInt(options, key) ?: fallback
        }

        private static Integer optionalInt(Map<String, Object> options, String key) {
            Object value = options[key]
            if (value instanceof Number) {
                return (value as Number).intValue()
            }
            if (value) {
                return value.toString().toInteger()
            }
            return null
        }

        private static String truncate(String body) {
            if (!body) {
                return ''
            }
            return body.length() <= 500 ? body : body.substring(0, 500)
        }
    }
}
