# Search Controller Design

## Goal

Expose the already implemented Search aggregation through the authenticated
`GET /search` endpoint and prove its HTTP contract at the controller boundary.

## Scope

Add a request-parameter DTO and `SearchController` under the existing `search`
feature package. The controller will accept `q`, optional `type`, optional
1-based `page`, and optional `size`, resolve the authenticated viewer UUID from
the security context, trim the query, and delegate to
`SearchService.search(UUID, String, SearchType, Integer, Integer)`.

The response remains `SearchResultDTO`. It must not be wrapped in
`PageResponseDTO`, because Search contains independent result arrays from TMDB
and local database sources rather than one Spring `Page` with a single total.

## Validation and error handling

- `q` is validated with `@Valid`, `@NotBlank`, and `@Size(min = 3)`.
- The controller additionally checks the trimmed value, so a value whose raw
  length is at least three but whose trimmed length is shorter is rejected.
- `page`, when present, must be at least `1`.
- `size`, when present, must be between `1` and `20`.
- `type` is converted to `SearchType`; invalid enum input must become the
  existing `ApiError` response through `GlobalExceptionHandler`.
- Validation and conversion failures must produce the existing
  `ApiError`/`ValidationApiError` shapes rather than Spring's default error
  body.
- `TmdbUnavailableException` remains a service concern and must be mapped by
  the existing handler to `502 Bad Gateway`.
- Authentication is enforced by the existing `SecurityConfig`; the controller
  never accepts a viewer ID from the client.

## Design alternatives

1. Validate raw `@RequestParam` values manually in the controller. This keeps
   the method signature small, but duplicates field constraints and makes the
   validation contract less reusable.
2. Enable method validation with `@Validated` on controller parameters. This
   is generic, but requires additional handling for
   `HandlerMethodValidationException` and risks a framework error shape if the
   handler is missed.
3. Use a validated `@ModelAttribute` request DTO. This is the selected design:
   it keeps the controller interface focused, reuses the existing validation
   exception handling, and leaves query normalization at the HTTP seam.

## Testing

Add unit tests for successful delegation, viewer resolution, query trimming,
and rejection before the service is called. Add controller integration tests
with MockMvc for:

- `200` and the four-array `SearchResultDTO` body;
- `400` for missing or too-short queries, including the post-trim case;
- `400` for invalid type, page, and size values;
- `401` without an access-token cookie;
- `502` when the mocked Search service raises `TmdbUnavailableException`;
- absence of Spring's default `ProblemDetail` fields in handled errors.

## Documentation

Because this is a shipped endpoint, append the implementation to the current
day in `docs/context/progress.md` and add the implemented Search visibility and
read-only aggregation rules to `docs/context/business-rules.md`. The existing
OpenAPI Search contract is already prepared and should only change if the
implementation reveals a genuine mismatch.
