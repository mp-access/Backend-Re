package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.projections.TaskFileOverview;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostFilter;

import java.util.List;
import java.util.Optional;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {

    @Transactional
    @PostFilter("hasRole(filterObject.courseURL + '-assistant') or filterObject.released")
    List<TaskFileOverview> findByTask_IdOrderByIdAscPathAsc(Long taskId);

    @Transactional
    List<TaskFile> findByTask_Id(Long taskId);

    Optional<TaskFile> findByTask_IdAndTemplatePath(Long taskId, String path);
}