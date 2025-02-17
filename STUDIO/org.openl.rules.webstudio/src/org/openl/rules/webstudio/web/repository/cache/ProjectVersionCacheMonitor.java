package org.openl.rules.webstudio.web.repository.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import org.openl.rules.common.ProjectVersion;
import org.openl.rules.project.abstraction.AProject;
import org.openl.rules.repository.api.BranchRepository;
import org.openl.rules.repository.api.Repository;
import org.openl.rules.security.SimpleGroup;
import org.openl.rules.security.SimpleUser;
import org.openl.rules.workspace.dtr.DesignTimeRepository;
import org.openl.util.StringUtils;

public class ProjectVersionCacheMonitor implements Runnable, InitializingBean {

    private final Logger log = LoggerFactory.getLogger(ProjectVersionCacheMonitor.class);

    private ScheduledExecutorService scheduledPool;
    private ProjectVersionH2CacheDB projectVersionCacheDB;
    private ProjectVersionCacheManager projectVersionCacheManager;
    private DesignTimeRepository designRepository;

    private final Authentication relevantSystemWideGrantedAuthority;

    private final static int PERIOD = 10;

    public ProjectVersionCacheMonitor(GrantedAuthority relevantSystemWideGrantedAuthority) {
        SimpleGroup group = new SimpleGroup();
        group.setName(relevantSystemWideGrantedAuthority.getAuthority());
        SimpleUser principal = SimpleUser.builder().setUsername("admin").setPrivileges(List.of(group)).build();
        this.relevantSystemWideGrantedAuthority = new UsernamePasswordAuthenticationToken(principal,
                "",
                principal.getAuthorities());
    }

    @Override
    public void run() {
        Authentication oldAuthentication = SecurityContextHolder.getContext().getAuthentication();
        try {
            SecurityContextHolder.getContext().setAuthentication(relevantSystemWideGrantedAuthority);
            try {
                if (!projectVersionCacheManager.isCacheCalculated()) {
                    recalculateDesignRepositoryCache();
                }
            } catch (Exception e) {
                log.error("Error during project caching", e);
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(oldAuthentication);
        }
    }

    private void recalculateDesignRepositoryCache() throws IOException, InterruptedException {
        Collection<? extends AProject> projects = designRepository.getProjects();
        for (AProject project : projects) {
            if (project.isDeleted()) {
                continue;
            }
            cacheDesignProject(project);
            Thread.yield();
        }
        projectVersionCacheDB.setCacheCalculatedState(true);
    }

    private void cacheDesignProject(AProject project) throws IOException, InterruptedException {
        Repository repository = designRepository.getRepository(project.getRepository().getId());
        List<ProjectVersion> versions = project.getVersions();
        if (repository.supports().branches()) {
            for (String branch : ((BranchRepository) repository).getBranches(project.getFolderPath())) {
                versions.addAll(new AProject(((BranchRepository) repository).forBranch(branch), project.getFolderPath())
                        .getVersions());
            }
        } else {
            versions.addAll(project.getVersions());
        }
        versions.sort(Comparator.comparing(p -> p.getVersionInfo().getCreatedAt(), Comparator.reverseOrder()));
        for (ProjectVersion projectVersion : versions) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Project monitor cache task is interrupted.");
            }
            if (projectVersion.isDeleted()) {
                continue;
            }

            String hash = projectVersionCacheDB.getHash(project.getBusinessName(),
                    projectVersion.getVersionName(),
                    projectVersion.getVersionInfo().getCreatedAt(),
                    ProjectVersionH2CacheDB.RepoType.DESIGN);
            if (StringUtils.isEmpty(hash)) {
                Repository repo = project.getRepository();
                String branch = repo.supports().branches() ? ((BranchRepository) repo).getBranch() : null;
                AProject designProject = designRepository.getProjectByPath(project.getRepository().getId(),
                        branch,
                        project.getRealPath(),
                        projectVersion.getVersionName());
                if (designProject.isDeleted()) {
                    continue;
                }
                cacheProjectVersion(designProject, ProjectVersionH2CacheDB.RepoType.DESIGN);
            }
        }
    }

    void cacheProjectVersion(AProject project, ProjectVersionH2CacheDB.RepoType repoType) throws IOException {
        String md5 = projectVersionCacheManager.computeMD5(project);
        projectVersionCacheDB.insertProject(project.getBusinessName(), project.getVersion(), md5, repoType);
    }

    public void setProjectVersionCacheDB(ProjectVersionH2CacheDB projectVersionCacheDB) {
        release();
        this.projectVersionCacheDB = projectVersionCacheDB;
    }

    public void setProjectVersionCacheManager(ProjectVersionCacheManager projectVersionCacheManager) {
        release();
        this.projectVersionCacheManager = projectVersionCacheManager;
    }

    public void setDesignRepository(DesignTimeRepository designRepository) {
        release();
        this.designRepository = designRepository;
    }

    @Override
    public void afterPropertiesSet() {
        if (projectVersionCacheDB != null && projectVersionCacheManager != null && designRepository != null) {
            scheduledPool = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Executors.defaultThreadFactory().newThread(r);
                t.setDaemon(true);
                return t;
            });
            scheduledPool.scheduleWithFixedDelay(this, 1, PERIOD, TimeUnit.SECONDS);
        }
    }

    /**
     * @see <a href=
     * "https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/ExecutorService.html">ExecutorService</a>
     */
    public synchronized void release() {
        if (scheduledPool == null) {
            return;
        }
        scheduledPool.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!scheduledPool.awaitTermination(PERIOD * 3, TimeUnit.SECONDS)) {
                scheduledPool.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!scheduledPool.awaitTermination(PERIOD * 3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Unable to terminate project version cache monitor task.");
                }
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            scheduledPool.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
        scheduledPool = null;
    }
}
