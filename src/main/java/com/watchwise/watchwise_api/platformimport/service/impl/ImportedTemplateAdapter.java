package com.watchwise.watchwise_api.platformimport.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.pick.dto.PickContentTargetDTO;
import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.*;
import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import com.watchwise.watchwise_api.platformimport.model.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ImportedTemplateAdapter {
    public PicksTemplateCreationDTO toCreation(TemplateImportDocument document, List<List<ImportTarget>> resolvedTargets) {
        if (document == null || resolvedTargets == null || resolvedTargets.size() != document.categories().size())
            throw new BadRequestException("Template resolutions do not match categories");
        List<PicksTemplateCategoryCreationDTO> categories = new ArrayList<>();
        for (int categoryIndex = 0; categoryIndex < document.categories().size(); categoryIndex++) {
            ImportedTemplateCategory source = document.categories().get(categoryIndex);
            List<ImportTarget> targets = resolvedTargets.get(categoryIndex);
            if (targets == null || targets.size() != source.nominees().size() || targets.stream().anyMatch(t -> t == null))
                throw new BadRequestException("Every fixed nominee requires an explicit resolution");
            List<PicksTemplateOptionCreationDTO> options = targets.stream()
                    .map(target -> new PicksTemplateOptionCreationDTO(toTarget(target))).toList();
            categories.add(new PicksTemplateCategoryCreationDTO(source.name(), source.description(), source.group(),
                    source.displayOrder(), source.type(), source.nomineesPresent()
                    ? PickCategoryOptionMode.FIXED : PickCategoryOptionMode.OPEN, options));
        }
        return new PicksTemplateCreationDTO(document.title(), document.description(), document.coverImage(),
                document.instructions(), document.eligibilityStart(), document.eligibilityEnd(), List.copyOf(categories));
    }

    private PickTargetDTO toTarget(ImportTarget target) {
        if (target instanceof ImportTarget.PersonTarget person) {
            return new PickTargetDTO(null, person.personTmdbId(), content(person.contextContent()));
        }
        return new PickTargetDTO(content(((ImportTarget.ContentTarget) target).content()), null, null);
    }

    private PickContentTargetDTO content(MediaReference reference) {
        if (reference == null) return null;
        return new PickContentTargetDTO(PickAllowedType.valueOf(reference.type().name()), reference.tmdbId(),
                reference.seriesTmdbId(), reference.seasonNumber(), reference.episodeNumber());
    }
}
