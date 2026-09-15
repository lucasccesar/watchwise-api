package com.watchwise.watchwise_api.pickstemplate.repository;
import com.watchwise.watchwise_api.pickstemplate.entity.PicksTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PicksTemplateRepository extends JpaRepository<PicksTemplate, UUID> {}
