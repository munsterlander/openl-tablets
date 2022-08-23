package org.openl.rules.ruleservice.publish.common;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.openl.binding.MethodUtil;
import org.openl.rules.context.IRulesRuntimeContext;
import org.openl.rules.ruleservice.core.annotations.ExternalParam;
import org.openl.rules.ruleservice.core.annotations.Name;
import org.openl.rules.variation.VariationsPack;
import org.openl.types.IMethodSignature;
import org.openl.types.IOpenField;
import org.openl.types.IOpenMember;
import org.openl.types.IOpenMethod;
import org.openl.util.ClassUtils;
import org.openl.util.JavaKeywordUtils;
import org.openl.util.generation.GenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MethodUtils {
    private MethodUtils() {
    }

    private static void validateAndUpdateParameterNames(String[] parameterNames) {
        Set<String> allNames = new HashSet<>(Arrays.asList(parameterNames));
        Set<String> usedNames = new HashSet<>();
        for (int i = 0; i < parameterNames.length; i++) {
            if (allNames.contains(parameterNames[i])) {
                allNames.remove(parameterNames[i]);
                usedNames.add(parameterNames[i]);
            } else {
                int j = 0;
                while (allNames.contains("arg" + j) || usedNames.contains("arg" + j)) {
                    j++;
                }
                parameterNames[i] = "arg" + j;
            }
        }
    }

    public static String[] getParameterNames(IOpenMember openMember,
            Method method,
            boolean provideRuntimeContext,
            boolean provideVariations) {
        String[] parameterNames = new String[method.getParameterCount()];
        if (openMember instanceof IOpenMethod) {
            int i = 0;
            int j = 0;
            IOpenMethod openMethod = (IOpenMethod) openMember;
            IMethodSignature methodSignature = openMethod.getSignature();
            for (Parameter parameter : method.getParameters()) {
                if (i == 0 && provideRuntimeContext && method
                    .getParameterTypes().length > 0 && IRulesRuntimeContext.class
                        .isAssignableFrom(method.getParameterTypes()[0])) {
                    parameterNames[i] = "runtimeContext";
                } else if (i == method.getParameterCount() - 1 && provideVariations && VariationsPack.class
                    .isAssignableFrom(method.getParameters()[method.getParameters().length - 1].getType())) {
                    parameterNames[i] = "variationPack";
                } else if (!parameter.isAnnotationPresent(ExternalParam.class)) {
                    parameterNames[i] = methodSignature.getParameterName(j++);
                }
                i++;
            }
        } else if (openMember instanceof IOpenField) {
            IOpenField openField = (IOpenField) openMember;
            if (ClassUtils.getter(openField.getName()).equals(method.getName())) {
                if (provideRuntimeContext && method.getParameterTypes().length > 0 && IRulesRuntimeContext.class
                    .isAssignableFrom(method.getParameterTypes()[0])) {
                    parameterNames[0] = "runtimeContext";
                }
                if (provideVariations && VariationsPack.class
                    .isAssignableFrom(method.getParameters()[method.getParameters().length - 1].getType()))
                    parameterNames[1] = "variationPack";
            }
        }
        int j = 0;
        for (int i = 0; i < parameterNames.length; i++) {
            if (parameterNames[i] == null) {
                parameterNames[i] = "arg" + j;
                j++;
            }
        }
        GenUtils.fixJavaKeyWords(Arrays.asList(parameterNames));
        int i = 0;
        for (Parameter parameter : method.getParameters()) {
            Name name = parameter.getAnnotation(Name.class);
            if (name != null) {
                if (!name.value().isEmpty() && !JavaKeywordUtils.isJavaKeyword(name.value())) {
                    parameterNames[i] = name.value();
                } else {
                    Logger log = LoggerFactory.getLogger(MethodUtils.class);
                    if (log.isWarnEnabled()) {
                        log.warn("Invalid parameter name '{}' is used in @Name annotation for the method '{}.{}'.",
                            name.value(),
                            method.getClass().getTypeName(),
                            MethodUtil.printMethod(method.getName(), method.getParameterTypes()));
                    }
                }
            }
            i++;
        }
        validateAndUpdateParameterNames(parameterNames);
        return parameterNames;
    }
}