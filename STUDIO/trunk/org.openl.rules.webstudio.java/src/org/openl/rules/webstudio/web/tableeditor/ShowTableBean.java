package org.openl.rules.webstudio.web.tableeditor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.lang.xls.syntax.TableSyntaxNodeAdapter;
import org.openl.rules.table.ITable;
import org.openl.rules.lang.xls.IXlsTableNames;
import org.openl.rules.table.IGridTable;
import org.openl.rules.table.properties.DefaultPropertyDefinitions;
import org.openl.rules.table.properties.TablePropertyDefinition;
import org.openl.rules.table.properties.TablePropertyDefinition.SystemValuePolicy;
import org.openl.rules.tableeditor.model.TableEditorModel;
import org.openl.rules.service.TableServiceException;
import org.openl.rules.service.TableServiceImpl;
import org.openl.rules.ui.ProjectModel;
import org.openl.rules.ui.WebStudio;
import org.openl.rules.ui.AllTestsRunResult;
import org.openl.rules.web.jsf.util.FacesUtils;
import org.openl.rules.webstudio.properties.SystemValuesManager;
import org.openl.rules.webstudio.web.util.Constants;
import org.openl.rules.webstudio.web.util.WebStudioUtils;
import org.openl.rules.webtools.WebTool;
import org.openl.rules.workspace.uw.UserWorkspaceProject;
import org.openl.syntax.ISyntaxError;
import org.openl.util.StringTool;

/**
 * Request scope managed bean for showTable facelet.
 */
public class ShowTableBean {

    private static final Log LOG = LogFactory.getLog(ShowTableBean.class);

    private String url;
    private String text;
    private String name;
    private boolean runnable;
    private boolean testable;
    private ISyntaxError[] se;
    private String uri;
    private String notViewParams;
    private String paramsWithoutShowFormulas;

    private boolean switchParam;

    @SuppressWarnings("unchecked")
    public ShowTableBean() {
        uri = FacesUtils.getRequestParameter(Constants.REQUEST_PARAM_URI);
        WebStudio studio = WebStudioUtils.getWebStudio();

        if (uri != null) {
            switchParam = Boolean.valueOf(FacesUtils.getRequestParameter("switch"));
            studio.setTableUri(uri);
        } else {
            uri = studio.getTableUri();
        }
        final ProjectModel model = studio.getModel();
        url = model.makeXlsUrl(uri);
        text = org.openl.rules.webtools.indexer.FileIndexer.showElementHeader(uri);
        name = model.getDisplayNameFull(uri);
        runnable = model.isRunnable(uri);
        testable = model.isTestable(uri);
        se = model.getErrors(uri);

        Map paramMap = new HashMap(FacesUtils.getRequestParameterMap());
        for (Map.Entry entry : (Set<Map.Entry>) paramMap.entrySet()) {
            if (entry.getValue() instanceof String) {
                entry.setValue(new String[] { (String) entry.getValue() });
            }
        }
        notViewParams = WebTool.listParamsExcept(new String[] { "transparency", "filterType", "view" }, paramMap);
        paramsWithoutShowFormulas = WebTool.listParamsExcept(new String[] { "transparency", "filterType",
                "showFormulas" }, paramMap);
    }

    // TODO: make internal and instance method
    public static boolean canModifyCurrentProject() {
        WebStudio studio = WebStudioUtils.getWebStudio();
        UserWorkspaceProject currentProject = studio.getCurrentProject();
        if (currentProject != null) {
            return (currentProject.isCheckedOut() || currentProject.isLocalOnly());
        }
        return false;
    }

    public String getEditCell() {
        if (switchParam) {
            return "";
        }
        return FacesUtils.getRequestParameter("cell");
    }

    public String getEncodedUri() {
        return StringTool.encodeURL(uri);
    }

    public String getErrorString() {
        WebStudio webStudio = WebStudioUtils.getWebStudio();
        return webStudio.getModel().showErrors(uri);
    }

    public String getMode() {
        return FacesUtils.getRequestParameter("mode");
    }

    public String getName() {
        return name;
    }

    public String getNotViewParams() {
        return notViewParams;
    }

    public String getParamsWithoutShowFormulas() {
        return paramsWithoutShowFormulas;
    }

    public ISyntaxError[] getSe() {
        return se;
    }

    public ITable getTable() {
        final WebStudio studio = WebStudioUtils.getWebStudio();
        TableSyntaxNode tsn = studio.getModel().getNode(uri == null ? studio.getTableUri() : uri);
        return new TableSyntaxNodeAdapter(tsn);
    }

    public TestRunsResultBean getTestRunResults() {
        AllTestsRunResult atr = WebStudioUtils.getWebStudio().getModel().getRunMethods(uri);
        AllTestsRunResult.Test[] tests = null;
        if (atr != null) {
            tests = atr.getTests();
        }
        return new TestRunsResultBean(tests);
    }

