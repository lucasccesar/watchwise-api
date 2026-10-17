## Task 1: Define the visual API types and controller boundary

**Files:**
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/SearchViewScope.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchViewRequestDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchContentViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/dto/SearchPersonViewDTO.java`
- Create: `src/main/java/com/watchwise/watchwise_api/search/service/SearchViewService.java`
- Test: `src/test/java/com/watchwise/watchwise_api/search/dto/SearchViewRequestDTOTest.java`

**Interfaces:**
- Produces `SearchViewService.search(UUID viewerId, String query, SearchViewScope scope, int page, int size): SearchViewDTO`.
- Produces the visual request/response types consumed by the later controller integration task without changing `GET /search`.

- [ ] **Step 1: Write the failing DTO validation tests**

~~~java
@Test
void shouldRejectSizeAboveTwenty() {
    Set<ConstraintViolation<SearchViewRequestDTO>> violations = validator.validate(
            new SearchViewRequestDTO("night", SearchViewScope.CONTENT, 1, 21));

    assertThat(violations).extracting(ConstraintViolation::getMessage)
            .contains("size must be less than or equal to 20");
}

@Test
void shouldRequireVisualScope() {
    Set<ConstraintViolation<SearchViewRequestDTO>> violations = validator.validate(
            new SearchViewRequestDTO("night", null, 1, 18));

    assertThat(violations).extracting(ConstraintViolation::getMessage)
            .contains("scope must not be null");
}
~~~

- [ ] **Step 2: Run the DTO test to verify it fails**

Run: `./mvnw.cmd test "-Dtest=SearchViewRequestDTOTest"`

Expected: compilation failure because `SearchViewRequestDTO` and `SearchViewScope` do not exist.

- [ ] **Step 3: Define the request, response, and service interface**

~~~java
public record SearchViewRequestDTO(
        @NotBlank @Size(min = 3, max = 100) String q,
        @NotNull SearchViewScope scope,
        @Min(1) @Max(500) Integer page,
        @Min(1) @Max(20) Integer size) { }

public interface SearchViewService {
    SearchViewDTO search(UUID viewerId, String query, SearchViewScope scope, int page, int size);
}
~~~

Use the explicit response fields from the design. `SearchContentViewDTO` holds `String tmdbId`, `MovieOrSeriesType type`, `String title`, `String posterUrl`, `Integer releaseYear`, `List<String> genres`, `Integer runtimeMinutes`, `Integer numberOfSeasons`, `Double voteAverage`, `ContentCardViewerStateDTO viewerState`, and `ContentPreviewStatus previewStatus`. `SearchPersonViewDTO` holds `String tmdbId`, `String name`, `String photoUrl`, and `String knownForDepartment`.

- [ ] **Step 4: Add DTO tests for trimmed boundaries and defaults**

~~~java
assertThat(validator.validate(new SearchViewRequestDTO(" " + "n".repeat(100) + " ",
        SearchViewScope.CONTENT, 1, 18))).isEmpty();
assertThat(validator.validate(new SearchViewRequestDTO("night", SearchViewScope.CONTENT, 0, 18)))
        .isNotEmpty();
~~~

The request DTO must not reject a 100-character trimmed query merely because surrounding whitespace makes the raw parameter longer. The controller will enforce the post-trim maximum when the route is wired in Task 6.

- [ ] **Step 5: Run the DTO tests**

Run: `./mvnw.cmd test "-Dtest=SearchViewRequestDTOTest"`

Expected: PASS, with the boundary assertions and no Spring application-context change.

- [ ] **Step 6: Commit the API contract**

~~~powershell
git add src/main/java/com/watchwise/watchwise_api/search src/test/java/com/watchwise/watchwise_api/search
git commit -m "feat(search): add visual search endpoint contract"
git show -s --format=%B HEAD
~~~

Expected: the commit message has no `Co-Authored-By` trailer or other self-attribution.

