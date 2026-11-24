package com.watchwise.watchwise_api.userlist.controller;

import com.watchwise.watchwise_api.userlist.dto.UserListEditorDTO;
import com.watchwise.watchwise_api.userlist.service.impl.UserListEditorReader;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class UserListEditorController {
    private final UserListEditorReader reader;

    @GetMapping("/lists/{listId}/editor")
    public UserListEditorDTO getEditor(@AuthenticationPrincipal UUID viewerId, @PathVariable UUID listId) {
        return reader.read(viewerId, listId);
    }
}
