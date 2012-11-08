package org.openl.rules.ui.tablewizard;

import static org.openl.rules.ui.tablewizard.WizardUtils.getMetaInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.faces.model.SelectItem;

import org.apache.commons.lang.StringUtils;
import org.hibernate.validator.constraints.NotBlank;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.table.properties.ITableProperties;
import org.openl.rules.table.properties.def.TablePropertyDefinition;
import org.openl.rules.table.properties.def.TablePropertyDefinitionUtils;
import org.openl.rules.table.properties.def.TablePropertyDefinition.SystemValuePolicy;
import org.openl.rules.webstudio.properties.SystemValuesManager;
import org.openl.rules.webstudio.web.util.WebStudioUtils;

public abstract class BusinessTableCreationWizard extends WizardBase {

    private String categoryName;

    @NotBlank(message="Can not be empty")
    private String newCategoryName;

    private String categoryNameSelector = "existing";

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.newCategoryName = null;
        this.categoryName = categoryName;
    }

    public String getCategoryNameSelector() {
        return categoryNameSelector;
    }

    public void setCategoryNameSelector(String categoryNameSelector) {
        this.categoryNameSelector = categoryNameSelector;
    }

    public String getNewCategoryName() {
        return newCategoryName;
    }

    public void setNewCategoryName(String newCategoryName) {
        this.categoryName = null;
        this.newCategoryName = newCategoryName;
    }

    public List<SelectItem> getCategoryNamesList() {
        List<SelectItem> categoryList = new ArrayList<SelectItem>();
        Set<String> categories = getAllCategories();
        for (String categoryName : categories) {
            categoryList.add(new SelectItem(
                    // Replace new line by space
                    categoryName.replaceAll("[\r\n]", " ")));
        }
        return categoryList;
    }

    private Set<String> getAllCategories() {
        Set<String> categories = new TreeSet<String>();
        TableSyntaxNode[] syntaxNodes = getMetaInfo().getXlsModuleNode().getXlsTableSyntaxNodes();
        for (TableSyntaxNode node : syntaxNodes) {
            ITableProperties tableProperties = node.getTableProperties();
            if (tableProperties != null) {
                String categoryName = tableProperties.getCategory();
                if (StringUtils.isNotBlank(categoryName)) {
                    categories.add(categoryName);
                }
            }
        }
        return categories;
    }

    protected String buildCategoryName() {
        String categoryName;
        if (StringUtils.isNotBlank(this.categoryName)) {
            categoryName = this.categoryName;
        } else if (StringUtils.isNotBlank(newCategoryName)) {
            categoryName = this.newCategoryName;
        } else {
            // This for the case when 'Sheet name' selected for category name.
            categoryName = null;
        }
        return categoryName;
    }

    protected Map<String, Object> buildSystemProperties() {
        // TODO Set user.mode property via faces-config.xml
        String userMode = WebStudioUtils.getWebStudio().getSystemConfigManager().getStringProperty("user.mode");
        Map<String, Object> result = new LinkedHashMap<String, Object>();

        List<TablePropertyDefinition> systemPropDefinitions = TablePropertyDefinitionUtils.getSystemProperties();
        for (TablePropertyDefinition systemPropDef : systemPropDefinitions) {
            String systemValueDescriptor = systemPropDef.getSystemValueDescriptor();
            if (userMode.equals("single") && systemValueDescriptor.equals(SystemValuesManager.CURRENT_USER_DESCRIPTOR)) {
                continue;
            }
            if (systemPropDef.getSystemValuePolicy().equals(SystemValuePolicy.IF_BLANK_ONLY)) {
                Object systemValue = SystemValuesManager.getInstance().getSystemValue(systemValueDescriptor);
                if (systemValue != null) {
                    result.put(systemPropDef.getName(), systemValue);
                }
            }
        }

        return result;
    }

    protected Map<String, Object> buildProperties() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();

        // Put business properties.
        String category = buildCategoryName();
        if (category != null) {
            properties.put("category", category);
        }

        // Put system properties.
        if (WebStudioUtils.getWebStudio().isUpdateSystemProperties()) {
            Map<String, Object> systemProperties = buildSystemProperties();
            properties.putAll(systemProperties);
        }

        return properties;
    }

}
