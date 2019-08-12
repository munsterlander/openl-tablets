package org.openl.rules.project.instantiation;

import java.util.Collection;
import java.util.Map;

import org.openl.CompiledOpenClass;
import org.openl.rules.project.model.Module;

/**
 * Compiles {@link Module}s and gets {@link CompiledOpenClass} and instance of for execution
 *
 * @author PUdalau, Marat Kamalov
 */
public interface RulesInstantiationStrategy {

    /**
     * Compiles module.
     *
     * @return CompiledOpenClass that represents overall info about module rules.
     * @throws RulesInstantiationException
     */
    CompiledOpenClass compile() throws RulesInstantiationException;

    /**
     * Creates instance of class handling all rules invocations. The class will be instance of class got with
     * {@link #getInstanceClass()()}.
     *
     * @return instance of {@link #getInstanceClass()} result.
     * @throws RulesInstantiationException
     * @throws ClassNotFoundException
     */
    Object instantiate() throws RulesInstantiationException, ClassNotFoundException;

    /**
     * Returns ClassLoader for the current module inside the project. If classLoader was set during the construction of
     * the strategy - returns it.<br>
     * If no, creates {@link org.openl.classloader.OpenLBundleClassLoader} with project classLoader of current module as parent.
     *
     * @return {@link ClassLoader} that will be used for openl compilation.
     *
     *         throws RulesInstantiationException some strategies compile dependencie during classloader build.
     */
    ClassLoader getClassLoader() throws RulesInstantiationException;

    /**
     * Service class of rules can be defined by user or it is predefined for some specific instantiations
     * strategies(e.g. for static wrapper case).
     *
     * @return service class that it is used for {@link InstantiationError} or <code>null</code>.
     * @throws ClassNotFoundException
     */
    Class<?> getServiceClass() throws ClassNotFoundException;

    /**
     * Service that will be used for instantiation.
     *
     * @param serviceClass service class.
     */
    void setServiceClass(Class<?> serviceClass);

    /**
     * Generates interfaces corresponding rules.
     *
     * @return generated interface for rules.
     * @throws RulesInstantiationException
     */
    Class<?> getGeneratedRulesClass() throws RulesInstantiationException;

    /**
     * Recognizes class for instance representing rule. It will be service class or generated by rules class it the
     * first one is not defined.
     *
     * @return class of instance.
     * @throws ClassNotFoundException
     * @throws RulesInstantiationException
     */
    Class<?> getInstanceClass() throws ClassNotFoundException, RulesInstantiationException;

    /**
     * @return <code>true</code> if service class is defined.
     */
    boolean isServiceClassDefined();

    /**
     * Resets instantiation strategy in order to check out all changes in modules.
     */
    void reset();

    /**
     * Forced reset that is improved version of {@link #reset()} with reloading of classloaders.
     */
    void forcedReset();

    /**
     * @return Modules used for compilation.
     */
    Collection<Module> getModules();

    /**
     * Some additional options for compilation defined externally(e.g. external dependencies, overridden system
     * properties)
     */
    Map<String, Object> getExternalParameters();

    void setExternalParameters(Map<String, Object> parameters);

}
