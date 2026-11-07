# Social notification aggregation and retention

## Goal

Add notifications for likes and comments received by user-owned social items, aggregate repeated actions into one notification, and remove stale social notifications automatically.

## Scope

Like notifications are created for comments, diary reviews, dropped reviews, user lists, Picks, and Picks templates. Comment notifications are created for user lists, diary reviews, dropped reviews, Picks, and Picks templates. Comments on `Content` do not notify a user because `Content` has no owner. Official Picks templates without a creator also have no notification recipient. Actions performed by the item's owner do not notify that same owner.

Replies remain comments on the original social item. They do not introduce a separate notification target for the parent comment.

Existing TMDB and followed-person notification types remain unchanged.

## Persistence model

Social notifications use `LIKE_RECEIVED` and `COMMENT_RECEIVED` notification types. Each social notification is identified by:

`recipient + notification type + target type + target id`

The notification stores the latest actor, the target type/id, the number of interactions in the current aggregate, read state, message, and timestamps. The target is represented by a type/id pair so future social targets do not require another nullable foreign-key column. Because this polymorphic reference cannot use a database foreign key, a notification for a deleted target may remain visible until the retention job removes it, with a maximum lifetime of 30 days.

The database has a partial unique index for social aggregate keys. Existing system notifications remain allowed to have multiple rows and keep their current content/person references.

## Aggregation behavior

The first action creates an unread notification with count `1`. Further actions for the same recipient, action type, and target update that row while it is unread, incrementing the count and replacing the latest actor. The message uses the latest actor and count, with singular/plural wording.

When the aggregate is already read, a later action deletes the old aggregate row and creates a new notification with a new id, count `1`, the latest actor, and `isRead = false`. This keeps the read notification out of the way while exposing the new interaction as a fresh item. Marking a notification as read does not delete it by itself. Unlike the current Like counter, the notification count records received interactions and is not decremented when an actor unlikes an item.

The create/update/replace operation is atomic and safe for concurrent actions. It must not use an unprotected check-then-insert sequence. The unique aggregate key and database-level upsert/locking behavior prevent duplicate aggregate rows and lost increments. Replacing a read aggregate is serialized so only one old row is removed and each later action either aggregates into the current unread row or starts the next fresh row.

## Retention

Only social notifications are subject to this retention policy:

- read social notifications are deleted after 7 days based on `updatedAt`;
- unread social notifications are deleted after 30 days based on `updatedAt`;
- the cleanup runs on a scheduled job and deletes in bounded batches;
- the job is idempotent and does not affect TMDB/followed-person notifications.

Because `updatedAt` represents the last aggregation or read change, a newly aggregated notification receives the full retention window again. The cleanup query must use a strict age cutoff and a stable batch limit so large histories do not create one long transaction.

## Runtime flow

`LikeServiceImpl` resolves the target owner after its existing visibility/idempotency checks and invokes a social notification writer only after the Like succeeds. `CommentServiceImpl` invokes the same writer after the Comment is built and persisted. The notification write participates in the same transaction as the social action, preventing a notification for a Like or Comment that ultimately rolled back.

The notification read response exposes the social target metadata, interaction count, and latest actor while preserving the existing content/person fields for system notifications. The existing `GET /notifications` and mark-as-read routes remain the read API.

## Validation

Tests cover:

- all likeable and commentable target types;
- owner/self-action suppression and ownerless targets;
- first action, unread aggregation, post-read reset, and singular/plural messages;
- concurrent aggregate creation/update behavior;
- persistence and response mapping for social fields;
- 7-day read and 30-day unread retention boundaries;
- batch cleanup and isolation from existing system notifications;
- existing notification retrieval and mark-as-read behavior.

Documentation updates keep `openapi.yaml`, `database-schema.md`, `business-rules.md`, and the current chronological `progress.md` entry aligned with the implementation.
