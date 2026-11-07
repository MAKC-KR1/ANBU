CREATE TABLE test_request_reads (
    request_id BIGINT NOT NULL REFERENCES test_requests(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (request_id, user_id)
);
