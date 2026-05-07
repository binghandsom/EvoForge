CREATE TABLE IF NOT EXISTS model_provider_configs (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    provider_type TEXT NOT NULL,
    base_url TEXT NOT NULL DEFAULT '',
    model_name TEXT NOT NULL,
    api_key TEXT NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    supports_llm BOOLEAN NOT NULL DEFAULT TRUE,
    supports_code_model BOOLEAN NOT NULL DEFAULT FALSE,
    default_llm BOOLEAN NOT NULL DEFAULT FALSE,
    default_code_model BOOLEAN NOT NULL DEFAULT FALSE,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_model_provider_configs_enabled ON model_provider_configs(enabled);
CREATE INDEX IF NOT EXISTS idx_model_provider_configs_provider_type ON model_provider_configs(provider_type);
CREATE INDEX IF NOT EXISTS idx_model_provider_configs_defaults ON model_provider_configs(default_llm, default_code_model);
