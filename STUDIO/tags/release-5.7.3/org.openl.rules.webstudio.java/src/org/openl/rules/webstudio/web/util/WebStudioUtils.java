package org.openl.rules.webstudio.web.util;

import javax.servlet.http.HttpSession;

import org.openl.commons.web.jsf.FacesUtils;
import org.openl.rules.repository.RulesRepositoryFactory;
import org.openl.rules.ui.ProjectModel;
import org.openl.rules.ui.WebStudio;
import org.openl.rules.webstudio.security.CurrentUserInfo;
import org.openl.rules.webstudio.web.servlet.RulesUserSession;
import org.openl.rules.workspace.MultiUserWorkspaceManager;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Contains utility methods, which can be used from any class.
 *
 * @author Aliaksandr Antonik
 */
public abstract class WebStudioUtils {

    private static final String STUDIO_ATTR = "studio";

    public static RulesUserSession getRulesUserSession(HttpSession session) {
        if (session == null) {
            return null;
        }
        return (RulesUserSession) session.getAttribute(Constants.RULES_USER_SESSION);
    }

    public static RulesUserSession getRulesUserSession(HttpSession session, boolean create) {
        RulesUserSession rulesUserSession = getRulesUserSession(session);
        if (rulesUserSession == null && create) {
            rulesUserSession = new RulesUserSession();

            rulesUserSession.setUser(((CurrentUserInfo) WebApplicationContextUtils
                    .getWebApplicationContext(session.getServletContext()).getBean("currentUserInfo")).getUser());
            rulesUserSession.setWorkspaceManager((MultiUserWorkspaceManager) WebApplicationContextUtils
                    .getWebApplicationContext(session.getServletContext()).getBean("workspaceManager"));
            session.setAttribute(Constants.RULES_USER_SESSION, rulesUserSession);
        }
        return rulesUserSession;
    }

    public static WebStudio getWebStudio() {
        return (WebStudio) (FacesUtils.getSessionParam(STUDIO_ATTR));
    }

    public static WebStudio getWebStudio(HttpSession session) {
        return session == null ? null : (WebStudio) session.getAttribute(STUDIO_ATTR);
    }

    public static WebStudio getWebStudio(boolean create) {
        return getWebStudio(FacesUtils.getSession(), create);
    }

    public static WebStudio getWebStudio(HttpSession session, boolean create) {
        WebStudio studio = getWebStudio(session);
        if (studio == null && create) {
            studio = new WebStudio(session);
            session.setAttribute(STUDIO_ATTR, studio);
        }
        return studio;
    }

    public static boolean isRepositoryFailed() {
        return RulesRepositoryFactory.isFailed();
    }

    public static boolean isStudioReady() {
        WebStudio webStudio = getWebStudio();
        return webStudio != null && webStudio.getModel().isReady();
    }

    public static ProjectModel getProjectModel() {
        WebStudio webStudio = getWebStudio();
        if (webStudio != null) {
            return webStudio.getModel();
        }
        return null;
    }

}
