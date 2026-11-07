ALTER TABLE test_requests ADD COLUMN recipient_organization_id BIGINT REFERENCES organizations(id);
ALTER TABLE test_requests ADD COLUMN note VARCHAR(500);
ALTER TABLE test_requests ADD CONSTRAINT test_requests_distinct_recipient
    CHECK (recipient_organization_id IS NULL OR recipient_organization_id <> requested_by_organization_id);
CREATE INDEX test_requests_recipient_status ON test_requests(recipient_organization_id, status);
