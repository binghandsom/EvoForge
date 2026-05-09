package com.evoforge.learning

import com.evoforge.agent.AgentKnowledgeFact
import com.evoforge.agent.AgentKnowledgeService
import com.evoforge.audit.SkillAuditService
import com.evoforge.audit.SkillEvent
import com.evoforge.audit.SkillEventType
import com.evoforge.core.EvoForgeProperties
import com.evoforge.llm.ModelProviderConfig
import com.evoforge.llm.ModelProviderConfigService
import com.evoforge.model.SkillDefinition
import com.evoforge.store.SkillStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service

import java.time.Instant

@Service
class SelfLearningDashboardService {
    static final String STARTUP_SIDE_QUEST_KEY = 'evoforge.self_learning.startup_sidequest'
    static final String INTERFACE_POLICY_KEY = 'evoforge.self_learning.interface_policy'

    private final AgentKnowledgeService knowledgeService
    private final SkillStore skillStore
    private final SkillAuditService auditService
    private final ModelProviderConfigService modelProviderConfigService
    private final EvoForgeProperties properties
    private final ObjectMapper objectMapper

    SelfLearningDashboardService(AgentKnowledgeService knowledgeService,
                                 SkillStore skillStore,
                                 SkillAuditService auditService,
                                 ModelProviderConfigService modelProviderConfigService,
                                 EvoForgeProperties properties,
                                 ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService
        this.skillStore = skillStore
        this.auditService = auditService
        this.modelProviderConfigService = modelProviderConfigService
        this.properties = properties
        this.objectMapper = objectMapper
    }

    Map<String, Object> dashboard(int requestedActivityLimit = 0) {
        SelfLearningConfig config = config()
        int activityLimit = boundedLimit(
            requestedActivityLimit,
            config.maxRecentActivities,
            1,
            200
        )
        int milestoneLimit = boundedLimit(0, config.maxMilestones, 1, 100)
        int interfaceLimit = boundedLimit(0, config.maxInterfaceExamples, 1, 50)

        List<AgentKnowledgeFact> facts = safeList {
            knowledgeService.search('', Math.max(50, activityLimit * 2))
        }
        List<SkillDefinition> skills = safeList { skillStore.loadAllSummaries() }
        List<SkillEvent> audits = safeList { auditService.listAll() }
        List<ModelProviderConfig> modelConfigs = safeList { modelProviderConfigService.list() }

        List<Map<String, Object>> interfaceExamples = interfaceExamples(modelConfigs, interfaceLimit)
        List<Map<String, Object>> deprecatedInterfaces = deprecatedInterfaces(modelConfigs)
        List<Map<String, Object>> milestones = milestones(facts, audits, modelConfigs, milestoneLimit)
        List<Map<String, Object>> activities = activities(facts, audits, modelConfigs, activityLimit)
        List<Map<String, Object>> sideQuests = activeSideQuests(facts, audits)

        return [
            summary             : [
                phase              : 'autonomous-learning',
                status             : config.enabled ? 'active' : 'disabled',
                statusText         : config.enabled
                    ? '启动后自动开启自我学习侧线，持续把有效改进沉淀为能力。'
                    : '自我学习已在配置中关闭。',
                autoStart          : config.autoStart,
                activityLimit      : activityLimit,
                okInterfaceCount   : interfaceExamples.count { it.status == 'OK' },
                milestoneCount     : milestones.size(),
                activeSideQuestCount: sideQuests.size(),
                updatedAt          : Instant.now().toString()
            ],
            capabilityAreas     : capabilityAreas(skills, facts, modelConfigs),
            modelEvolution      : modelEvolution(skills, facts, modelConfigs),
            activeSideQuests    : sideQuests,
            milestones          : milestones,
            activities          : activities,
            learningVelocity    : learningVelocity(activities, milestones, config),
            resourceUtilization : resourceUtilization(config),
            interfaceExamples   : interfaceExamples,
            deprecatedInterfaces: deprecatedInterfaces,
            activityRetention   : [
                policy             : 'newest-first-bounded-stream',
                backendMaxItems    : config.maxRecentActivities,
                returnedItems      : activities.size(),
                frontendRenderHint : Math.min(activityLimit, config.maxRecentActivities),
                browsingEventsRule : '网页/资料浏览只能进入活动流；只有形成已验证改进、接口或 Skill 后才进入里程碑。'
            ]
        ] as Map<String, Object>
    }

