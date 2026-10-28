package com.watchwise.watchwise_api.pickstemplate.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pick.service.PickTargetService;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCategoryCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateOptionCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreparedTemplateCreationServiceImplTest {

    @Test
    void rejectsMoreThan100DistinctTargetsBeforeResolvingThem() {
        UserRepository users = mock(UserRepository.class);
        PickTargetService targets = mock(PickTargetService.class);
        PreparedTemplateWriter writer = mock(PreparedTemplateWriter.class);
        PicksTemplateCreationValidator validator = mock(PicksTemplateCreationValidator.class);
        UUID actorId = UUID.randomUUID();
        when(users.findById(actorId)).thenReturn(Optional.of(User.builder().id(actorId).build()));
        var service = new PreparedTemplateCreationServiceImpl(users, targets, writer, validator, Clock.systemUTC());
        PicksTemplateCreationDTO request = new PicksTemplateCreationDTO("Awards", null, null, null, null, null,
                List.of(category("First", 0), category("Second", 51)));

        assertThatThrownBy(() -> service.prepare(actorId, request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A template preparation can contain at most 100 distinct target identities");

        verifyNoInteractions(targets, writer);
    }

    private PicksTemplateCategoryCreationDTO category(String name, int offset) {
        List<PicksTemplateOptionCreationDTO> options = IntStream.range(offset, offset + 51)
                .mapToObj(index -> new PicksTemplateOptionCreationDTO(
                        new PickTargetDTO(null, Integer.toString(index + 1), null)))
                .toList();
        return new PicksTemplateCategoryCreationDTO(name, null, PickCategoryGroup.PRIMARY,
                offset + 1, PickAllowedType.PERSON, PickCategoryOptionMode.FIXED, options);
    }
}
