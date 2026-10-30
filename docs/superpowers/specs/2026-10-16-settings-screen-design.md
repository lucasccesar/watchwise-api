# Settings Screen Backend Design

## Goal

Provide the backend contracts required by the Settings screen: a focused initial read, user-block
management, URL-based profile media, and safe reuse of the existing profile, credential, follow-request,
session, and account-deletion mutations.

## Scope

The feature contains two cooperating modules:

1. **Settings read model**: `GET /users/me/settings` returns only data needed by the screen.
2. **User blocks**: an explicit directional `UserBlock` relation manages the Blocked users section and
   prevents the blocked social interactions shown by the screen.

The existing `GET /users/me`, `PATCH /users/me`, follow-request endpoints, logout endpoints, and
`DELETE /users/me` remain compatible and continue to own their current write semantics.

## Decisions

### Settings read model

Add an authenticated additive route:

```http
GET /users/me/settings
```

It returns:

```java
public record SettingsViewDTO(
        SettingsProfileDTO profile,
        SettingsPrivacyDTO privacy,
        SettingsContentPreferencesDTO contentPreferences,
        SettingsAccountDTO account) {}
```

`SettingsProfileDTO` owns the editable/displayed profile fields, including profile visibility.
`SettingsPrivacyDTO` contains accepted follower/following counts and at most ten newest pending follow-request
previews and blocked-user previews, each accompanied by its full count. `SettingsContentPreferencesDTO`
contains `preferredLanguage` and `preferredRegion`. `SettingsAccountDTO` contains only the current email.

The route must not reuse `UserServiceImpl.getCurrentUser`, because that method calculates diary and genre
statistics unused by Settings. It loads the current user once and uses bounded repository reads for
privacy previews. It never returns passwords, role, refresh tokens, session timestamps, or asset object keys.

### User blocks

Use a separate `user_blocks` table; do not overload `followers.status`.

```sql
user_blocks (
  id UUID primary key,
  blocker_id UUID not null references users(id) on delete cascade,
  blocked_id UUID not null references users(id) on delete cascade,
  created_at timestamp not null,
  unique (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
)
```

The routes are:

```http
GET    /users/me/blocked?page=1&size=20
POST   /users/me/blocked/{userId}
DELETE /users/me/blocked/{userId}
```

Blocking and unblocking are idempotent and never notify the target. Creating a block removes pending or
accepted follower relations in both directions. Either-direction block prevents a new follow request,
acceptance of a pending request, a comment/reply on a user-owned target, and visibility of either user’s
comments to the other. The user-block predicate is evaluated in database queries before pagination, so a
blocked comment does not create a short page or inaccurate total.

The feature deliberately does not change global content visibility, historical notification retention,
or unrelated recommendations. Those policies are not required by the Settings design and would expand the
privacy model beyond the user-facing promise.

### Profile media URLs

`profilePicture` and `banner` remain URL values. The frontend obtains or hosts each image outside the
API, then sends the canonical URL in `PATCH /users/me`. The backend keeps the existing URL format and length
validation; it does not receive bytes, store files, call an object-storage provider, or manage file lifecycle.

Add only:

```http
DELETE /users/me/banner
```

The route clears the nullable `banner` field and is idempotent. It is needed because the patch contract treats
null as “field omitted” and therefore cannot safely express banner removal without changing the existing patch
semantics. There is no equivalent profile-picture deletion route because the screen does not offer that action
and users always retain the configured default profile picture.

### Existing mutations

`PATCH /users/me` remains the command for name, username, description, visibility, language, region,
email, password, and legacy URL fields. A section save sends only changed fields. Email and password
changes continue to require `currentPassword`, use the current lockout policy, and invalidate other
sessions after commit. This matches the warning already present in the screen.

`POST /auth/logout`, `POST /auth/logout-all`, and `DELETE /users/me` already satisfy the session and
account controls. A session/device list and verified-email-change workflow are intentionally out of scope:
the design displays neither devices nor email-verification state.

## Error and security contract

- All Settings reads and mutations require authentication; every mutating route retains CSRF protection.
- Invalid block target, self-block, invalid banner operation, or malformed paging returns the project
  `ApiError` shape.
- A blocked interaction returns `403` without revealing whether the counterpart created the block.
- The backend validates only the URL values supplied to `PATCH /users/me`; image availability and hosting are
  owned by the frontend or its chosen media provider.

## Verification

Tests cover bounded settings reads, pending/block preview counts, no exposure of credentials, block races,
relationship cleanup, comment filtering before pagination, follow/comment denial, banner removal,
CSRF/authentication, raw-route compatibility, and the complete Maven suite.
