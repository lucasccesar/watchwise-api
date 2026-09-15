package com.watchwise.watchwise_api.pickstemplate.repository;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplateCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PicksTemplateCategoryRepository extends JpaRepository<PicksTemplateCategory, UUID> {}
