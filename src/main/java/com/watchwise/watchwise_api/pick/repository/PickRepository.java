package com.watchwise.watchwise_api.pick.repository;
import com.watchwise.watchwise_api.pick.entity.Pick;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface PickRepository extends JpaRepository<Pick, UUID> {}
