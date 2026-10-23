CREATE INDEX idx_content_reviews_publication_order
    ON content_reviews(user_id, content_id, source_published_at, source_published_on, id);