    public String getText() {
        return text;
    }

    public String getUri() {
        return uri;
    }

    public String getUrl() {
        return url;
    }

    public String getView() {
        WebStudio studio = WebStudioUtils.getWebStudio();
        return studio.getModel().getTableView(FacesUtils.getRequestParameter("view"));
    }

    public boolean isCopyable() {
        return canModifyCurrentProject();
    }

    public boolean isEditable() {
        return canModifyCurrentProject();
    }

    public boolean isHasErrors() {
        return se != null && se.length > 0;
    }

    public boolean isRunnable() {
        return runnable;
    }

    public boolean isTestable() {
        return testable;
    }

    public boolean isTsnHasErrors() {
        WebStudio webStudio = WebStudioUtils.getWebStudio();
        return webStudio != null && webStudio.getModel().hasErrors(uri);
    }

    public boolean isShowFormulas() {
        String showFormulas = FacesUtils.getRequestParameter("showFormulas");
        if (showFormulas != null) {
            return Boolean.parseBoolean(showFormulas);
        } else {
            WebStudio webStudio = WebStudioUtils.getWebStudio();
            return webStudio != null && webStudio.isShowFormulas();
        }
    }
    
    public boolean isCollapseProperties() {
        String collapseProperties = FacesUtils.getRequestParameter("collapseProperties");
        if (collapseProperties != null) {
            return Boolean.parseBoolean(collapseProperties);
        } else {
            WebStudio webStudio = WebStudioUtils.getWebStudio();
            return webStudio != null && webStudio.isCollapseProperties();
        }
    }

    public String removeTable() {
        final WebStudio studio = WebStudioUtils.getWebStudio();
        IGridTable table = studio.getModel().getTableWithMode(uri, IXlsTableNames.VIEW_DEVELOPER);
        try {
            new TableServiceImpl(true).removeTable(table);
        } catch (TableServiceException e) {
            e.printStackTrace();
            // TODO UI exception
            return null;
        }
        return "mainPage";
    }

    public String editAsNewVersion() {
        // save new table
        // rebuild project model
        return null;
    }

    public void resetStudio() {
        final WebStudio studio = WebStudioUtils.getWebStudio();
        studio.reset();
        studio.getModel().buildProjectTree();
    }

    @SuppressWarnings("unchecked")
    public boolean updateSystemProperties() {
        boolean result = true;
        String editorId = FacesUtils.getRequestParameter(
                org.openl.rules.tableeditor.util.Constants.REQUEST_PARAM_EDITOR_ID);

        Map editorModelMap = (Map) FacesUtils.getSessionParam(
                org.openl.rules.tableeditor.util.Constants.TABLE_EDITOR_MODEL_NAME);

        TableEditorModel editorModel = (TableEditorModel) editorModelMap.get(editorId);

        List<TablePropertyDefinition> sysProps = DefaultPropertyDefinitions.getSystemProperties();
        for (TablePropertyDefinition sysProp : sysProps) {
            String resultValue = null;
            if (sysProp.getSystemValuePolicy().equals(SystemValuePolicy.ON_EACH_EDIT)) {
                resultValue = SystemValuesManager.instance().getSystemValueString(sysProp.getSystemValueDescriptor());
                if (resultValue != null) {
                    try {
                        editorModel.setProperty(sysProp.getName(), resultValue);
                    } catch (Exception e) {
                        LOG.error(String.format("Can`t update system property %d with value %d",
                                                sysProp.getName(), resultValue),e);
                        result = false;
                    }
                }                
            }
        }
        return result;
    }

    public static class TestRunsResultBean {
        public class TestProxy {
            int index;

            public TestProxy(int index) {
                this.index = index;
            }

            public String[] getDescriptions() {
                AllTestsRunResult.Test test = getTest();
                String[] descriptions = new String[test.ntests()];
                for (int i = 0; i < descriptions.length; i++) {
                    descriptions[i] = test.getTestDescription(i);
                }
                return descriptions;
            }

            private AllTestsRunResult.Test getTest() {
                return tests[index];
            }

            public String getTestName() {
                return StringTool.encodeURL(getTest().getTestName());
            }
        }

        private AllTestsRunResult.Test[] tests;

        private TestProxy[] proxies;

        public TestRunsResultBean(AllTestsRunResult.Test[] tests) {
            this.tests = tests;
            if (tests == null) {
                proxies = new TestProxy[0];
            } else {
                proxies = new TestProxy[tests.length];
            }

            for (int i = 0; i < proxies.length; i++) {
                proxies[i] = new TestProxy(i);
            }
        }

        public TestProxy[] getTests() {
            return proxies;
        }

        public boolean isNotEmpty() {
            return tests != null && tests.length > 0;
        }
    }
}
