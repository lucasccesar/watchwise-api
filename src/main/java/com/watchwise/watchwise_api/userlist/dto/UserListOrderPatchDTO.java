package com.watchwise.watchwise_api.userlist.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UserListOrderPatchDTO(
        @NotBlank @Size(max = 19) @Pattern(regexp = "^(0|[1-9][0-9]{0,18})$") String revision,
        @NotEmpty @Size(max = 100) List<@Valid UserListOrderMoveDTO> moves) {
}
