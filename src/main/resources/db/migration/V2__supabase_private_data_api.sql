-- Keep restaurant accounting data backend-only when PostgreSQL is hosted on Supabase.
-- Spring/Flyway connects as the database owner. The public Data API roles receive no table grants.

ALTER TABLE categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendors ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE normalization_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE source_column_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE review_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_metrics ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL PRIVILEGES ON TABLE
            categories,
            vendors,
            employees,
            normalization_mappings,
            source_messages,
            source_documents,
            ingestion_jobs,
            source_column_mappings,
            transactions,
            review_items,
            audit_logs,
            daily_metrics
        FROM anon;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL PRIVILEGES ON TABLE
            categories,
            vendors,
            employees,
            normalization_mappings,
            source_messages,
            source_documents,
            ingestion_jobs,
            source_column_mappings,
            transactions,
            review_items,
            audit_logs,
            daily_metrics
        FROM authenticated;
    END IF;
END
$$;