    Map<String, Object> startAutonomousLearningSideQuest() {
        SelfLearningConfig config = config()
        if (!config.enabled || !config.autoStart) {
            return [enabled: config.enabled, autoStart: config.autoStart, started: false] as Map<String, Object>
        }

        Instant now = Instant.now()
        Map previous = readFactValue(STARTUP_SIDE_QUEST_KEY)
        String startedAt = text(previous.startedAt) ?: now.toString()
        Map<String, Object> sideQuest = [
            kind       : 'self-learning-side-quest',
            title      : '自我升级推理模型与多模态生成能力',
            status     : 'active',
            startedAt  : startedAt,
            updatedAt  : now.toString(),
            objective  : '自动发现推理模型、训练数据、评测、多模态理解/生成、工具环境和验证链路的能力缺口，并把真正有效的改进沉淀到 EvoForge。',
            velocity   : [
                targetCycleMinutes      : config.targetImprovementCycleMinutes,
                maxParallelLearningTasks: config.maxParallelLearningTasks,
                resourceMode            : config.resourceMode
            ],
            focusAreas : [
                'model-core',
                'training-data-flywheel',
                'reasoning-loop',
                'multimodal-interfaces',
                'multimodal-generation',
                'skill-evolution',
                'network-learning-with-evidence',
                'regression-verification'
            ],
            guardrails : [
                '长期改变系统行为前需要用户确认',
                '网页浏览只算研究活动，不直接算里程碑',
                '接口示例只展示当前可用或明确推荐的形态',
                '过时或不合理模型接口进入摒弃列表',
                '尽量提高学习并发和硬件利用率，但不能拖垮当前主线任务'
            ],
            next       : '并行扫描当前模型接口、Skill、知识沉淀、网络资料和验证能力，优先补齐可复用的多模态能力。'
        ] as Map<String, Object>
        knowledgeService.upsert(
            STARTUP_SIDE_QUEST_KEY,
            objectMapper.writeValueAsString(sideQuest),
            'global',
            ['self-learning', 'side-quest', 'startup', 'multimodal', 'reasoning'],
            'self-learning',
            0.86d
        )

        Map<String, Object> interfacePolicy = [
            kind       : 'self-learning-interface-policy',
            title      : '模型接口取舍策略',
            status     : 'active',
            updatedAt  : now.toString(),
            principle  : '优先保留能表达多模态、工具调用、结构化参数和可验证输出的接口；历史不合理接口应明确摒弃，不再作为新能力示例。',
            abandoned  : deprecatedInterfacePolicies(),
            preferred  : [
                'OpenAI/Anthropic 兼容的 chat 形态',
                '带 provider/config id 的显式模型调用',
                '参数结构化、可审计、可回放的内部 client_request',
                '经过测试或真实配置验证的接口示例',
                '能并行检索、批量验证和复用本地硬件的学习接口'
            ]
        ] as Map<String, Object>
        knowledgeService.upsert(
            INTERFACE_POLICY_KEY,
            objectMapper.writeValueAsString(interfacePolicy),
            'global',
            ['self-learning', 'model-interface', 'abandoned-interface', 'policy'],
            'self-learning',
            0.84d
        )

        return [enabled: true, autoStart: true, started: true, startedAt: startedAt, updatedAt: now.toString()]
    }

