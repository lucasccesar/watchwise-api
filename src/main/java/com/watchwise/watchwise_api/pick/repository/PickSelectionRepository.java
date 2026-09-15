package com.watchwise.watchwise_api.pick.repository;
import com.watchwise.watchwise_api.pick.entity.PickSelection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PickSelectionRepository extends JpaRepository<PickSelection, UUID> {}
