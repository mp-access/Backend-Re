# Start the engine:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) API_KEY=1234 WORKING_DIR="$HOME/Documents/UZH/Master/master_project/access/data" AUTH_SERVER_URL=http://localhost:8080 ./gradlew bootRun
```

With pools:
```bash
DOCKER_POOL_ENABLED=true DOCKER_POOL_IMAGE=python:latest JAVA_HOME=$(/usr/libexec/java_home -v 21) API_KEY=1234 WORKING_DIR="$HOME/Documents/UZH/Master/master_project/access/data" AUTH_SERVER_URL=http://localhost:8080 ./gradlew bootRun
```

Running all the Tests
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) API_KEY=1234 WORKING_DIR="$HOME/Documents/UZH/Master/master_project/access/data" AUTH_SERVER_URL=http://localhost:8080 ./gradlew cleanTest test
```

## Docker Engine update

To add the feature of Docker pools for faster loading and preparation speeds. We added the following classes:

![Docker Container class diagram](documentation/ContainerPoolClass.png)

There are now additional files found here: ```src/main/kotlin/ch.uzh.ifi.access/service```
- DockerContainer.kt
- DockerContainerInstance.kt
- DockerPoolService

### Performance increase for peak loads

When we compare a performance test to the regular running performance for an individual container, we realize that there is a severe bottle neck.

![Performance measures #1](documentation/latency_comparison.png)
Comparing isolated vs load

Now after the implementation of the pooled containers we have the following:

![Performance measures pooled vs # 1](documentation/pool_vs_oneshot_load.png)
Performance differences in pooled approach vs individual container launch.

And if we get the diagram from the beginning we get substantially different results. Suggesting that the pooled approach is indeed the faster approach.

![Performance measures pooled](documentation/pool_isolated_vs_load.png)
Comparing isolated vs load


## The getTask() problem

1. Solution, super simple, we could add the following properties to the DB structure:
   ```
   CREATE INDEX IF NOT EXISTS idx_task_slug ON task (slug);
   CREATE INDEX IF NOT EXISTS idx_task_assignment_id ON task (assignment_id);
   CREATE INDEX IF NOT EXISTS idx_assignment_slug ON assignment (slug);
   CREATE INDEX IF NOT EXISTS idx_assignment_course_id ON assignment (course_id);
   ```
2. Using caching, we could add a cache that records the Slug to ID resolution.
   ```
   @Query("select t.id from Task t " +
       "where t.assignment.course.slug = ?1 and t.assignment.slug = ?2 and t.slug = ?3")
   fun findTaskId(courseSlug: String, assignmentSlug: String, taskSlug: String): Long?
   ```

## The User Priviledge problem

I tackled it through the usage of 1000:1000 priviledge.

But a problem remains: We only delete the /workspace and /submission directory.

Therefore I added a read only for all rootfs

TODO: Debug for the /workspace and /tmp directories. Test a dry run and check if the containers work.

## The online counter

Goal: We want to spin up more containers if more are online. There will be a limit of 100 consecutive containers.

## Student counter problem and consecutive submissions

How do we tackle this?

1. An online counter
2. A reactive system that spins up containers
3. We leave the containers for now all running.

Some percentage of people with a floor of containers.

TODO: Remeasure performance with the new getTaskBySlug() function.

## Re-Evaluation after security + get slug update

No significant changes its all within the regular running delta.

# Further security issues

1. Priviledge escalation
   - Simply the fact that there is a root in the container and commands such as su, mount and passwd are not blocked
2. We should prevent the general linux capability...
   - cap-drop ALL
3. Add a CPU limit
   - Prevent a fork bomb or


### Container hardening — what was changed

The pooled containers (`DockerPoolService.createContainer`) now run with the following, verified by `DockerPoolServiceTests`:

- **Non-root workload** — student code runs via `docker exec --user 1000:1000` (`docker.pool.user`). Checked fail-closed at creation (`effectiveUid()`): a container resolving to uid 0 is destroyed and never pooled.
- **Read-only root filesystem** (`docker.pool.readOnlyRootfs`, default `true`) + tmpfs `/workspace` and `/tmp`. Only those two plus the bound `/submission` are writable, and all are wiped by `reset()` — nothing a student writes survives into the next borrower.
- **no-new-privileges** — blocks setuid binaries (`su`, `mount`, ...) from escalating. This is the gap read-only rootfs alone does *not* close, since base images ship setuid-root binaries.
- **Capabilities: drop ALL, add back only `KILL`, `FOWNER`, `DAC_OVERRIDE`** — the three the root maintenance execs need (kill workload processes; chmod/rm host-owned and sticky files). The non-root workload holds no effective capabilities regardless.
- **pids-limit** (`docker.pool.pidsLimit`, default 256) — fork-bomb guard.
- **CPU ceiling** (`docker.pool.cpuLimit`, default 2.0 cores, CFS quota) — one submission cannot peg every host core.
- **Network `none`** (pre-existing) — no route to the backend/DB, which is what blocked the "read all students' results over the network" scenario.

**Not addressed (shared-kernel ceiling):** a real breakout via a kernel exploit remains possible with standard containers regardless of the above. For true isolation of untrusted code, the next step is a sandboxed runtime — gVisor (`runsc`) or a microVM (Kata / Firecracker).

TODO: dry-run real course images under read-only rootfs — watch for images that need `$HOME` or other writable paths (e.g. Python cache dirs), which the mock `python:latest` tasks won't surface.

## Serious Bug fix on 12. August

To prepare for the launch to the staging server I found a bug in the pipeline where the pipeline would default back to 
the previous docker system when 200+ concurrent submissions where reached. This was due to a limitation of Tomcat that
I was not aware of. It is fixed and there is an upper limit of 2000 concurrent submissions now.