    private List<Map<String, Object>> capabilityAreas(List<SkillDefinition> skills,
                                                      List<AgentKnowledgeFact> facts,
                                                      List<ModelProviderConfig> modelConfigs) {
        int activeSkills = skills.count { it.enabled }
        int knowledgeFacts = facts.size()
        int okModels = modelConfigs.count { it.enabled && it.apiKey }
        return [
            [
                id      : 'reasoning-loop',
                name    : '推理执行闭环',
                status  : properties?.agent?.enabled ? 'active' : 'disabled',
                evidence: "agent.maxSteps=${properties?.agent?.maxSteps ?: 0}, skillFallback=${properties?.agent?.skillFallbackEnabled == true}".toString()
            ],
            [
                id      : 'model-core',
                name    : '模型本体进化',
                status  : 'bootstrapping',
                evidence: '知识库和 Skill 只是脚手架；目标是沉淀训练样本、评测集、推理模型和多模态生成模型。'
            ],
            [
                id      : 'skill-evolution',
                name    : 'Skill 自我进化',
                status  : activeSkills > 0 ? 'active' : 'warming-up',
                evidence: "${activeSkills} 个已激活 Skill，${skills.size()} 个 Skill 摘要可用于候选检索。".toString()
            ],
            [
                id      : 'learning-memory',
                name    : '学习记忆沉淀',
                status  : knowledgeFacts > 0 ? 'active' : 'empty',
                evidence: "最近 ${knowledgeFacts} 条知识事实可用于复盘、纠错和里程碑抽取。".toString()
            ],
            [
                id      : 'multimodal-interfaces',
                name    : '多模态接口能力',
                status  : okModels > 0 ? 'ok' : 'needs-config',
                evidence: okModels > 0
                    ? "${okModels} 个模型配置已启用且配置了密钥。".toString()
                    : '暂无启用且配置密钥的模型接口；页面会只展示安全的内部接口示例。'
            ],
            [
                id      : 'verification',
                name    : '验证与回归保护',
                status  : 'expanding',
                evidence: '有效改进需要来自测试、Skill 激活、模型配置验证或 Codex 任务成功记录。'
            ],
            [
                id      : 'learning-velocity',
                name    : '学习速度调度',
                status  : properties?.selfLearning?.networkLearningEnabled ? 'accelerating' : 'bounded',
                evidence: "targetCycle=${properties?.selfLearning?.targetImprovementCycleMinutes ?: 30}m, parallel=${properties?.selfLearning?.maxParallelLearningTasks ?: 4}, networkFetches=${properties?.selfLearning?.maxNetworkFetchesPerCycle ?: 16}".toString()
            ]
        ] as List<Map<String, Object>>
    }

    private Map<String, Object> modelEvolution(List<SkillDefinition> skills,
                                               List<AgentKnowledgeFact> facts,
                                               List<ModelProviderConfig> modelConfigs) {
        int reasoningTraceCount = facts.count {
            it.source == 'codex-learning' || hasTag(it, 'latest-change') || hasTag(it, 'task-intent')
        }
        int supervisedSampleCandidates = facts.count {
            Map value = readMap(it.value)
            ['completed', 'success'].contains(text(value.status))
        }
        int okReasoningBackends = modelConfigs.count { it.enabled && it.apiKey && it.supportsLlm }
        int okCodeBackends = modelConfigs.count { it.enabled && it.apiKey && it.supportsCodeModel }
        int multimodalGenerationBackends = modelConfigs.count { supportsMultimodalGeneration(it) }
        return [
            target  : 'ChatGPT-like reasoning model with multimodal generation',
            boundary: '知识库、记忆和 Skill 是训练数据/工具环境/脚手架，不是模型本体。',
            status  : okReasoningBackends > 0 ? 'bootstrapping-with-external-models' : 'needs-reasoning-backend',
            metrics : [
                reasoningTraceCount          : reasoningTraceCount,
                supervisedSampleCandidates   : supervisedSampleCandidates,
                okReasoningBackends          : okReasoningBackends,
                okCodeBackends               : okCodeBackends,
                multimodalGenerationBackends : multimodalGenerationBackends,
                activeSkillEnvironmentCount  : skills.count { it.enabled }
            ],
            tracks  : [
                [
                    id      : 'training-data-flywheel',
                    name    : '训练数据飞轮',
                    status  : reasoningTraceCount > 0 ? 'collecting' : 'needs-traces',
                    evidence: "${reasoningTraceCount} 条推理/任务轨迹可候选转化为训练样本，${supervisedSampleCandidates} 条有成功信号。".toString(),
                    next    : '把任务轨迹拆成 instruction、reasoning trace、tool call、verification、preference 样本。'
                ],
                [
                    id      : 'reasoning-model',
                    name    : '推理模型',
                    status  : okReasoningBackends > 0 ? 'distillation-ready' : 'needs-provider-or-local-model',
                    evidence: okReasoningBackends > 0
                        ? "${okReasoningBackends} 个可用 LLM 后端可用于教师模型、蒸馏或评测。".toString()
                        : '暂无可用 LLM 后端，无法启动推理样本生成/蒸馏闭环。',
                    next    : '建立 EvoForge 专属推理评测集，再选择本地/开源模型做 SFT、LoRA 或蒸馏。'
                ],
                [
                    id      : 'multimodal-understanding',
                    name    : '多模态理解',
                    status  : 'data-needed',
                    evidence: '当前已能记录多模态任务意图，但还需要图片、文档、界面、音视频样本的结构化标注和评测。',
                    next    : '把真实图片/文档/桌面 UI/音视频任务转成可回放样本，并接入视觉/语音编码模型。'
                ],
                [
                    id      : 'multimodal-generation',
                    name    : '多模态生成',
                    status  : multimodalGenerationBackends > 0 ? 'interface-ready' : 'needs-generation-backend',
                    evidence: multimodalGenerationBackends > 0
                        ? "${multimodalGenerationBackends} 个模型配置声明了多模态生成能力。".toString()
                        : '尚未发现明确标记的图像/音频/视频生成后端；不能把普通文本接口当成多模态生成模型。',
                    next    : '接入图像、文档、音频、视频生成接口，并建立质量评测与参数示例。'
                ],
                [
                    id      : 'evaluation-harness',
                    name    : '模型评测体系',
                    status  : 'forming',
                    evidence: '里程碑必须来自验证，但还需要专门覆盖推理质量、多模态质量、生成质量和成本速度的评测集。',
                    next    : '把成功/失败任务沉淀成基准，分离训练集、验证集和回归集。'
                ],
                [
                    id      : 'model-runtime',
                    name    : '模型运行时',
                    status  : 'planned',
                    evidence: '当前运行时以外部模型、Skill 和工具编排为主，还没有统一的权重/数据/评测/生成运行时。',
                    next    : '设计模型注册、训练任务、评测结果、推理服务和多模态生成服务的统一接口。'
                ]
            ],
            next    : '下一阶段应优先建立训练样本格式、评测集和多模态生成后端接入，再推动本地/开源模型微调或蒸馏。'
        ] as Map<String, Object>
    }

