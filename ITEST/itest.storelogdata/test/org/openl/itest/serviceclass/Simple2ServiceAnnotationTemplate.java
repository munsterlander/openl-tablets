package org.openl.itest.serviceclass;

import org.openl.rules.context.IRulesRuntimeContext;
import org.openl.rules.ruleservice.storelogdata.cassandra.annotation.StoreLogDataToCassandra;
import org.openl.rules.ruleservice.storelogdata.db.annotation.StoreLogDataToDB;

@StoreLogDataToCassandra
@StoreLogDataToDB
public interface Simple2ServiceAnnotationTemplate {

    String Hello(IRulesRuntimeContext runtimeContext, Integer hour);
}
