package org.openl.rules.validator.dt;

import org.openl.types.java.JavaEnumDomain;

import com.exigen.ie.constrainer.IntVar;

public class JavaEnumDomainAdaptor implements IDomainAdaptor {

    JavaEnumDomain domain;
    
    public JavaEnumDomainAdaptor(JavaEnumDomain domain) {
        this.domain = domain;
    }

    public int getIndex(Object value) {
        return ((Enum<?>)value).ordinal();
    }

    public int getIntVarDomainType() {
        return IntVar.DOMAIN_BIT_FAST;
    }

    public int getMax() {
        return domain.size() -1;
    }

    public int getMin() {
        return 0;
    }

    public Object getValue(int index) {
        return domain.getValue(index);
    }

}
