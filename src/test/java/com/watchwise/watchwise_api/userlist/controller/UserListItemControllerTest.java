package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.userlist.dto.UserListItemBulkCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemCreationDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserListItemControllerTest {

    @Mock
    private UserListItemService userListItemService;

    @InjectMocks
    private UserListItemController userListItemController;

    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[addItem] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenAddingItem() {
        UUID listId = UUID.randomUUID();
        UserListItemCreationDTO creationDTO = new UserListItemCreationDTO(null, UUID.randomUUID(), null, null);
        UserListItemResponseDTO dto = buildResponseDto();
        when(userListItemService.addItem(currentUserId, listId, creationDTO)).thenReturn(dto);

        ResponseEntity<UserListItemResponseDTO> result = userListItemController.addItem(listId, creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[addItem] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenAddingItem() {
        UUID listId = UUID.randomUUID();
        UserListItemCreationDTO creationDTO = new UserListItemCreationDTO(null, UUID.randomUUID(), null, null);
        when(userListItemService.addItem(currentUserId, listId, creationDTO)).thenReturn(buildResponseDto());

        userListItemController.addItem(listId, creationDTO);

        verify(userListItemService).addItem(currentUserId, listId, creationDTO);
    }

    @Test
    @DisplayName("[addItems] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenAddingItems() {
        UUID listId = UUID.randomUUID();
        UserListItemBulkCreationDTO bulkDto = new UserListItemBulkCreationDTO(List.of(contentRefCreation("550")));
        List<UserListItemResponseDTO> dtos = List.of(buildResponseDto());
        when(userListItemService.addItems(currentUserId, listId, bulkDto)).thenReturn(dtos);

        ResponseEntity<List<UserListItemResponseDTO>> result = userListItemController.addItems(listId, bulkDto);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dtos);
    }

    @Test
    @DisplayName("[addItems] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenAddingItems() {
        UUID listId = UUID.randomUUID();
        UserListItemBulkCreationDTO bulkDto = new UserListItemBulkCreationDTO(List.of(contentRefCreation("550")));
        when(userListItemService.addItems(currentUserId, listId, bulkDto)).thenReturn(List.of(buildResponseDto()));

        userListItemController.addItems(listId, bulkDto);

        verify(userListItemService).addItems(currentUserId, listId, bulkDto);
    }

    @Test
    @DisplayName("[removeItem] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenRemovingItem() {
        UUID listId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        ResponseEntity<Void> result = userListItemController.removeItem(listId, itemId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[removeItem] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenRemovingItem() {
        UUID listId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        userListItemController.removeItem(listId, itemId);

        verify(userListItemService).removeItem(currentUserId, listId, itemId);
    }

    private UserListItemResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        return new UserListItemResponseDTO(UUID.randomUUID(), null, null, 1, null, now, now);
    }

    private ContentRefCreationDTO contentRefCreation(String tmdbId) {
        return new ContentRefCreationDTO(tmdbId, ContentType.MOVIE, null, null, null, null, null);
    }
}
