-- Fresh installation. Use a dedicated database owned by the application role.
SET LOCAL search_path = public, pg_catalog;

CREATE TABLE kb_knowledge (
  id uuid PRIMARY KEY,
  system_code varchar(80) NOT NULL, module_name varchar(160) NOT NULL,
  customer_code varchar(80) NOT NULL, product_version varchar(80) NOT NULL,
  git_branch varchar(200) NOT NULL, db_vendor varchar(80) NOT NULL,
  scope_key char(64) NOT NULL,
  knowledge_type varchar(32) NOT NULL CHECK (knowledge_type IN ('STANDARD','ARCHITECTURE','BUSINESS_RULE','BUG','DECISION','TASK','DATA_LINEAGE','EXPERIENCE')),
  authority varchar(32) NOT NULL CHECK (authority IN ('COMPANY_MANDATORY','COMPANY_RECOMMENDED','PRODUCT_STANDARD','CUSTOMER_STANDARD','PROJECT_CONVENTION','EXPERIENCE')),
  current_revision_id uuid NOT NULL, current_revision_no integer NOT NULL CHECK (current_revision_no > 0),
  current_content_hash char(64) NOT NULL, current_active boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((knowledge_type='STANDARD' AND authority<>'EXPERIENCE') OR (knowledge_type<>'STANDARD' AND authority='EXPERIENCE')),
  CHECK (system_code<>'' AND module_name<>'' AND customer_code<>'' AND product_version<>'' AND git_branch<>'' AND db_vendor<>'')
);
CREATE UNIQUE INDEX uk_kb_active_content ON kb_knowledge(scope_key,knowledge_type,current_content_hash) WHERE current_active;
CREATE INDEX ix_kb_scope ON kb_knowledge(system_code,customer_code,product_version,git_branch);
CREATE TABLE kb_revision (
  id uuid PRIMARY KEY, knowledge_id uuid NOT NULL REFERENCES kb_knowledge(id),
  revision_no integer NOT NULL CHECK (revision_no>0), title varchar(240) NOT NULL, body text NOT NULL,
  content_hash char(64) NOT NULL, verification_status varchar(20) NOT NULL CHECK (verification_status IN ('VERIFIED','HISTORICAL','UNVERIFIED')),
  verification_evidence jsonb NOT NULL,
  write_review jsonb CHECK (write_review IS NULL OR jsonb_typeof(write_review) = 'object'),
  lifecycle varchar(20) NOT NULL CHECK (lifecycle IN ('ACTIVE','RETRACTED','RETIRED')),
  standard_code varchar(100), standard_version varchar(80), operation varchar(20),
  effective_from date, effective_to date,
  change_reason varchar(1000) NOT NULL, actor_id varchar(100) NOT NULL,
  restored_from_revision_id uuid REFERENCES kb_revision(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(knowledge_id,revision_no), UNIQUE(knowledge_id,id),
  CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to>=effective_from),
  CHECK ((standard_code IS NULL AND standard_version IS NULL AND operation IS NULL) OR
         (standard_code IS NOT NULL AND standard_version IS NOT NULL AND operation IN ('java','api','sql','export','docs','git')))
);
ALTER TABLE kb_knowledge ADD CONSTRAINT fk_kb_current_revision
  FOREIGN KEY (id,current_revision_id) REFERENCES kb_revision(knowledge_id,id) DEFERRABLE INITIALLY DEFERRED;
-- Lifecycle changes and restore operations can preserve a published standard version.
CREATE INDEX ix_kb_standard_version ON kb_revision(knowledge_id,standard_version) WHERE standard_version IS NOT NULL;
CREATE TABLE kb_source_ref (
  id uuid PRIMARY KEY, revision_id uuid NOT NULL REFERENCES kb_revision(id),
  ordinal integer NOT NULL CHECK (ordinal>=0), repo_id varchar(100) NOT NULL,
  source_path varchar(1000) NOT NULL, source_line integer, source_end_line integer,
  revision_kind varchar(20) NOT NULL CHECK (revision_kind IN ('COMMIT','WORKTREE','DOCUMENT','TEST')),
  commit_hash varchar(64), base_commit varchar(64), content_hash char(64) NOT NULL,
  UNIQUE(revision_id,ordinal),
  CHECK ((source_line IS NULL AND source_end_line IS NULL) OR
         (source_line IS NOT NULL AND source_end_line IS NOT NULL AND source_line>0 AND source_end_line>=source_line))
);
CREATE TABLE kb_chunk (
  id uuid PRIMARY KEY, revision_id uuid NOT NULL REFERENCES kb_revision(id), ordinal integer NOT NULL,
  body text NOT NULL, symbols text[] NOT NULL DEFAULT '{}', search_vector tsvector NOT NULL,
  tokenizer_version varchar(40) NOT NULL, UNIQUE(revision_id,ordinal)
);
CREATE INDEX ix_kb_chunk_text ON kb_chunk USING gin(search_vector);
CREATE INDEX ix_kb_chunk_symbols ON kb_chunk USING gin(symbols);
CREATE TABLE kb_relation (
  id uuid PRIMARY KEY, from_revision_id uuid NOT NULL REFERENCES kb_revision(id),
  relation_type varchar(40) NOT NULL CHECK (relation_type IN ('RELATED_TO','CORRECTS','CALLS','READS','WRITES','IMPLEMENTS','APPLIES_TO')),
  to_knowledge_id uuid NOT NULL REFERENCES kb_knowledge(id),
  UNIQUE(from_revision_id,relation_type,to_knowledge_id)
);
CREATE INDEX ix_kb_relation_target ON kb_relation(to_knowledge_id);
CREATE TABLE kb_write_receipt (
  actor_key varchar(100) NOT NULL, idempotency_key varchar(160) NOT NULL, request_hash char(64) NOT NULL,
  knowledge_id uuid NOT NULL REFERENCES kb_knowledge(id), revision_id uuid NOT NULL REFERENCES kb_revision(id),
  revision_no integer NOT NULL, outcome varchar(20) NOT NULL, content_hash char(64) NOT NULL,
  write_review jsonb CHECK (write_review IS NULL OR jsonb_typeof(write_review) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(actor_key,idempotency_key)
);
