package com.watchwise.watchwise_api.pick.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.pick.dto.PickListCardDTO;
import com.watchwise.watchwise_api.pick.service.MyPicksListService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class MyPicksListController {
    private final MyPicksListService myPicksListService;

    @GetMapping("/users/me/picks/list")
    public PageResponseDTO<PickListCardDTO> list(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        UUID viewerId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return PageResponseDTO.of(myPicksListService.list(viewerId, page, size));
    }
}
