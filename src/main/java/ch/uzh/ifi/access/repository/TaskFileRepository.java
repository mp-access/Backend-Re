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
    @PostFilter("hasRole(filterObject.task.assignment.course.url + '-assistant') or not filterObject.permission.restricted")
    List<TaskFile> findByTask_IdOrderByPermissionAscNameAsc(Long taskId);

    Optional<TaskFile> findByTask_IdAndPath(Long taskId, String filePath);

    List<TaskFile> findByTask_IdAndPermissionIn(Long taskId, List<FilePermission> permissions);
}