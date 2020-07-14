package org.openl.rules.workspace.lw.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.openl.rules.common.ProjectException;
import org.openl.rules.project.abstraction.AProject;
import org.openl.rules.project.impl.local.LocalRepository;
import org.openl.rules.repository.api.FileData;
import org.openl.rules.repository.api.Repository;
import org.openl.rules.repository.exceptions.RRepositoryException;
import org.openl.rules.workspace.ProjectKey;
import org.openl.rules.workspace.WorkspaceUser;
import org.openl.rules.workspace.dtr.DesignTimeRepository;
import org.openl.rules.workspace.lw.LocalWorkspace;
import org.openl.rules.workspace.lw.LocalWorkspaceListener;

public class LocalWorkspaceImpl implements LocalWorkspace {

    private final WorkspaceUser user;
    private final File location;
    private final Map<ProjectKey, AProject> localProjects;
    private final List<LocalWorkspaceListener> listeners = new ArrayList<>();
    private final List<LocalRepository> repositories;
    private final DesignTimeRepository designTimeRepository;

    LocalWorkspaceImpl(WorkspaceUser user, File location, DesignTimeRepository designTimeRepository) {
        this.user = user;
        this.location = location;
        this.designTimeRepository = designTimeRepository;

        localProjects = new HashMap<>();
        repositories = new ArrayList<>();

        refreshRepositories();
        loadProjects();
    }

    private void refreshRepositories() {
        for (LocalRepository localRepository : repositories) {
            localRepository.close();
        }
        repositories.clear();

        File[] folders = location.listFiles();
        if (folders == null) {
            throw new IllegalArgumentException("Path " + location.getPath() + " does not denote a directory");
        }
        for (File folder : folders) {
            LocalRepository localRepository = new LocalRepository(folder);
            try {
                String id = folder.getName();
                localRepository.setId(id);
                if (designTimeRepository != null) {
                    Repository designRepository = designTimeRepository.getRepository(id);
                    if (designRepository != null) {
                        localRepository.setName(designRepository.getName());
                    }
                }
                localRepository.initialize();
            } catch (RRepositoryException e) {
                throw new IllegalStateException(e);
            }
            repositories.add(localRepository);
        }
    }

    @Override
    public void addWorkspaceListener(LocalWorkspaceListener listener) {
        listeners.add(listener);
    }

    @Override
    public LocalRepository getRepository(String id) {
        List<LocalRepository> repositories = getRepositories();
        for (LocalRepository repository : repositories) {
            if (id.equals(repository.getId())) {
                return repository;
            }
        }

        File repoBase = new File(location, id);
        LocalRepository repository = new LocalRepository(repoBase);
        repository.setId(id);
        if (designTimeRepository != null) {
            Repository designRepository = designTimeRepository.getRepository(id);
            if (designRepository != null) {
                repository.setName(designRepository.getName());
            }
        }
        try {
            repository.initialize();
        } catch (RRepositoryException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        repositories.add(repository);
        return repository;
    }

    @Override
    public List<LocalRepository> getRepositories() {
        return repositories;
    }

    @Override
    public File getLocation() {
        return location;
    }

    @Override
    public AProject getProject(String repositoryId, String name) throws ProjectException {
        AProject lp;
        synchronized (localProjects) {
            lp = localProjects.get(new ProjectKey(repositoryId, name.toLowerCase()));
        }
        if (lp == null) {
            throw new ProjectException("Cannot find project ''{0}''.", null, name);
        }

        return lp;
    }

    @Override
    public Collection<AProject> getProjects() {
        synchronized (localProjects) {
            return new ArrayList<>(localProjects.values());
        }
    }

    protected WorkspaceUser getUser() {
        return user;
    }

    @Override
    public boolean hasProject(String repositoryId, String name) {
        synchronized (localProjects) {
            return localProjects.get(new ProjectKey(repositoryId, name.toLowerCase())) != null;
        }
    }

    private void loadProjects() {
        for (LocalRepository localRepository : getRepositories()) {
            // TODO: Use full path instead of project name.
            // TODO: User rulesLocation instead of ""
            // TODO: LocalRepository.listFolders() should return the same FileData as
            //          localRepository.getProjectState(name).getProjectVersion()
            List<FileData> folders = localRepository.listFolders("");
            for (FileData folder : folders) {
                AProject lpi;
                String name = folder.getName();
                FileData fileData = localRepository.getProjectState(name).getFileData();
                if (fileData == null) {
                    String version = localRepository.getProjectState(name).getProjectVersion();
                    lpi = new AProject(localRepository, name, version);
                } else {
                    lpi = new AProject(localRepository, fileData);
                }
                synchronized (localProjects) {
                    localProjects.put(new ProjectKey(localRepository.getId(), name.toLowerCase()), lpi);
                }
            }
        }
    }

    @Override
    public void refresh() {
        // check existing
        synchronized (localProjects) {
            localProjects.clear();
            refreshRepositories();
        }
        loadProjects();
    }

    @Override
    public void release() {
        synchronized (localProjects) {
            localProjects.clear();
        }

        for (LocalRepository localRepository : repositories) {
            localRepository.close();
        }
        repositories.clear();

        for (LocalWorkspaceListener lwl : new ArrayList<>(listeners)) {
            lwl.workspaceReleased(this);
        }
    }

    @Override
    public void removeWorkspaceListener(LocalWorkspaceListener listener) {
        listeners.remove(listener);
    }
}
