package com.watchwise.watchwise_api.person.service;

import com.watchwise.watchwise_api.person.dto.PersonPreviewDTO;

import java.util.Collection;
import java.util.Map;

public interface PersonPreviewResolver {

    Map<String, PersonPreviewDTO> resolve(Collection<String> personTmdbIds);
}