    private Map<String, Object> learningVelocity(List<Map<String, Object>> activities,
                                                 List<Map<String, Object>> milestones,
                                                 SelfLearningConfig config) {
        Instant now = Instant.now()
        int recentActivities = countSince(activities, now.minusSeconds(3600))
        int recentMilestones = countSince(milestones, now.minusSeconds(86400))
        String pressure = recentMilestones > 0
            ? 'effective'
            : recentActivities >= Math.max(1, config.maxParallelLearningTasks)
                ? 'needs-validation'
                : 'needs-acceleration'
        return [
            status                      : pressure,
            targetImprovementCycleMinutes: config.targetImprovementCycleMinutes,
            recentActivityWindowMinutes : 60,
            recentActivityCount         : recentActivities,
            recentMilestoneWindowHours  : 24,
            recentMilestoneCount        : recentMilestones,
            maxParallelLearningTasks    : config.maxParallelLearningTasks,
            next                        : pressure == 'effective'
                ? '保持当前节奏，并把高收益学习支线沉淀为可复用能力。'
                : '提高并行资料检索、接口验证和测试生成比例，优先把活动转化为有效里程碑。'
        ] as Map<String, Object>
    }

    private Map<String, Object> resourceUtilization(SelfLearningConfig config) {
        return [
            mode                         : config.resourceMode,
            networkLearningEnabled       : config.networkLearningEnabled,
            hardwareAccelerationEnabled  : config.hardwareAccelerationEnabled,
            preferLocalHardware          : config.preferLocalHardware,
            maxParallelLearningTasks     : config.maxParallelLearningTasks,
            maxNetworkFetchesPerCycle    : config.maxNetworkFetchesPerCycle,
            maxCandidateSourcesPerTopic  : config.maxCandidateSourcesPerTopic,
            maxHardwareUtilizationPercent: config.maxHardwareUtilizationPercent,
            strategy                     : [
                '互不依赖的网络检索、源码阅读、接口验证和测试补充应并行推进',
                '优先复用本地索引、缓存、测试结果和已验证模型接口',
                'CPU/GPU/NPU 可用时用于批量解析、向量检索、多模态处理和本地验证',
                '主线任务优先级高于支线学习；资源压力过高时自动收敛并发'
            ]
        ] as Map<String, Object>
    }

