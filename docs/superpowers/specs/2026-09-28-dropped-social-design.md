# Social interaction on dropped reviews

## Scope

`DroppedEntry.comment` remains the only review text field. No score is added to a dropped entry. A dropped entry becomes a social target for likes and comments, using the same profile-visibility rule as diary entries. The feed exposes its like count and whether the viewer liked it.

`GET /contents/{contentId}/reviews` returns diary reviews and dropped reviews together, limited to rows with a non-null comment and ordered by creation time. Each result identifies whether its target is a diary entry or a dropped entry, so clients can call the correct like/comment endpoint.

## Data model

`dropped_entries` receives `likes_count`. `comments.dropped_entry_id` and `likes.dropped_entry_id` are nullable target columns. Their check constraints continue to require exactly one target per row, and unique/index constraints preserve one like per user/target and indexed comment reads.

## HTTP surface

- `GET/POST /dropped/{droppedEntryId}/comments`
- `POST/DELETE /dropped/{droppedEntryId}/like`
- Existing dropped-entry responses and `DROPPED` feed items expose `likesCount` and `likedByMe`.
- Content review responses expose `source = DIARY` or `DROPPED` and retain diary-specific nullable fields for compatibility with the existing review payload.

## Authorization and deletion

Reading or interacting with a dropped entry follows the same rule as diary entries: owner, public profile, or accepted follower. Foreign-key cascades remove dropped likes/comments when the dropped row is deleted. A diary write still removes the dropped row and therefore its social interactions.

## Verification

Add controller/service tests for dropped likes and comments, visibility and idempotency, feed enrichment, and mixed content-review pagination/visibility.
