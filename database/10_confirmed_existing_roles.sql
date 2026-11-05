UPDATE job_role
SET is_emerging = 0
WHERE role_code IN (
  'RAG_APP_ENGINEER',
  'AI_AGENT_ENGINEER',
  'LLM_APP_ENGINEER',
  'AI_DATA_ENGINEER',
  'AI_SECURITY_TEST_ENGINEER'
)
AND is_emerging <> 0;
