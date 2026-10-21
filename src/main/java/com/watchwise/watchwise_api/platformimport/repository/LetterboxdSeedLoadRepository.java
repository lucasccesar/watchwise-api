package com.watchwise.watchwise_api.platformimport.repository;
import com.watchwise.watchwise_api.platformimport.entity.LetterboxdSeedLoad; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID;
public interface LetterboxdSeedLoadRepository extends JpaRepository<LetterboxdSeedLoad, UUID> { }
