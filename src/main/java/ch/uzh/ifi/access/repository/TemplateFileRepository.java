package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.TemplateFile;
import ch.uzh.ifi.access.projections.TemplateOverview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TemplateFileRepository extends JpaRepository<TemplateFile, Long> {

    Optional<TemplateFile> findByPath(String path);

    List<TemplateOverview> findAllByOrderByPath();
}