package org.openl.rules.ruleservice.core;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.tuple.Pair;
import org.openl.CompiledOpenClass;
import org.openl.classloader.OpenLClassLoader;
import org.openl.rules.lang.xls.binding.XlsModuleOpenClass;
import org.openl.rules.project.dependencies.ProjectExternalDependenciesHelper;
import org.openl.rules.project.instantiation.RulesInstantiationException;
import org.openl.rules.project.instantiation.RulesInstantiationStrategy;
import org.openl.rules.project.instantiation.RuntimeContextInstantiationStrategyEnhancer;
import org.openl.rules.project.instantiation.variation.VariationInstantiationStrategyEnhancer;
import org.openl.rules.project.model.Module;
import org.openl.rules.project.model.RulesDeploy;
import org.openl.rules.project.validation.ProjectValidator;
import org.openl.rules.ruleservice.core.interceptors.DynamicInterfaceAnnotationEnhancerHelper;
import org.openl.rules.ruleservice.core.interceptors.ServiceInvocationAdvice;
import org.openl.rules.ruleservice.core.interceptors.ServiceInvocationAdviceListener;
import org.openl.rules.ruleservice.loader.RuleServiceLoader;
import org.openl.rules.ruleservice.management.ServiceManagerImpl;
import org.openl.rules.ruleservice.publish.RuleServiceInstantiationStrategyFactory;
import org.openl.rules.ruleservice.publish.RuleServiceInstantiationStrategyFactoryImpl;
import org.openl.runtime.ASMProxyFactory;
import org.openl.types.IOpenClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;

/**
 * Default implementation of RuleServiceOpenLServiceInstantiationFactory. Depend on RuleLoader.
 *
 * @author Marat Kamalov
 */
public class RuleServiceOpenLServiceInstantiationFactoryImpl implements RuleServiceInstantiationFactory {
    private final Logger log = LoggerFactory.getLogger(RuleServiceOpenLServiceInstantiationFactoryImpl.class);

    private RuleServiceLoader ruleServiceLoader;

    private RuleServiceInstantiationStrategyFactory instantiationStrategyFactory = new RuleServiceInstantiationStrategyFactoryImpl();

    private Map<String, Object> externalParameters;

    private final Map<DeploymentDescription, RuleServiceDependencyManager> dependencyManagerMap = new HashMap<>();

    private ObjectProvider<Collection<ServiceInvocationAdviceListener>> serviceInvocationAdviceListeners;

    @Autowired
    @Lazy
    private ServiceManagerImpl serviceManager;

    @Autowired(required = false)
    private List<ProjectValidator> projectValidators = new ArrayList<>();

