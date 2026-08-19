package com.watchwise.watchwise_api.userlist.mapper;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.mapper.UserMapper;
import com.watchwise.watchwise_api.userlist.dto.UserListItemResponseDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListPreviewDTO;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.entity.UserListItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserListItemMapperTest {

    private UserListItemMapper userListItemMapper;

    @BeforeEach
    void setUp() {
        UserListItemMapperImpl mapper = new UserListItemMapperImpl();
        ReflectionTestUtils.setField(mapper, "contentMapper", Mappers.getMapper(ContentMapper.class));
        ReflectionTestUtils.setField(mapper, "userMapper", Mappers.getMapper(UserMapper.class));
        userListItemMapper = mapper;
    }

    @Test
    @DisplayName("[userListItemToResponseDto] Should Map A Content Item - When ChildList Is Null")
    void shouldMapAContentItemWhenChildListIsNull() {
        LocalDateTime now = LocalDateTime.now();
        Content content = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        UserListItem item = UserListItem.builder()
                .id(UUID.randomUUID())
                .userList(buildUserList(UUID.randomUUID(), "lucas"))
                .content(content)
                .position(1)
                .description("Best plot twist")
                .createdAt(now)
                .updatedAt(now)
                .build();

        UserListItemResponseDTO result = userListItemMapper.userListItemToResponseDto(item);

        assertThat(result.id()).isEqualTo(item.getId());
        assertThat(result.content().id()).isEqualTo(content.getId());
        assertThat(result.content().tmdbId()).isEqualTo("550");
        assertThat(result.childList()).isNull();
        assertThat(result.position()).isEqualTo(1);
        assertThat(result.description()).isEqualTo("Best plot twist");
        assertThat(result.createdAt()).isEqualTo(now);
        assertThat(result.updatedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("[userListItemToResponseDto] Should Map A Nested List Item - When Content Is Null")
    void shouldMapANestedListItemWhenContentIsNull() {
        LocalDateTime now = LocalDateTime.now();
        UUID childOwnerId = UUID.randomUUID();
        UserList childList = buildUserList(childOwnerId, "marina");

        UserListItem item = UserListItem.builder()
                .id(UUID.randomUUID())
                .userList(buildUserList(UUID.randomUUID(), "lucas"))
                .childList(childList)
                .position(2)
                .createdAt(now)
                .updatedAt(now)
                .build();

        UserListItemResponseDTO result = userListItemMapper.userListItemToResponseDto(item);

        assertThat(result.content()).isNull();
        assertThat(result.childList().id()).isEqualTo(childList.getId());
        assertThat(result.childList().name()).isEqualTo(childList.getName());
        assertThat(result.childList().isPublic()).isTrue();
        assertThat(result.childList().user().id()).isEqualTo(childOwnerId);
        assertThat(result.childList().user().username()).isEqualTo("marina");
        assertThat(result.description()).isNull();
    }

    @Test
    @DisplayName("[userListToPreviewDto] Should Return Null - When UserList Is Null")
    void shouldReturnNullWhenUserListIsNull() {
        UserListPreviewDTO result = userListItemMapper.userListToPreviewDto(null);

        assertThat(result).isNull();
    }

    private UserList buildUserList(UUID ownerId, String ownerUsername) {
        LocalDateTime now = LocalDateTime.now();
        User owner = User.builder()
                .id(ownerId)
                .username(ownerUsername)
                .email(ownerUsername + "@email.com")
                .password("hashed_password")
                .isProfilePublic(true)
                .createdAt(now)
                .updatedAt(now)
                .build();

        return UserList.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .name("Best sci-fi of the 90s")
                .isPublic(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}