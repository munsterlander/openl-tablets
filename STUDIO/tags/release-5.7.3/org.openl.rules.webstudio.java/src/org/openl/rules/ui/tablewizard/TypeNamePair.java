package org.openl.rules.ui.tablewizard;

import javax.validation.constraints.Pattern;

import org.hibernate.validator.constraints.NotEmpty;

/**
 * @author Aliaksandr Antonik.
 */
public class TypeNamePair {

    @NotEmpty(message="Parameter type can not be empty")
    private String type;

    private boolean iterable;

    @NotEmpty(message="Parameter name can not be empty")
    @Pattern(regexp="([a-zA-Z_][a-zA-Z_0-9]*)?", message="Invalid name for parameter")
    private String name;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public boolean isIterable() {
        return iterable;
    }

    public void setIterable(boolean iterable) {
        this.iterable = iterable;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