    private void initService(ServiceDescription serviceDescription,
            RuleServiceDependencyManager dependencyManager,
            OpenLService service) throws RuleServiceInstantiationException, RulesInstantiationException {
        RulesInstantiationStrategy baseInstantiationStrategy = instantiationStrategyFactory
            .getStrategy(serviceDescription, dependencyManager);
        RulesInstantiationStrategy instantiationStrategy = baseInstantiationStrategy;
        Map<String, Object> parameters = ProjectExternalDependenciesHelper
            .buildExternalParamsWithProjectDependencies(externalParameters, service.getModules());
        instantiationStrategy.setExternalParameters(parameters);
        if (service.isProvideVariations()) {
            instantiationStrategy = new VariationInstantiationStrategyEnhancer(instantiationStrategy);
        }
        if (service.isProvideRuntimeContext()) {
            instantiationStrategy = new RuntimeContextInstantiationStrategyEnhancer(instantiationStrategy);
        }
        compileOpenClass(service, instantiationStrategy);
        ClassLoader serviceClassLoader = resolveServiceClassLoader(service, instantiationStrategy);
        ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(serviceClassLoader);
            Pair<Object, Class<?>> serviceTarget = resolveInterfaceAndClassLoader(service,
                serviceDescription,
                instantiationStrategy);
            if (service.getPublishers().contains(RulesDeploy.PublisherType.RMI.toString())) {
                resolveRmiInterface(service);
            }
            validate(service, baseInstantiationStrategy);
            instantiateServiceBean(service, serviceTarget, serviceClassLoader);
        } finally {
            Thread.currentThread().setContextClassLoader(oldClassLoader);
        }
    }

    private ClassLoader resolveServiceClassLoader(OpenLService service,
            RulesInstantiationStrategy instantiationStrategy) throws RulesInstantiationException,
                                                              RuleServiceInstantiationException {
        ClassLoader moduleGeneratedClassesClassLoader = ((XlsModuleOpenClass) service.getOpenClass())
            .getClassGenerationClassLoader();
        OpenLClassLoader openLClassLoader = new OpenLClassLoader(null);
        openLClassLoader.addClassLoader(moduleGeneratedClassesClassLoader);
        openLClassLoader.addClassLoader(instantiationStrategy.getClassLoader());
        service.setClassLoader(openLClassLoader);
        return openLClassLoader;
    }

    private void compileOpenClass(OpenLService service,
            RulesInstantiationStrategy instantiationStrategy) throws RulesInstantiationException {
        CompiledOpenClass compiledOpenClass = instantiationStrategy.compile();
        service.setCompiledOpenClass(compiledOpenClass);
    }

    private void validate(OpenLService service,
            RulesInstantiationStrategy instantiationStrategy) throws RulesInstantiationException {
        CompiledOpenClass compiledOpenClass = service.getCompiledOpenClass();
        if (!service.getModules().isEmpty()) {
            for (ProjectValidator projectValidator : getProjectValidators()) {
                compiledOpenClass = projectValidator.validate(service.getModules().iterator().next().getProject(),
                    instantiationStrategy);
            }
        }
        service.setCompiledOpenClass(compiledOpenClass);
    }

    private void instantiateServiceBean(OpenLService service,
            Pair<Object, Class<?>> serviceTarget,
            ClassLoader classLoader) throws RuleServiceInstantiationException {
        Class<?> serviceClass = service.getServiceClass();
        try {
            if (!serviceClass.isInterface()) {
                // deprecated approach with wrapper: service class is not
                // interface
                throw new RuleServiceRuntimeException(
                    "Failed to create a proxy for service target object. Deprecated approach with wrapper: service class is not an interface.");
            }
            ServiceInvocationAdvice serviceInvocationAdvice = new ServiceInvocationAdvice(service.getOpenClass(),
                serviceTarget.getLeft(),
                serviceTarget.getRight(),
                serviceClass,
                classLoader,
                getListServiceInvocationAdviceListeners(),
                serviceManager != null ? serviceManager.getRulesDeployInProcess() : null);
            Object proxyServiceBean = ASMProxyFactory
                .newProxyInstance(classLoader, serviceInvocationAdvice, serviceClass);
            service.setServiceBean(proxyServiceBean);
        } catch (Exception t) {
            throw new RuleServiceRuntimeException("Failed to create a proxy for service target object.", t);
        }
    }

    private Pair<Object, Class<?>> resolveInterfaceAndClassLoader(OpenLService service,
            ServiceDescription serviceDescription,
            RulesInstantiationStrategy instantiationStrategy) throws RuleServiceInstantiationException,
                                                              RulesInstantiationException {
        String serviceClassName = service.getServiceClassName();
        Class<?> serviceClass;
        Class<?> serviceTargetClass;
        Object serviceTarget;
        ClassLoader serviceClassLoader = service.getClassLoader();

        if (serviceClassName != null) {
            try {
                serviceClass = serviceClassLoader.loadClass(serviceClassName.trim());
                if (!serviceClass.isInterface()) {
                    throw new RuleServiceRuntimeException(
                            String.format("Failed to apply service class '%s'. Interface is expected, but class is found.",
                                    serviceClass));
                }
                serviceTargetClass = RuleServiceInstantiationFactoryHelper
                    .buildInterfaceForInstantiationStrategy(serviceClass,
                        instantiationStrategy.getClassLoader(),
                        instantiationStrategy.instantiate(),
                        serviceDescription.isProvideRuntimeContext(),
                        serviceDescription.isProvideVariations());
                instantiationStrategy.setServiceClass(serviceTargetClass);
                service.setServiceClass(serviceClass);
                serviceTarget = instantiationStrategy.instantiate();
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                throw new RuleServiceRuntimeException(
                    String.format("Failed to load a service class '%s'.", serviceClassName),
                    e);
            }
        } else {
            log.info("Service class is undefined for service '{}'. Generated interface is used.", service.getDeployPath());
            serviceTargetClass = instantiationStrategy.getInstanceClass();
            serviceTarget = instantiationStrategy.instantiate();
            IOpenClass openClass = service.getOpenClass();
            var annotatedClass = processAnnotatedTemplateClass(serviceDescription, serviceTargetClass, openClass, serviceClassLoader);
            serviceClass = RuleServiceInstantiationFactoryHelper.buildInterfaceForService(openClass,
                annotatedClass,
                serviceClassLoader,
                serviceTarget,
                serviceDescription.isProvideRuntimeContext(),
                serviceDescription.isProvideVariations());
            service.setServiceClass(serviceClass);
        }
        return Pair.of(serviceTarget, serviceTargetClass);
    }

    private void resolveRmiInterface(OpenLService service) throws RuleServiceInstantiationException {
        String rmiServiceClassName = service.getRmiServiceClassName();
        Class<?> serviceClass = null;
        ClassLoader serviceClassLoader = service.getClassLoader();
        if (rmiServiceClassName != null) {
            try {
                serviceClass = serviceClassLoader.loadClass(rmiServiceClassName.trim());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                throw new RuleServiceRuntimeException(
                    String.format("Failed to load RMI service class '%s'.", rmiServiceClassName),
                    e);
            }
        }
        if (serviceClass == null) {
            log.info("Service class is undefined for service '{}'. Default RMI interface is used.",
                service.getDeployPath());
            service.setRmiServiceClassName(null); // RMI default will be used
        }
        service.setRmiServiceClass(serviceClass);
    }

    private Class<?> processAnnotatedTemplateClass(ServiceDescription serviceDescription,
            Class<?> serviceClass,
            IOpenClass openClass,
            ClassLoader classLoader) {
        String annotationTemplateClassName = serviceDescription.getAnnotationTemplateClassName();
        if (annotationTemplateClassName != null) {
            try {
                Class<?> annotationTemplateClass = classLoader.loadClass(annotationTemplateClassName.trim());
                if (annotationTemplateClass.isInterface() || Modifier
                    .isAbstract(annotationTemplateClass.getModifiers())) {
                    Class<?> decoratedClass = DynamicInterfaceAnnotationEnhancerHelper
                        .decorate(serviceClass, annotationTemplateClass, openClass, classLoader);
                    log.info("Annotation template class '{}' is used for service {}.",
                        annotationTemplateClassName,
                        serviceDescription.getDeployPath());
                    return decoratedClass;
                }
                throw new RuleServiceRuntimeException(String.format(
                    "Failed to apply annotation template class '%s'. Interface or abstract class is expected, but class is found.",
                    annotationTemplateClassName));
            } catch (RuleServiceRuntimeException e) {
                throw e;
            } catch (Exception | NoClassDefFoundError e) {
                throw new RuleServiceRuntimeException(
                    String.format("Failed to load or apply annotation template class '%s'.",
                        annotationTemplateClassName),
                    e);
            }
        }
        return serviceClass;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OpenLService createService(final ServiceDescription serviceDescription) {
        log.debug("Resolving modules for service '{}'.", serviceDescription.getDeployPath());
        Collection<Module> modules = serviceDescription.getModules();

        OpenLService.OpenLServiceBuilder builder = new OpenLService.OpenLServiceBuilder();
        builder.setName(serviceDescription.getName())
            .setUrl(serviceDescription.getUrl())
            .setDeployPath(serviceDescription.getDeployPath())
            .setServiceClassName(serviceDescription.getServiceClassName())
            .setRmiServiceClassName(serviceDescription.getRmiServiceClassName())
            .setRmiName(serviceDescription.getRmiName())
            .setProvideRuntimeContext(serviceDescription.isProvideRuntimeContext())
            .setProvideVariations(serviceDescription.isProvideVariations())
            .addModules(modules)
            .setDeployment(serviceDescription.getDeployment());

        for (String publisher : serviceDescription.getPublishers()) {
            builder.addPublisher(publisher);
        }

        return builder.build(new AbstractOpenLServiceInitializer() {
            @Override
            public void init(OpenLService openLService) throws RuleServiceInstantiationException {
                try {
                    initService(serviceDescription, getDependencyManager(serviceDescription), openLService);
                } catch (RuleServiceInstantiationException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuleServiceInstantiationException(
                        String.format("Failed to initialize service '%s'.", openLService.getDeployPath()),
                        e);
                }
            }
        });
    }

    public RuleServiceLoader getRuleServiceLoader() {
        return ruleServiceLoader;
    }

    public void setRuleServiceLoader(RuleServiceLoader ruleServiceLoader) {
        this.ruleServiceLoader = Objects.requireNonNull(ruleServiceLoader, "ruleServiceLoader cannot be null");
    }

    public RuleServiceInstantiationStrategyFactory getInstantiationStrategyFactory() {
        return instantiationStrategyFactory;
    }

    public void setInstantiationStrategyFactory(RuleServiceInstantiationStrategyFactory instantiationStrategyFactory) {
        this.instantiationStrategyFactory = Objects.requireNonNull(instantiationStrategyFactory,
            "instantiationStrategyFactory cannot be null");

    }

    public Collection<ServiceInvocationAdviceListener> getListServiceInvocationAdviceListeners() {
        if (getServiceInvocationAdviceListeners() != null) {
            return getServiceInvocationAdviceListeners().getIfAvailable();
        } else {
            return Collections.emptyList();
        }
    }

    public ObjectProvider<Collection<ServiceInvocationAdviceListener>> getServiceInvocationAdviceListeners() {
        return serviceInvocationAdviceListeners;
    }

    public void setServiceInvocationAdviceListeners(
            ObjectProvider<Collection<ServiceInvocationAdviceListener>> serviceInvocationAdviceListeners) {
        this.serviceInvocationAdviceListeners = serviceInvocationAdviceListeners;
    }

    public Map<String, Object> getExternalParameters() {
        return externalParameters;
    }

    public void setExternalParameters(Map<String, Object> externalParameters) {
        this.externalParameters = externalParameters;
    }

    public List<ProjectValidator> getProjectValidators() {
        return projectValidators;
    }

    public void setProjectValidators(List<ProjectValidator> projectValidators) {
        this.projectValidators = projectValidators;
    }

    @Override
    public void clean(ServiceDescription serviceDescription) {
        dependencyManagerMap.remove(serviceDescription.getDeployment()).resetAll();
    }

    private RuleServiceDependencyManager getDependencyManager(ServiceDescription serviceDescription) {
        RuleServiceDependencyManager dependencyManager;
        DeploymentDescription deployment = serviceDescription.getDeployment();
        if (dependencyManagerMap.containsKey(deployment)) {
            dependencyManager = dependencyManagerMap.get(deployment);
        } else {
            ClassLoader rootClassLoader = Thread.currentThread().getContextClassLoader();
            dependencyManager = new RuleServiceDependencyManager(deployment,
                ruleServiceLoader,
                rootClassLoader,
                    externalParameters);
            dependencyManagerMap.put(deployment, dependencyManager);
        }
        return dependencyManager;
    }

}
