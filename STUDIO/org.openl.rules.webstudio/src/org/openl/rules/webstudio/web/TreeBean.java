package org.openl.rules.webstudio.web;

import java.util.List;

import org.openl.rules.ui.WebStudio;
import org.openl.rules.webstudio.web.util.WebStudioUtils;
import org.openl.security.acl.permission.AclPermission;
import org.openl.security.acl.repository.RepositoryAclService;
import org.richfaces.model.TreeNode;
import org.springframework.stereotype.Service;
import org.springframework.web.context.annotation.SessionScope;

/**
 * Request scope managed bean providing logic for tree page of OpenL Studio.
 */
@Service
@SessionScope
public class TreeBean {

    private boolean hideUtilityTables = true;

    public void setHideUtilityTables(boolean hideUtilityTables) {
        this.hideUtilityTables = hideUtilityTables;
    }

    public boolean isHideUtilityTables() {
        return hideUtilityTables;
    }

    private final RepositoryAclService repositoryAclService;

    public TreeBean(RepositoryAclService repositoryAclService) {
        this.repositoryAclService = repositoryAclService;
    }

    public void setCurrentView(String currentView) {
        WebStudio studio = WebStudioUtils.getWebStudio();
        studio.setTreeView(currentView);
    }

    public boolean getCanRun() {
        WebStudio studio = WebStudioUtils.getWebStudio();
        return repositoryAclService.isGranted(studio.getCurrentProject(), List.of(AclPermission.RUN));
    }

    public TreeNode getTree() {
        WebStudio studio = WebStudioUtils.getWebStudio();

        return studio.getModel().getProjectTree();
    }
}
