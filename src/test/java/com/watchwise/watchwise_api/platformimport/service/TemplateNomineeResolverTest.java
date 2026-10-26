package com.watchwise.watchwise_api.platformimport.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.platformimport.model.*;
import com.watchwise.watchwise_api.platformimport.service.impl.TemplateNomineeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TemplateNomineeResolverTest {
    private final ExternalContentAliasService aliases = mock(ExternalContentAliasService.class);
    private final TemplateNomineeResolver resolver = new TemplateNomineeResolver(aliases);
    private final JsonMapper json = new JsonMapper();
    private final UUID owner = UUID.randomUUID();

    @Test
    @DisplayName("[resolve] Should Use Person Namespace Without Inventing Context - When Nominee Is A Name")
    void personNameUsesPersonNamespaceWithoutInventingContext() throws Exception {
        AliasQuery query = AliasQuery.person("Carrie Fisher");
        when(aliases.resolve(owner, query)).thenReturn(AliasResolution.matched(new ImportTarget.PersonTarget("6384", null)));
        var result = resolver.resolve(owner, PickAllowedType.PERSON, json.readTree("\"Carrie Fisher\""));
        assertThat(result.status()).isEqualTo(AliasResolution.Status.MATCHED);
        assertThat(result.candidates()).containsExactly(new ImportTarget.PersonTarget("6384", null));
        verify(aliases).resolve(owner, query);
    }

    @Test
    @DisplayName("[resolve] Should Preserve Explicit Person Context - When Resolving A Name")
    void personNamePreservesExplicitContext() throws Exception {
        AliasQuery query = AliasQuery.person("Carrie Fisher");
        when(aliases.resolve(owner, query)).thenReturn(AliasResolution.matched(
                new ImportTarget.PersonTarget("6384", null)));

        var result = resolver.resolve(owner, PickAllowedType.PERSON, json.readTree("""
                {"name":"Carrie Fisher","contextContent":{"type":" series ","tmdbId":"1396"}}
                """));

        assertThat(result.status()).isEqualTo(AliasResolution.Status.MATCHED);
        assertThat(result.candidates()).containsExactly(new ImportTarget.PersonTarget(
                "6384", MediaReference.series("1396")));
        verify(aliases).resolve(owner, query);
    }

    @Test
    @DisplayName("[resolve] Should Normalize Explicit Person Context Type - When It Has Accepted Case And Whitespace")
    void personIdAcceptsNormalizedContextType() throws Exception {
        var result = resolver.resolve(owner, PickAllowedType.PERSON, json.readTree("""
                {"personTmdbId":"6384","contextContent":{"type":" series ","tmdbId":"1396"}}
                """));

        assertThat(result.candidates()).containsExactly(new ImportTarget.PersonTarget(
                "6384", MediaReference.series("1396")));
        verifyNoInteractions(aliases);
    }

    @Test
    @DisplayName("[resolve] Should Keep Episode Query Unresolved - When No Known Coordinates Exist")
    void episodeStringWithoutKnownCoordinatesRemainsUnresolved() throws Exception {
        AliasQuery query = new AliasQuery("Pilot", null, com.watchwise.watchwise_api.content.entity.ContentType.EPISODE);
        when(aliases.resolve(owner, query)).thenReturn(AliasResolution.notFound());
        assertThat(resolver.resolve(owner, PickAllowedType.EPISODE, json.readTree("\"Pilot\"")).status())
                .isEqualTo(AliasResolution.Status.NOT_FOUND);
        verify(aliases).resolve(owner, query);
    }

    @Test
    @DisplayName("[resolve] Should Use Composite Episode Identity - When Coordinates Are Provided")
    void episodeCoordinatesSkipDiscoveryButKeepTypedIdentity() throws Exception {
        var result = resolver.resolve(owner, PickAllowedType.EPISODE,
                json.readTree("{\"seriesTmdbId\":\"1396\",\"seasonNumber\":1,\"episodeNumber\":2}"));
        assertThat(result.candidates()).containsExactly(new ImportTarget.ContentTarget(MediaReference.episode("1396", 1, 2)));
        verifyNoInteractions(aliases);
    }

    @Test
    @DisplayName("[resolve] Should Scope Movie Search - When Title And Year Are Provided")
    void movieTitleIsScopedToMovieQuery() throws Exception {
        AliasQuery query = new AliasQuery("Alien", 1979, com.watchwise.watchwise_api.content.entity.ContentType.MOVIE);
        when(aliases.resolve(owner, query)).thenReturn(AliasResolution.ambiguous(List.of(
                new ImportTarget.ContentTarget(MediaReference.movie("348")),
                new ImportTarget.ContentTarget(MediaReference.movie("999")))));
        assertThat(resolver.resolve(owner, PickAllowedType.MOVIE,
                json.readTree("{\"title\":\"Alien\",\"releaseYear\":1979}")).status())
                .isEqualTo(AliasResolution.Status.AMBIGUOUS);
        verify(aliases).resolve(owner, query);
    }
}
