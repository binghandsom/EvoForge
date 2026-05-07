class ModelProviderConfigView {
  final String id;
  final String name;
  final String providerType;
  final String baseUrl;
  final String modelName;
  final bool enabled;
  final bool supportsLlm;
  final bool supportsCodeModel;
  final bool defaultLlm;
  final bool defaultCodeModel;
  final bool apiKeyConfigured;
  final String maskedApiKey;

  const ModelProviderConfigView({
    required this.id,
    required this.name,
    required this.providerType,
    required this.baseUrl,
    required this.modelName,
    required this.enabled,
    required this.supportsLlm,
    required this.supportsCodeModel,
    required this.defaultLlm,
    required this.defaultCodeModel,
    required this.apiKeyConfigured,
    required this.maskedApiKey,
  });

  factory ModelProviderConfigView.fromJson(Map<String, dynamic> json) {
    return ModelProviderConfigView(
      id: json['id']?.toString() ?? '',
      name: json['name']?.toString() ?? '',
      providerType: json['providerType']?.toString() ?? '',
      baseUrl: json['baseUrl']?.toString() ?? '',
      modelName: json['modelName']?.toString() ?? '',
      enabled: json['enabled'] == true,
      supportsLlm: json['supportsLlm'] == true,
      supportsCodeModel: json['supportsCodeModel'] == true,
      defaultLlm: json['defaultLlm'] == true,
      defaultCodeModel: json['defaultCodeModel'] == true,
      apiKeyConfigured: json['apiKeyConfigured'] == true,
      maskedApiKey: json['maskedApiKey']?.toString() ?? '',
    );
  }
}