    private List<Map<String, Object>> activeSideQuests(List<AgentKnowledgeFact> facts, List<SkillEvent> audits) {
        List<Map<String, Object>> quests = []
        facts.findAll { hasTag(it, 'side-quest') || it.key == STARTUP_SIDE_QUEST_KEY }.each { fact ->
            Map value = readMap(fact.value)
            quests << [
                id       : fact.key,
                title    : text(value.title) ?: fact.key,
                status   : text(value.status) ?: 'active',
                objective: text(value.objective) ?: compact(fact.value, 240),
                next     : text(value.next),
                updatedAt: fact.updatedAt?.toString()
            ].findAll { it.value != null && it.value != '' } as Map<String, Object>
        }
        audits.findAll { it.type == SkillEventType.PROPOSED }.sort { a, b -> b.timestamp <=> a.timestamp }.take(8).each { event ->
            quests << [
                id       : "skill-proposal:${event.id}".toString(),
                title    : "待确认 Skill 提案：${event.skillName ?: '未命名'}".toString(),
                status   : 'needs-confirmation',
                objective: compact(event.payload?.prompt, 240),
                updatedAt: event.timestamp?.toString()
            ] as Map<String, Object>
        }
        return quests
            .unique { it.id }
            .sort { a, b -> instant(b.updatedAt) <=> instant(a.updatedAt) }
            .take(20)
    }

    private List<Map<String, Object>> milestones(List<AgentKnowledgeFact> facts,
                                                 List<SkillEvent> audits,
                                                 List<ModelProviderConfig> modelConfigs,
                                                 int limit) {
        List<Map<String, Object>> items = []
        audits.findAll { it.type in [SkillEventType.ACTIVATED, SkillEventType.UPDATED, SkillEventType.ROLLED_BACK] }.each { event ->
            items << [
                id       : "skill:${event.id}".toString(),
                category : 'skill',
                status   : 'OK',
                title    : "${skillEventVerb(event.type)}：${event.skillName ?: event.skillId ?: 'Skill'}".toString(),
                evidence : "skillId=${event.skillId ?: '-'}, event=${event.type}".toString(),
                createdAt: event.timestamp?.toString()
            ] as Map<String, Object>
        }
        modelConfigs.findAll { it.enabled && it.apiKey }.each { config ->
            items << [
                id       : "model:${config.id}".toString(),
                category : 'model-interface',
                status   : 'OK',
                title    : "模型接口可用：${config.name ?: config.modelName}".toString(),
                evidence : "${config.providerType} · ${config.modelName} · supportsLlm=${config.supportsLlm} · supportsCodeModel=${config.supportsCodeModel}".toString(),
                createdAt: config.updatedAt?.toString()
            ] as Map<String, Object>
        }
        facts.findAll { it.source == 'codex-learning' || hasTag(it, 'latest-change') }.each { fact ->
            Map value = readMap(fact.value)
            String status = text(value.status)
            if (status == 'completed' || status == 'success') {
                items << [
                    id       : "learning:${fact.id ?: fact.key}".toString(),
                    category : 'validated-change',
                    status   : 'OK',
                    title    : '任务改进已沉淀',
                    evidence : compact(value.userTask ?: value.codexOutput ?: fact.value, 260),
                    createdAt: fact.updatedAt?.toString()
                ] as Map<String, Object>
            }
        }
        return items
            .findAll { it.createdAt }
            .sort { a, b -> instant(b.createdAt) <=> instant(a.createdAt) }
            .take(limit)
    }

