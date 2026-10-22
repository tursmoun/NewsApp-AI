CREATE TABLE ai_revision_reviews (
  revision_id uuid PRIMARY KEY REFERENCES news_revisions(id) ON DELETE CASCADE,
  state text NOT NULL DEFAULT 'waiting' CHECK (state IN ('waiting','done','error')),
  risk_group text CHECK (risk_group IN ('normal','doubtful','suspicious')),
  result jsonb,
  model text,
  checked_at timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  retry_at timestamptz NOT NULL DEFAULT now()
);
