package ch.uzh.ifi.access.repository;

import ch.uzh.ifi.access.model.TaskFile;
import ch.uzh.ifi.access.model.constants.FilePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.security.access.prepost.PostFilter;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

public interface TaskFileRepository extends JpaRepository<TaskFile, Long> {

    @Transactional
    @PostFilter("not filterObject.permission.restricted")
    List<TaskFile> findByTask_IdOrderByIdDesc(Long taskId);

    Optional<TaskFile> findByTask_IdAndPath(Long taskId, String filePath);

    Optional<TaskFile> findTopByTask_IdAndPermissionOrderByIdDesc(Long taskId, FilePermission permission);

    List<TaskFile> findByTask_IdAndPermissionIn(Long taskId, List<FilePermission> permissions);
}