    private List<Map<String, Object>> activities(List<AgentKnowledgeFact> facts,
                                                 List<SkillEvent> audits,
                                                 List<ModelProviderConfig> modelConfigs,
                                                 int limit) {
        List<Map<String, Object>> items = []
        facts.each { fact ->
            Map value = readMap(fact.value)
            boolean browsing = isBrowsingFact(fact, value)
            items << [
                id       : "knowledge:${fact.id ?: fact.key}".toString(),
                type     : browsing ? 'web-research' : activityType(fact, value),
                status   : text(value.status) ?: (hasTag(fact, 'error') ? 'failed' : 'recorded'),
                title    : text(value.title) ?: text(value.kind) ?: fact.key,
                detail   : compact(value.objective ?: value.userTask ?: value.codexOutput ?: value.principle ?: fact.value, 320),
                source   : fact.source ?: 'knowledge',
                evidence : evidenceForFact(fact, value),
                createdAt: fact.updatedAt?.toString()
            ].findAll { it.value != null && it.value != '' } as Map<String, Object>
        }
        audits.each { event ->
            items << [
                id       : "audit:${event.id}".toString(),
                type     : 'skill-audit',
                status   : event.type in [SkillEventType.FAILED] ? 'failed' : 'recorded',
                title    : "${skillEventVerb(event.type)}：${event.skillName ?: event.skillId ?: 'Skill'}".toString(),
                detail   : compact(event.payload, 320),
                source   : 'skill-audit',
                evidence : "event=${event.type}, skillId=${event.skillId ?: '-'}".toString(),
                createdAt: event.timestamp?.toString()
            ] as Map<String, Object>
        }
        modelConfigs.each { config ->
            items << [
                id       : "model-config:${config.id}".toString(),
                type     : 'model-interface',
                status   : config.enabled && config.apiKey ? 'OK' : 'needs-config',
                title    : "模型配置：${config.name ?: config.modelName ?: config.id}".toString(),
                detail   : "${config.providerType} · ${config.modelName} · ${config.enabled ? 'enabled' : 'disabled'}".toString(),
                source   : 'model-config',
                evidence : config.apiKey ? 'apiKey=configured' : 'apiKey=missing',
                createdAt: config.updatedAt?.toString()
            ] as Map<String, Object>
        }
        return items
            .findAll { it.createdAt }
            .sort { a, b -> instant(b.createdAt) <=> instant(a.createdAt) }
            .take(limit)
    }

    private List<Map<String, Object>> interfaceExamples(List<ModelProviderConfig> configs, int limit) {
        List<Map<String, Object>> examples = []
        examples << [
            id          : 'self-learning-dashboard',
            name        : '自我学习仪表盘',
            status      : 'OK',
            transport   : 'client_request / REST',
            method      : 'selfLearning.dashboard',
            endpoint    : '/api/self-learning/dashboard',
            parameterExample: [
                limit: 80
            ],
            note        : '活动流服务端限量返回，前端固定渲染窗口，避免长期运行后 UI 元素爆量。'
        ] as Map<String, Object>
        configs.findAll { it.enabled && it.apiKey }.each { config ->
            if (config.supportsLlm) {
                examples << [
                    id          : "llm:${config.id}".toString(),
                    name        : "LLM 调用：${config.name ?: config.modelName}".toString(),
                    status      : 'OK',
                    transport   : 'REST',
                    method      : 'POST',
                    endpoint    : '/api/models/llm/chat',
                    providerId  : config.id,
                    modelName   : config.modelName,
                    parameterExample: [
                        provider   : config.id,
                        prompt     : '用三步推理分析这个任务的可执行路线',
                        temperature: 0.2,
                        maxTokens  : 1200
                    ]
                ] as Map<String, Object>
            }
            if (config.supportsCodeModel) {
                examples << [
                    id          : "code:${config.id}".toString(),
                    name        : "代码/Skill 生成：${config.name ?: config.modelName}".toString(),
                    status      : 'OK',
                    transport   : 'REST',
                    method      : 'POST',
                    endpoint    : '/api/models/code/generate',
                    providerId  : config.id,
                    modelName   : config.modelName,
                    parameterExample: [
                        provider: config.id,
                        prompt  : '为 WPS 云文档定位与编辑生成一个可验证 Skill'
                    ]
                ] as Map<String, Object>
            }
        }
        return examples.take(limit)
    }

    private List<Map<String, Object>> deprecatedInterfaces(List<ModelProviderConfig> configs) {
        List<Map<String, Object>> items = deprecatedInterfacePolicies()
        configs.findAll { deprecatedMetadata(it.metadata) }.each { config ->
            items << [
                id         : "model-config:${config.id}".toString(),
                name       : config.name ?: config.modelName ?: config.id,
                status     : 'abandoned',
                reason     : text(config.metadata?.reason) ?: '该模型配置已被标记为过时或不适合继续作为 EvoForge 新能力入口。',
                replacement: text(config.metadata?.replacement) ?: '迁移到已验证的 chat / multimodal / structured client_request 形态。'
            ] as Map<String, Object>
        }
        return items.unique { it.id }
    }

