package com.watchwise.watchwise_api.content.dto;

import java.util.List;

public record CrewMemberDTO(Integer id, String name, String profilePath, List<String> jobs) {
}
