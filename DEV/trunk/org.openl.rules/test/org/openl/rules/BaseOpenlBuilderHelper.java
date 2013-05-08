package org.openl.rules;

import java.util.Map.Entry;

import junit.framework.Assert;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.openl.OpenL;
import org.openl.conf.UserContext;
import org.openl.dependency.IDependencyManager;
import org.openl.impl.OpenClassJavaWrapper;
import org.openl.rules.lang.xls.binding.XlsMetaInfo;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.lang.xls.syntax.XlsModuleSyntaxNode;
import org.openl.rules.table.properties.ITableProperties;
import org.openl.types.IOpenClass;
import org.openl.types.IOpenField;
import org.openl.types.IOpenMethod;

/**
 * Helper class for building OpenClassJavaWrapper and getting XlsModuleSyntaxNode from it. To get everything you need 
 * for your tests just extend this class. 
 *  
 * 
 * @author DLiauchuk
 *
 */
public abstract class BaseOpenlBuilderHelper {
    
    private XlsModuleSyntaxNode xsn;    
    private OpenClassJavaWrapper wrapper;
    
    private IDependencyManager dependencyManager;
    
    
    public BaseOpenlBuilderHelper() {
        
    }
    
    public BaseOpenlBuilderHelper(String _src) {
        build(_src);        
    }
    
    public BaseOpenlBuilderHelper(String _src, IDependencyManager dependencyManager) {
        this.dependencyManager = dependencyManager;
        build(_src);        
    }
    
    protected void buildXlsModuleSyntaxNode(String fileToBuildWrapper) {        
        buildJavaWrapper(fileToBuildWrapper);
        XlsMetaInfo xmi = (XlsMetaInfo) wrapper.getOpenClassWithErrors().getMetaInfo();
        xsn = xmi.getXlsModuleNode();        
    }
    
    protected OpenClassJavaWrapper buildJavaWrapper(String fileToBuildWrapper) {        
        UserContext ucxt = initUserContext();
        wrapper = OpenClassJavaWrapper.createWrapper(OpenL.OPENL_JAVA_RULE_NAME, ucxt, fileToBuildWrapper, false, dependencyManager);
        return wrapper;
    }

    protected UserContext initUserContext() {
        return new UserContext(Thread.currentThread().getContextClassLoader(), ".");        
    }
    
    @Deprecated
    protected TableSyntaxNode findTable(String tableName, TableSyntaxNode[] tsns) {
        TableSyntaxNode result = null;
        for (TableSyntaxNode tsn : tsns) {
            if (tableName.equals(tsn.getDisplayName())) {
                result = tsn;   
            }
        }
        return result;
    }
    
    protected TableSyntaxNode findTable(String tableName) {
        TableSyntaxNode result = null;
        for (TableSyntaxNode tsn : getTableSyntaxNodes()) {
            if (tableName.equals(tsn.getDisplayName())) {
                result = tsn;   
            }
        }
        return result;
    }

    protected TableSyntaxNode findTable(String tableName, ITableProperties properties) {
        TableSyntaxNode result = null;
        for (TableSyntaxNode tsn : getTableSyntaxNodes()) {
            if (tableName.equals(tsn.getDisplayName())) {
                EqualsBuilder equalsBuilder = new EqualsBuilder();
                for (Entry<String, Object> property : properties.getAllProperties().entrySet()) {
                    equalsBuilder.append(property.getValue(),
                            tsn.getTableProperties().getPropertyValue(property.getKey()));
                }
                if (equalsBuilder.isEquals()) {
                    result = tsn;
                }
            }
        }
        return result;
    }

    protected TableSyntaxNode[] getTableSyntaxNodes() {  
        TableSyntaxNode[] tsns = xsn.getXlsTableSyntaxNodes();
        return tsns;
    }
    
    protected XlsModuleSyntaxNode getModuleSuntaxNode() {
        return xsn;
    }
    
    protected OpenClassJavaWrapper getJavaWrapper() {
        return wrapper;
    }
    
    public void build(String fileToBuildWrapper) {
        buildXlsModuleSyntaxNode(fileToBuildWrapper);        
    }
    
    protected Object invokeMethod(IOpenMethod testMethod, Object[] paramValues) {
        org.openl.vm.IRuntimeEnv environment = new org.openl.vm.SimpleVM().getRuntimeEnv();
        Object __myInstance = getJavaWrapper().getOpenClassWithErrors().newInstance(environment);
        
        return testMethod.invoke(__myInstance, paramValues, environment); 
    }
    
    protected Object invokeMethod(String methodName) {        
        return invokeMethod(methodName, new IOpenClass[] {},  new Object[0]);        
    }
    
    protected Object invokeMethod(String methodName, IOpenClass[] params, Object[] paramValues) {
        IOpenMethod testMethod = getMethod(methodName, params);

        Assert.assertNotNull(String.format("Method with name \"%s\" does not exists", methodName), testMethod);

        return invokeMethod(testMethod, paramValues);
    }
    
    protected IOpenMethod getMethod(String methodName, IOpenClass[] params) {
        IOpenClass __class = getJavaWrapper().getOpenClassWithErrors(); 
        return __class.getMatchingMethod(methodName, params);
    }

    protected IOpenField getField(String fieldName) {
        return getJavaWrapper().getOpenClassWithErrors().getField(fieldName);
    }

    protected Object getFieldValue(String fieldName) {
        IOpenField field = getField(fieldName);
        org.openl.vm.IRuntimeEnv environment = new org.openl.vm.SimpleVM().getRuntimeEnv();
        Object __myInstance = getJavaWrapper().getOpenClassWithErrors().newInstance(environment);
        return field.get(__myInstance, environment);
    }
}