    private static List<Map<String, Object>> deprecatedInterfacePolicies() {
        return [
            [
                id         : 'legacy-completions',
                name       : '纯 text completion 旧接口',
                status     : 'abandoned',
                reason     : '难以表达多模态输入、工具调用、结构化参数和可审计输出，不再作为新能力入口。',
                replacement: '使用 chat / responses / openai-compatible chat 形态，并显式传 provider/config id。'
            ],
            [
                id         : 'implicit-model-name-only',
                name       : '只传模型名的隐式接口',
                status     : 'abandoned',
                reason     : '缺少 provider、baseUrl、密钥状态和能力边界，无法判断接口是否真的 OK。',
                replacement: '使用持久化模型配置 id，并展示可验证参数示例。'
            ],
            [
                id         : 'unbounded-web-crawl-as-progress',
                name       : '把无界网页浏览当作进展',
                status     : 'abandoned',
                reason     : '浏览本身不是有效迭代，且会造成活动流和 UI 元素无限增长。',
                replacement: '浏览只进入有界活动流；通过验证的改进、接口或 Skill 才进入里程碑。'
            ]
        ] as List<Map<String, Object>>
    }

    private Map readFactValue(String key) {
        return knowledgeService.findByKey(key, 'global')
            .map { readMap(it.value) }
            .orElse([:] as Map)
    }

    private Map readMap(Object value) {
        if (value instanceof Map) {
            return value as Map
        }
        String raw = text(value)
        if (!raw) {
            return [:]
        }
        try {
            Object parsed = objectMapper.readValue(raw, Map)
            return parsed instanceof Map ? parsed as Map : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    private static boolean deprecatedMetadata(Map metadata) {
        if (!(metadata instanceof Map)) {
            return false
        }
        return metadata.deprecated == true ||
            text(metadata.status).equalsIgnoreCase('deprecated') ||
            text(metadata.lifecycle).equalsIgnoreCase('abandoned')
    }

    private static boolean supportsMultimodalGeneration(ModelProviderConfig config) {
        Map metadata = config?.metadata ?: [:]
        if (metadata.multimodalGeneration == true ||
            metadata.supportsMultimodalGeneration == true ||
            metadata.supportsImageGeneration == true ||
            metadata.supportsAudioGeneration == true ||
            metadata.supportsVideoGeneration == true) {
            return config.enabled && config.apiKey
        }
        String modalities = text(metadata.modalities).toLowerCase(Locale.ROOT)
        String capabilities = text(metadata.capabilities).toLowerCase(Locale.ROOT)
        String combined = "${modalities} ${capabilities}".toString()
        return config.enabled && config.apiKey &&
            ['image-generation', 'audio-generation', 'video-generation', 'multimodal-generation'].any { combined.contains(it) }
    }

    private static String activityType(AgentKnowledgeFact fact, Map value) {
        String kind = text(value.kind)
        if (hasTag(fact, 'side-quest') || kind.contains('side-quest')) {
            return 'side-quest'
        }
        if (fact.source == 'codex-learning' || kind.contains('change-episode')) {
            return 'validated-change'
        }
        if (hasTag(fact, 'model-interface')) {
            return 'model-interface'
        }
        return 'knowledge'
    }

    private static boolean isBrowsingFact(AgentKnowledgeFact fact, Map value) {
        if (hasTag(fact, 'web') || hasTag(fact, 'web-research') || hasTag(fact, 'network-learning')) {
            return true
        }
        String source = text(fact.source).toLowerCase(Locale.ROOT)
        if (source.contains('web') || source.contains('browser')) {
            return true
        }
        return text(value.url) || text(value.sourceUrl) || text(value.uri)
    }

    private static String evidenceForFact(AgentKnowledgeFact fact, Map value) {
        if (text(value.url)) {
            return text(value.url)
        }
        if (text(value.taskId)) {
            return "taskId=${value.taskId}".toString()
        }
        return "key=${fact.key}, confidence=${fact.confidence}".toString()
    }

    private static String skillEventVerb(SkillEventType type) {
        switch (type) {
            case SkillEventType.CREATED:
                return 'Skill 创建'
            case SkillEventType.UPDATED:
                return 'Skill 更新'
            case SkillEventType.ACTIVATED:
                return 'Skill 激活'
            case SkillEventType.DEACTIVATED:
                return 'Skill 停用'
            case SkillEventType.EXECUTED:
                return 'Skill 执行'
            case SkillEventType.FAILED:
                return 'Skill 失败'
            case SkillEventType.ROLLED_BACK:
                return 'Skill 回滚'
            case SkillEventType.PROPOSED:
                return 'Skill 提案'
            default:
                return 'Skill 事件'
        }
    }

    private static int boundedLimit(int requested, int configured, int min, int hardMax) {
        int fallback = configured > 0 ? configured : hardMax
        int selected = requested > 0 ? requested : fallback
        int max = Math.max(min, Math.min(fallback, hardMax))
        return Math.max(min, Math.min(selected, max))
    }

    private static int countSince(List<Map<String, Object>> items, Instant threshold) {
        return items.count { instant(it.createdAt) >= threshold }
    }

    private SelfLearningConfig config() {
        def raw = properties?.selfLearning ?: new EvoForgeProperties.SelfLearning()
        return new SelfLearningConfig(
            enabled: raw.enabled,
            autoStart: raw.autoStart,
            resourceMode: text(raw.resourceMode) ?: 'adaptive',
            networkLearningEnabled: raw.networkLearningEnabled,
            hardwareAccelerationEnabled: raw.hardwareAccelerationEnabled,
            preferLocalHardware: raw.preferLocalHardware,
            targetImprovementCycleMinutes: positiveInt(raw.targetImprovementCycleMinutes, 30, 5, 1440),
            maxParallelLearningTasks: positiveInt(raw.maxParallelLearningTasks, 4, 1, 64),
            maxNetworkFetchesPerCycle: positiveInt(raw.maxNetworkFetchesPerCycle, 16, 1, 500),
            maxCandidateSourcesPerTopic: positiveInt(raw.maxCandidateSourcesPerTopic, 8, 1, 100),
            maxHardwareUtilizationPercent: positiveInt(raw.maxHardwareUtilizationPercent, 85, 10, 100),
            maxRecentActivities: Math.max(1, raw.maxRecentActivities ?: 80),
            maxMilestones: Math.max(1, raw.maxMilestones ?: 30),
            maxInterfaceExamples: Math.max(1, raw.maxInterfaceExamples ?: 12)
        )
    }

    private static Instant instant(Object value) {
        try {
            String text = text(value)
            return text ? Instant.parse(text) : Instant.EPOCH
        } catch (Exception ignored) {
            return Instant.EPOCH
        }
    }

    private static boolean hasTag(AgentKnowledgeFact fact, String tag) {
        String needle = tag?.toLowerCase(Locale.ROOT)
        return (fact?.tags ?: []).any { it?.toString()?.toLowerCase(Locale.ROOT)?.contains(needle) }
    }

    private static String compact(Object value, int limit) {
        String normalized = value instanceof Map || value instanceof Collection
            ? value.toString()
            : (value ?: '').toString()
        normalized = normalized.replaceAll('\\s+', ' ').trim()
        return normalized.length() > limit ? normalized.take(limit) + '...' : normalized
    }

    private static String text(Object value) {
        value == null ? '' : value.toString().trim()
    }

    private static int positiveInt(Object value, int fallback, int min, int max) {
        int parsed = value instanceof Number ? value.intValue() : value?.toString()?.isInteger() ? value.toString().toInteger() : fallback
        return Math.max(min, Math.min(max, parsed))
    }

    private static List safeList(Closure supplier) {
        try {
            return (supplier.call() ?: []) as List
        } catch (Exception ignored) {
            return []
        }
    }

    private static class SelfLearningConfig {
        boolean enabled
        boolean autoStart
        String resourceMode
        boolean networkLearningEnabled
        boolean hardwareAccelerationEnabled
        boolean preferLocalHardware
        int targetImprovementCycleMinutes
        int maxParallelLearningTasks
        int maxNetworkFetchesPerCycle
        int maxCandidateSourcesPerTopic
        int maxHardwareUtilizationPercent
        int maxRecentActivities
        int maxMilestones
        int maxInterfaceExamples
    }
}
