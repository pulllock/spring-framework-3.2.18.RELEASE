/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Provider;

import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.CannotLoadBeanClassException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.SmartFactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} and
 * {@link BeanDefinitionRegistry} interfaces: a full-fledged bean factory
 * based on bean definition objects.
 *
 * <p>Typical usage is registering all bean definitions first (possibly read
 * from a bean definition file), before accessing beans. Bean definition lookup
 * is therefore an inexpensive operation in a local bean definition table,
 * operating on pre-built bean definition metadata objects.
 *
 * <p>Can be used as a standalone bean factory, or as a superclass for custom
 * bean factories. Note that readers for specific bean definition formats are
 * typically implemented separately rather than as bean factory subclasses:
 * see for example {@link PropertiesBeanDefinitionReader} and
 * {@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}.
 *
 * <p>For an alternative implementation of the
 * {@link org.springframework.beans.factory.ListableBeanFactory} interface,
 * have a look at {@link StaticListableBeanFactory}, which manages existing
 * bean instances rather than creating new ones based on bean definitions.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @author Costin Leau
 * @author Chris Beams
 * @author Phillip Webb
 * @since 16 April 2001
 * @see StaticListableBeanFactory
 * @see PropertiesBeanDefinitionReader
 * @see org.springframework.beans.factory.xml.XmlBeanDefinitionReader
 * ioc容器的默认实现，是一个完整的ioc容器
 */
@SuppressWarnings("serial")
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
		implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {

	private static Class<?> javaxInjectProviderClass = null;

	static {
		try {
			javaxInjectProviderClass =
					ClassUtils.forName("javax.inject.Provider", DefaultListableBeanFactory.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - Provider interface simply not supported then.
		}
	}


	/**
	 * Map from serialized id to factory instance
	 * 缓存序列化id和工厂实例的关系
	 */
	private static final Map<String, Reference<DefaultListableBeanFactory>> serializableFactories =
			new ConcurrentHashMap<String, Reference<DefaultListableBeanFactory>>(8);

	/**
	 * Optional id for this factory, for serialization purposes
	 * 当前工厂可选的一个id，用于序列化
	 */
	private String serializationId;

	/**
	 * Whether to allow re-registration of a different definition with the same name
	 * 是否允许覆盖bean定义
	 */
	private boolean allowBeanDefinitionOverriding = true;

	/**
	 * Whether to allow eager class loading even for lazy-init beans
	 * 是否对于lazy-init类型的Bean，也进行加载
	 */
	private boolean allowEagerClassLoading = true;

	/**
	 * Resolver to use for checking if a bean definition is an autowire candidate
	 * 自动注入候选的解析器
	 */
	private AutowireCandidateResolver autowireCandidateResolver = new SimpleAutowireCandidateResolver();

	/**
	 * Map from dependency type to corresponding autowired value
	 * 依赖类型和对应的自动注入的value的缓存
	 */
	private final Map<Class<?>, Object> resolvableDependencies = new HashMap<Class<?>, Object>(16);

	/**
	 * Map of bean definition objects, keyed by bean name
	 * bean定义缓存，key是bean名字
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<String, BeanDefinition>(64);

	/**
	 * Map of singleton and non-singleton bean names, keyed by dependency type
	 * 单例和非单例的名字缓存，key是依赖类型
	 * 缓存所有的Bean的名字
	 */
	private final Map<Class<?>, String[]> allBeanNamesByType = new ConcurrentHashMap<Class<?>, String[]>(64);

	/**
	 * Map of singleton-only bean names, keyed by dependency type
	 * 单例bean名字缓存
	 * 缓存所有的单例Bean的名字
	 */
	private final Map<Class<?>, String[]> singletonBeanNamesByType = new ConcurrentHashMap<Class<?>, String[]>(64);

	/**
	 * List of bean definition names, in registration order
	 * beanDefinition名字缓存，按照注册顺序
	 * 缓存所有Bean定义的名字
	 */
	private final List<String> beanDefinitionNames = new ArrayList<String>(64);

	/**
	 * Whether bean definition metadata may be cached for all beans
	 * bean definition元数据是否缓存
	 */
	private boolean configurationFrozen = false;

	/**
	 * Cached array of bean definition names in case of frozen configuration
	 * 冻结的BeanDefinition名字
	 */
	private String[] frozenBeanDefinitionNames;


	/**
	 * Create a new DefaultListableBeanFactory.
	 */
	public DefaultListableBeanFactory() {
		super();
	}

	/**
	 * Create a new DefaultListableBeanFactory with the given parent.
	 * @param parentBeanFactory the parent BeanFactory
	 */
	public DefaultListableBeanFactory(BeanFactory parentBeanFactory) {
		super(parentBeanFactory);
	}


	/**
	 * Specify an id for serialization purposes, allowing this BeanFactory to be
	 * deserialized from this id back into the BeanFactory object, if needed.
	 */
	public void setSerializationId(String serializationId) {
		if (serializationId != null) {
			serializableFactories.put(serializationId, new WeakReference<DefaultListableBeanFactory>(this));
		}
		else if (this.serializationId != null) {
			serializableFactories.remove(this.serializationId);
		}
		this.serializationId = serializationId;
	}

	/**
	 * Set whether it should be allowed to override bean definitions by registering
	 * a different definition with the same name, automatically replacing the former.
	 * If not, an exception will be thrown. This also applies to overriding aliases.
	 * <p>Default is "true".
	 * @see #registerBeanDefinition
	 */
	public void setAllowBeanDefinitionOverriding(boolean allowBeanDefinitionOverriding) {
		this.allowBeanDefinitionOverriding = allowBeanDefinitionOverriding;
	}

	/**
	 * Set whether the factory is allowed to eagerly load bean classes
	 * even for bean definitions that are marked as "lazy-init".
	 * <p>Default is "true". Turn this flag off to suppress class loading
	 * for lazy-init beans unless such a bean is explicitly requested.
	 * In particular, by-type lookups will then simply ignore bean definitions
	 * without resolved class name, instead of loading the bean classes on
	 * demand just to perform a type check.
	 * @see AbstractBeanDefinition#setLazyInit
	 */
	public void setAllowEagerClassLoading(boolean allowEagerClassLoading) {
		this.allowEagerClassLoading = allowEagerClassLoading;
	}

	/**
	 * Set a custom autowire candidate resolver for this BeanFactory to use
	 * when deciding whether a bean definition should be considered as a
	 * candidate for autowiring.
	 */
	public void setAutowireCandidateResolver(final AutowireCandidateResolver autowireCandidateResolver) {
		Assert.notNull(autowireCandidateResolver, "AutowireCandidateResolver must not be null");
		if (autowireCandidateResolver instanceof BeanFactoryAware) {
			if (System.getSecurityManager() != null) {
				final BeanFactory target = this;
				AccessController.doPrivileged(new PrivilegedAction<Object>() {
					public Object run() {
						((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(target);
						return null;
					}
				}, getAccessControlContext());
			}
			else {
				((BeanFactoryAware) autowireCandidateResolver).setBeanFactory(this);
			}
		}
		this.autowireCandidateResolver = autowireCandidateResolver;
	}

	/**
	 * Return the autowire candidate resolver for this BeanFactory (never {@code null}).
	 */
	public AutowireCandidateResolver getAutowireCandidateResolver() {
		return this.autowireCandidateResolver;
	}


	@Override
	public void copyConfigurationFrom(ConfigurableBeanFactory otherFactory) {
		super.copyConfigurationFrom(otherFactory);
		if (otherFactory instanceof DefaultListableBeanFactory) {
			DefaultListableBeanFactory otherListableFactory = (DefaultListableBeanFactory) otherFactory;
			this.allowBeanDefinitionOverriding = otherListableFactory.allowBeanDefinitionOverriding;
			this.allowEagerClassLoading = otherListableFactory.allowEagerClassLoading;
			this.autowireCandidateResolver = otherListableFactory.autowireCandidateResolver;
			this.resolvableDependencies.putAll(otherListableFactory.resolvableDependencies);
		}
	}


	//---------------------------------------------------------------------
	// Implementation of ListableBeanFactory interface
	// ListableBeanFactory接口的实现
	//---------------------------------------------------------------------

	/**
	 * 获取Bean实例，指定需要的类型
	 * @param requiredType type the bean must match; can be an interface or superclass.
	 * {@code null} is disallowed.
	 * @param <T>
	 * @return
	 * @throws BeansException
	 */
	public <T> T getBean(Class<T> requiredType) throws BeansException {
		Assert.notNull(requiredType, "Required type must not be null");
		// 指定类型的所有的bean的名字
		String[] beanNames = getBeanNamesForType(requiredType);
		// 有多个bean名字
		if (beanNames.length > 1) {
			// 只需要哪些autowire-candidate=true的Bean
			ArrayList<String> autowireCandidates = new ArrayList<String>();
			for (String beanName : beanNames) {
				if (!containsBeanDefinition(beanName) || getBeanDefinition(beanName).isAutowireCandidate()) {
					autowireCandidates.add(beanName);
				}
			}
			if (autowireCandidates.size() > 0) {
				beanNames = autowireCandidates.toArray(new String[autowireCandidates.size()]);
			}
		}
		// 只有一个，直接调用getBean获取
		if (beanNames.length == 1) {
			return getBean(beanNames[0], requiredType);
		}
		// 如果确实有多个autowire-candidate=true的Bean的话，需要找到primary=true的Bean
		else if (beanNames.length > 1) {
			T primaryBean = null;
			for (String beanName : beanNames) {
				T beanInstance = getBean(beanName, requiredType);
				if (isPrimary(beanName, beanInstance)) {
					if (primaryBean != null) {
						throw new NoUniqueBeanDefinitionException(requiredType, beanNames.length,
								"more than one 'primary' bean found of required type: " + Arrays.asList(beanNames));
					}
					primaryBean = beanInstance;
				}
			}
			if (primaryBean != null) {
				return primaryBean;
			}
			throw new NoUniqueBeanDefinitionException(requiredType, beanNames);
		}
		// 查找父容器
		else if (getParentBeanFactory() != null) {
			return getParentBeanFactory().getBean(requiredType);
		}
		// 找不到，抛异常
		else {
			throw new NoSuchBeanDefinitionException(requiredType);
		}
	}

	@Override
	public boolean containsBeanDefinition(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return this.beanDefinitionMap.containsKey(beanName);
	}

	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	public String[] getBeanDefinitionNames() {
		synchronized (this.beanDefinitionMap) {
			if (this.frozenBeanDefinitionNames != null) {
				return this.frozenBeanDefinitionNames;
			}
			else {
				return StringUtils.toStringArray(this.beanDefinitionNames);
			}
		}
	}

	public String[] getBeanNamesForType(Class<?> type) {
		return getBeanNamesForType(type, true, true);
	}

	public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		if (!isConfigurationFrozen() || type == null || !allowEagerInit) {
			return doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		}
		Map<Class<?>, String[]> cache =
				(includeNonSingletons ? this.allBeanNamesByType : this.singletonBeanNamesByType);
		String[] resolvedBeanNames = cache.get(type);
		if (resolvedBeanNames != null) {
			return resolvedBeanNames;
		}
		resolvedBeanNames = doGetBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		if (ClassUtils.isCacheSafe(type, getBeanClassLoader())) {
			cache.put(type, resolvedBeanNames);
		}
		return resolvedBeanNames;
	}

	private String[] doGetBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
		List<String> result = new ArrayList<String>();

		// Check all bean definitions.
		String[] beanDefinitionNames = getBeanDefinitionNames();
		for (String beanName : beanDefinitionNames) {
			// Only consider bean as eligible if the bean name
			// is not defined as alias for some other bean.
			if (!isAlias(beanName)) {
				try {
					RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
					// Only check bean definition if it is complete.
					if (!mbd.isAbstract() && (allowEagerInit ||
							((mbd.hasBeanClass() || !mbd.isLazyInit() || this.allowEagerClassLoading)) &&
									!requiresEagerInitForType(mbd.getFactoryBeanName()))) {
						// In case of FactoryBean, match object created by FactoryBean.
						boolean isFactoryBean = isFactoryBean(beanName, mbd);
						boolean matchFound = (allowEagerInit || !isFactoryBean || containsSingleton(beanName)) &&
								(includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type);
						if (!matchFound && isFactoryBean) {
							// In case of FactoryBean, try to match FactoryBean instance itself next.
							beanName = FACTORY_BEAN_PREFIX + beanName;
							matchFound = (includeNonSingletons || mbd.isSingleton()) && isTypeMatch(beanName, type);
						}
						if (matchFound) {
							result.add(beanName);
						}
					}
				}
				catch (CannotLoadBeanClassException ex) {
					if (allowEagerInit) {
						throw ex;
					}
					// Probably contains a placeholder: let's ignore it for type matching purposes.
					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Ignoring bean class loading failure for bean '" + beanName + "'", ex);
					}
					onSuppressedException(ex);
				}
				catch (BeanDefinitionStoreException ex) {
					if (allowEagerInit) {
						throw ex;
					}
					// Probably contains a placeholder: let's ignore it for type matching purposes.
					if (this.logger.isDebugEnabled()) {
						this.logger.debug("Ignoring unresolvable metadata in bean definition '" + beanName + "'", ex);
					}
					onSuppressedException(ex);
				}
			}
		}

		// Check singletons too, to catch manually registered singletons.
		String[] singletonNames = getSingletonNames();
		for (String beanName : singletonNames) {
			// Only check if manually registered.
			if (!containsBeanDefinition(beanName)) {
				// In case of FactoryBean, match object created by FactoryBean.
				if (isFactoryBean(beanName)) {
					if ((includeNonSingletons || isSingleton(beanName)) && isTypeMatch(beanName, type)) {
						result.add(beanName);
						// Match found for this bean: do not match FactoryBean itself anymore.
						continue;
					}
					// In case of FactoryBean, try to match FactoryBean itself next.
					beanName = FACTORY_BEAN_PREFIX + beanName;
				}
				// Match raw bean instance (might be raw FactoryBean).
				if (isTypeMatch(beanName, type)) {
					result.add(beanName);
				}
			}
		}

		return StringUtils.toStringArray(result);
	}

	/**
	 * Check whether the specified bean would need to be eagerly initialized
	 * in order to determine its type.
	 * @param factoryBeanName a factory-bean reference that the bean definition
	 * defines a factory method for
	 * @return whether eager initialization is necessary
	 */
	private boolean requiresEagerInitForType(String factoryBeanName) {
		return (factoryBeanName != null && isFactoryBean(factoryBeanName) && !containsSingleton(factoryBeanName));
	}

	public <T> Map<String, T> getBeansOfType(Class<T> type) throws BeansException {
		return getBeansOfType(type, true, true);
	}

	public <T> Map<String, T> getBeansOfType(Class<T> type, boolean includeNonSingletons, boolean allowEagerInit)
			throws BeansException {

		String[] beanNames = getBeanNamesForType(type, includeNonSingletons, allowEagerInit);
		Map<String, T> result = new LinkedHashMap<String, T>(beanNames.length);
		for (String beanName : beanNames) {
			try {
				result.put(beanName, getBean(beanName, type));
			}
			catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException) {
					BeanCreationException bce = (BeanCreationException) rootCause;
					if (isCurrentlyInCreation(bce.getBeanName())) {
						if (this.logger.isDebugEnabled()) {
							this.logger.debug("Ignoring match to currently created bean '" + beanName + "': " +
									ex.getMessage());
						}
						onSuppressedException(ex);
						// Ignore: indicates a circular reference when autowiring constructors.
						// We want to find matches other than the currently created bean itself.
						continue;
					}
				}
				throw ex;
			}
		}
		return result;
	}

	public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
		Map<String, Object> results = new LinkedHashMap<String, Object>();
		for (String beanName : getBeanDefinitionNames()) {
			BeanDefinition beanDefinition = getBeanDefinition(beanName);
			if (!beanDefinition.isAbstract() && findAnnotationOnBean(beanName, annotationType) != null) {
				results.put(beanName, getBean(beanName));
			}
		}
		for (String beanName : getSingletonNames()) {
			if (!results.containsKey(beanName) && findAnnotationOnBean(beanName, annotationType) != null) {
				results.put(beanName, getBean(beanName));
			}
		}
		return results;
	}

	/**
	 * Find a {@link Annotation} of {@code annotationType} on the specified
	 * bean, traversing its interfaces and super classes if no annotation can be
	 * found on the given class itself, as well as checking its raw bean class
	 * if not found on the exposed bean reference (e.g. in case of a proxy).
	 */
	public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType)
			throws NoSuchBeanDefinitionException{

		A ann = null;
		Class<?> beanType = getType(beanName);
		if (beanType != null) {
			ann = AnnotationUtils.findAnnotation(beanType, annotationType);
		}
		if (ann == null && containsBeanDefinition(beanName)) {
			BeanDefinition bd = getMergedBeanDefinition(beanName);
			if (bd instanceof AbstractBeanDefinition) {
				AbstractBeanDefinition abd = (AbstractBeanDefinition) bd;
				if (abd.hasBeanClass()) {
					ann = AnnotationUtils.findAnnotation(abd.getBeanClass(), annotationType);
				}
			}
		}
		return ann;
	}


	//---------------------------------------------------------------------
	// Implementation of ConfigurableListableBeanFactory interface
	//---------------------------------------------------------------------

	public void registerResolvableDependency(Class<?> dependencyType, Object autowiredValue) {
		Assert.notNull(dependencyType, "Dependency type must not be null");
		if (autowiredValue != null) {
			if (!(autowiredValue instanceof ObjectFactory || dependencyType.isInstance(autowiredValue))) {
				throw new IllegalArgumentException("Value [" + autowiredValue +
						"] does not implement specified dependency type [" + dependencyType.getName() + "]");
			}
			this.resolvableDependencies.put(dependencyType, autowiredValue);
		}
	}

	public boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {

		// Consider FactoryBeans as autowiring candidates.
		boolean isFactoryBean = (descriptor != null && descriptor.getDependencyType() != null &&
				FactoryBean.class.isAssignableFrom(descriptor.getDependencyType()));
		if (isFactoryBean) {
			beanName = BeanFactoryUtils.transformedBeanName(beanName);
		}

		if (containsBeanDefinition(beanName)) {
			return isAutowireCandidate(beanName, getMergedLocalBeanDefinition(beanName), descriptor);
		}
		else if (containsSingleton(beanName)) {
			return isAutowireCandidate(beanName, new RootBeanDefinition(getType(beanName)), descriptor);
		}
		else if (getParentBeanFactory() instanceof ConfigurableListableBeanFactory) {
			// No bean definition found in this factory -> delegate to parent.
			return ((ConfigurableListableBeanFactory) getParentBeanFactory()).isAutowireCandidate(beanName, descriptor);
		}
		else {
			return true;
		}
	}

	/**
	 * Determine whether the specified bean definition qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * @param beanName the name of the bean definition to check
	 * @param mbd the merged bean definition to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @return whether the bean should be considered as autowire candidate
	 */
	protected boolean isAutowireCandidate(String beanName, RootBeanDefinition mbd, DependencyDescriptor descriptor) {
		resolveBeanClass(mbd, beanName);
		if (mbd.isFactoryMethodUnique) {
			boolean resolve;
			synchronized (mbd.constructorArgumentLock) {
				resolve = (mbd.resolvedConstructorOrFactoryMethod == null);
			}
			if (resolve) {
				new ConstructorResolver(this).resolveFactoryMethodIfPossible(mbd);
			}
		}
		return getAutowireCandidateResolver().isAutowireCandidate(
				new BeanDefinitionHolder(mbd, beanName, getAliases(beanName)), descriptor);
	}

	@Override
	public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		BeanDefinition bd = this.beanDefinitionMap.get(beanName);
		if (bd == null) {
			if (this.logger.isTraceEnabled()) {
				this.logger.trace("No bean named '" + beanName + "' found in " + this);
			}
			throw new NoSuchBeanDefinitionException(beanName);
		}
		return bd;
	}

	public void freezeConfiguration() {
		this.configurationFrozen = true;
		synchronized (this.beanDefinitionMap) {
			this.frozenBeanDefinitionNames = StringUtils.toStringArray(this.beanDefinitionNames);
		}
	}

	public boolean isConfigurationFrozen() {
		return this.configurationFrozen;
	}

	/**
	 * Considers all beans as eligible for metadata caching
	 * if the factory's configuration has been marked as frozen.
	 * @see #freezeConfiguration()
	 */
	@Override
	protected boolean isBeanEligibleForMetadataCaching(String beanName) {
		return (this.configurationFrozen || super.isBeanEligibleForMetadataCaching(beanName));
	}

	public void preInstantiateSingletons() throws BeansException {
		if (this.logger.isInfoEnabled()) {
			this.logger.info("Pre-instantiating singletons in " + this);
		}

		List<String> beanNames;
		synchronized (this.beanDefinitionMap) {
			// Iterate over a copy to allow for init methods which in turn register new bean definitions.
			// While this may not be part of the regular factory bootstrap, it does otherwise work fine.
			beanNames = new ArrayList<String>(this.beanDefinitionNames);
		}

		// Trigger initialization of all non-lazy singleton beans...
		// 触发所有的非懒加载的singleton beans的初始化操作
		for (String beanName : beanNames) {
			/**
			 * 合并父Bean中的配置
			 * <bean id="" class="" parent=""/>
			 */
			RootBeanDefinition bd = getMergedLocalBeanDefinition(beanName);
			/**
			 * 非抽象
			 * 非懒加载
			 * 如果是abstract="true"的，不需要初始化
			 */
			if (!bd.isAbstract() && bd.isSingleton() && !bd.isLazyInit()) {
				// 处理FactoryBean
				if (isFactoryBean(beanName)) {
					/**
					 * FactoryBean，beanName前加上&符号
					 * 再调用getBean
					 */
					final FactoryBean<?> factory = (FactoryBean<?>) getBean(FACTORY_BEAN_PREFIX + beanName);
					/**
					 * 判断FactoryBean是否是SmartFactoryBean的实现
					 */
					boolean isEagerInit;
					if (System.getSecurityManager() != null && factory instanceof SmartFactoryBean) {
						isEagerInit = AccessController.doPrivileged(new PrivilegedAction<Boolean>() {
							public Boolean run() {
								return ((SmartFactoryBean<?>) factory).isEagerInit();
							}
						}, getAccessControlContext());
					}
					else {
						isEagerInit = (factory instanceof SmartFactoryBean &&
								((SmartFactoryBean<?>) factory).isEagerInit());
					}
					if (isEagerInit) {
						getBean(beanName);
					}
				}
				else {
					// 普通的Bean 调用getBean进行初始化
					getBean(beanName);
				}
			}
		}
	}


	//---------------------------------------------------------------------
	// Implementation of BeanDefinitionRegistry interface
	//---------------------------------------------------------------------

	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
			throws BeanDefinitionStoreException {

		Assert.hasText(beanName, "Bean name must not be empty");
		Assert.notNull(beanDefinition, "BeanDefinition must not be null");

		// 注册前最后一次校验BeanDefinition，主要是对methodOverrides进行校验
		if (beanDefinition instanceof AbstractBeanDefinition) {
			try {
				((AbstractBeanDefinition) beanDefinition).validate();
			}
			catch (BeanDefinitionValidationException ex) {
				throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
						"Validation of bean definition failed", ex);
			}
		}

		/**
		 * allowBeanDefinitionOverriding
		 * 允许bean覆盖
		 */
		BeanDefinition oldBeanDefinition;

		synchronized (this.beanDefinitionMap) {
			// 所有的Bean注册后，都会放到这个beanDefinitionMap中
			oldBeanDefinition = this.beanDefinitionMap.get(beanName);
			if (oldBeanDefinition != null) {
				// 如果不允许覆盖，抛异常
				if (!this.allowBeanDefinitionOverriding) {
					throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
							"Cannot register bean definition [" + beanDefinition + "] for bean '" + beanName +
							"': There is already [" + oldBeanDefinition + "] bound.");
				}
				else {
					if (this.logger.isInfoEnabled()) {
						this.logger.info("Overriding bean definition for bean '" + beanName +
								"': replacing [" + oldBeanDefinition + "] with [" + beanDefinition + "]");
					}
				}
			}
			else {
				this.beanDefinitionNames.add(beanName);
				this.frozenBeanDefinitionNames = null;
			}
			this.beanDefinitionMap.put(beanName, beanDefinition);
		}

		// 重新设置beanName对应的缓存
		if (oldBeanDefinition != null || containsSingleton(beanName)) {
			resetBeanDefinition(beanName);
		}
	}

	public void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException {
		Assert.hasText(beanName, "'beanName' must not be empty");

		synchronized (this.beanDefinitionMap) {
			BeanDefinition bd = this.beanDefinitionMap.remove(beanName);
			if (bd == null) {
				if (this.logger.isTraceEnabled()) {
					this.logger.trace("No bean named '" + beanName + "' found in " + this);
				}
				throw new NoSuchBeanDefinitionException(beanName);
			}
			this.beanDefinitionNames.remove(beanName);
			this.frozenBeanDefinitionNames = null;
		}

		resetBeanDefinition(beanName);
	}

	/**
	 * Reset all bean definition caches for the given bean,
	 * including the caches of beans that are derived from it.
	 * @param beanName the name of the bean to reset
	 */
	protected void resetBeanDefinition(String beanName) {
		// Remove the merged bean definition for the given bean, if already created.
		clearMergedBeanDefinition(beanName);

		// Remove corresponding bean from singleton cache, if any. Shouldn't usually
		// be necessary, rather just meant for overriding a context's default beans
		// (e.g. the default StaticMessageSource in a StaticApplicationContext).
		destroySingleton(beanName);

		// Reset all bean definitions that have the given bean as parent (recursively).
		for (String bdName : this.beanDefinitionNames) {
			if (!beanName.equals(bdName)) {
				BeanDefinition bd = this.beanDefinitionMap.get(bdName);
				if (beanName.equals(bd.getParentName())) {
					resetBeanDefinition(bdName);
				}
			}
		}
	}

	/**
	 * Only allows alias overriding if bean definition overriding is allowed.
	 */
	@Override
	protected boolean allowAliasOverriding() {
		return this.allowBeanDefinitionOverriding;
	}

	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		super.registerSingleton(beanName, singletonObject);
		clearByTypeCache();
	}

	@Override
	public void destroySingleton(String beanName) {
		super.destroySingleton(beanName);
		clearByTypeCache();
	}

	/**
	 * Remove any assumptions about by-type mappings.
	 */
	private void clearByTypeCache() {
		this.allBeanNamesByType.clear();
		this.singletonBeanNamesByType.clear();
	}


	//---------------------------------------------------------------------
	// Dependency resolution functionality
	//---------------------------------------------------------------------

	public Object resolveDependency(DependencyDescriptor descriptor, String beanName,
			Set<String> autowiredBeanNames, TypeConverter typeConverter) throws BeansException {

		descriptor.initParameterNameDiscovery(getParameterNameDiscoverer());
		if (descriptor.getDependencyType().equals(ObjectFactory.class)) {
			return new DependencyObjectFactory(descriptor, beanName);
		}
		else if (descriptor.getDependencyType().equals(javaxInjectProviderClass)) {
			return new DependencyProviderFactory().createDependencyProvider(descriptor, beanName);
		}
		else {
			return doResolveDependency(descriptor, descriptor.getDependencyType(), beanName, autowiredBeanNames, typeConverter);
		}
	}

	protected Object doResolveDependency(DependencyDescriptor descriptor, Class<?> type, String beanName,
			Set<String> autowiredBeanNames, TypeConverter typeConverter) throws BeansException {

		Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);
		if (value != null) {
			if (value instanceof String) {
				String strVal = resolveEmbeddedValue((String) value);
				BeanDefinition bd = (beanName != null && containsBean(beanName) ? getMergedBeanDefinition(beanName) : null);
				value = evaluateBeanDefinitionString(strVal, bd);
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			return (descriptor.getField() != null ?
					converter.convertIfNecessary(value, type, descriptor.getField()) :
					converter.convertIfNecessary(value, type, descriptor.getMethodParameter()));
		}

		if (type.isArray()) {
			Class<?> componentType = type.getComponentType();
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, componentType, descriptor);
			if (matchingBeans.isEmpty()) {
				if (descriptor.isRequired()) {
					raiseNoSuchBeanDefinitionException(componentType, "array of " + componentType.getName(), descriptor);
				}
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			return converter.convertIfNecessary(matchingBeans.values(), type);
		}
		else if (Collection.class.isAssignableFrom(type) && type.isInterface()) {
			Class<?> elementType = descriptor.getCollectionType();
			if (elementType == null) {
				if (descriptor.isRequired()) {
					throw new FatalBeanException("No element type declared for collection [" + type.getName() + "]");
				}
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, elementType, descriptor);
			if (matchingBeans.isEmpty()) {
				if (descriptor.isRequired()) {
					raiseNoSuchBeanDefinitionException(elementType, "collection of " + elementType.getName(), descriptor);
				}
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
			return converter.convertIfNecessary(matchingBeans.values(), type);
		}
		else if (Map.class.isAssignableFrom(type) && type.isInterface()) {
			Class<?> keyType = descriptor.getMapKeyType();
			if (keyType == null || !String.class.isAssignableFrom(keyType)) {
				if (descriptor.isRequired()) {
					throw new FatalBeanException("Key type [" + keyType + "] of map [" + type.getName() +
							"] must be assignable to [java.lang.String]");
				}
				return null;
			}
			Class<?> valueType = descriptor.getMapValueType();
			if (valueType == null) {
				if (descriptor.isRequired()) {
					throw new FatalBeanException("No value type declared for map [" + type.getName() + "]");
				}
				return null;
			}
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, valueType, descriptor);
			if (matchingBeans.isEmpty()) {
				if (descriptor.isRequired()) {
					raiseNoSuchBeanDefinitionException(valueType, "map with value type " + valueType.getName(), descriptor);
				}
				return null;
			}
			if (autowiredBeanNames != null) {
				autowiredBeanNames.addAll(matchingBeans.keySet());
			}
			return matchingBeans;
		}
		else {
			Map<String, Object> matchingBeans = findAutowireCandidates(beanName, type, descriptor);
			if (matchingBeans.isEmpty()) {
				if (descriptor.isRequired()) {
					raiseNoSuchBeanDefinitionException(type, "", descriptor);
				}
				return null;
			}
			if (matchingBeans.size() > 1) {
				String primaryBeanName = determinePrimaryCandidate(matchingBeans, descriptor);
				if (primaryBeanName == null) {
					throw new NoUniqueBeanDefinitionException(type, matchingBeans.keySet());
				}
				if (autowiredBeanNames != null) {
					autowiredBeanNames.add(primaryBeanName);
				}
				return matchingBeans.get(primaryBeanName);
			}
			// We have exactly one match.
			Map.Entry<String, Object> entry = matchingBeans.entrySet().iterator().next();
			if (autowiredBeanNames != null) {
				autowiredBeanNames.add(entry.getKey());
			}
			return entry.getValue();
		}
	}

	/**
	 * Find bean instances that match the required type.
	 * Called during autowiring for the specified bean.
	 * @param beanName the name of the bean that is about to be wired
	 * @param requiredType the actual type of bean to look for
	 * (may be an array component type or collection element type)
	 * @param descriptor the descriptor of the dependency to resolve
	 * @return a Map of candidate names and candidate instances that match
	 * the required type (never {@code null})
	 * @throws BeansException in case of errors
	 * @see #autowireByType
	 * @see #autowireConstructor
	 */
	protected Map<String, Object> findAutowireCandidates(
			String beanName, Class<?> requiredType, DependencyDescriptor descriptor) {

		String[] candidateNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
				this, requiredType, true, descriptor.isEager());
		Map<String, Object> result = new LinkedHashMap<String, Object>(candidateNames.length);
		for (Class<?> autowiringType : this.resolvableDependencies.keySet()) {
			if (autowiringType.isAssignableFrom(requiredType)) {
				Object autowiringValue = this.resolvableDependencies.get(autowiringType);
				autowiringValue = AutowireUtils.resolveAutowiringValue(autowiringValue, requiredType);
				if (requiredType.isInstance(autowiringValue)) {
					result.put(ObjectUtils.identityToString(autowiringValue), autowiringValue);
					break;
				}
			}
		}
		for (String candidateName : candidateNames) {
			if (!candidateName.equals(beanName) && isAutowireCandidate(candidateName, descriptor)) {
				result.put(candidateName, getBean(candidateName));
			}
		}
		return result;
	}

	/**
	 * Determine the primary autowire candidate in the given set of beans.
	 * @param candidateBeans a Map of candidate names and candidate instances
	 * that match the required type, as returned by {@link #findAutowireCandidates}
	 * @param descriptor the target dependency to match against
	 * @return the name of the primary candidate, or {@code null} if none found
	 */
	protected String determinePrimaryCandidate(Map<String, Object> candidateBeans, DependencyDescriptor descriptor) {
		String primaryBeanName = null;
		String fallbackBeanName = null;
		for (Map.Entry<String, Object> entry : candidateBeans.entrySet()) {
			String candidateBeanName = entry.getKey();
			Object beanInstance = entry.getValue();
			if (isPrimary(candidateBeanName, beanInstance)) {
				if (primaryBeanName != null) {
					boolean candidateLocal = containsBeanDefinition(candidateBeanName);
					boolean primaryLocal = containsBeanDefinition(primaryBeanName);
					if (candidateLocal == primaryLocal) {
						throw new NoUniqueBeanDefinitionException(descriptor.getDependencyType(), candidateBeans.size(),
								"more than one 'primary' bean found among candidates: " + candidateBeans.keySet());
					}
					else if (candidateLocal && !primaryLocal) {
						primaryBeanName = candidateBeanName;
					}
				}
				else {
					primaryBeanName = candidateBeanName;
				}
			}
			if (primaryBeanName == null &&
					(this.resolvableDependencies.values().contains(beanInstance) ||
							matchesBeanName(candidateBeanName, descriptor.getDependencyName()))) {
				fallbackBeanName = candidateBeanName;
			}
		}
		return (primaryBeanName != null ? primaryBeanName : fallbackBeanName);
	}

	/**
	 * Return whether the bean definition for the given bean name has been
	 * marked as a primary bean.
	 * @param beanName the name of the bean
	 * @param beanInstance the corresponding bean instance
	 * @return whether the given bean qualifies as primary
	 */
	protected boolean isPrimary(String beanName, Object beanInstance) {
		if (containsBeanDefinition(beanName)) {
			return getMergedLocalBeanDefinition(beanName).isPrimary();
		}
		BeanFactory parentFactory = getParentBeanFactory();
		return (parentFactory instanceof DefaultListableBeanFactory &&
				((DefaultListableBeanFactory) parentFactory).isPrimary(beanName, beanInstance));
	}

	/**
	 * Determine whether the given candidate name matches the bean name or the aliases
	 * stored in this bean definition.
	 */
	protected boolean matchesBeanName(String beanName, String candidateName) {
		return (candidateName != null &&
				(candidateName.equals(beanName) || ObjectUtils.containsElement(getAliases(beanName), candidateName)));
	}

	/**
	 * Raise a NoSuchBeanDefinitionException for an unresolvable dependency.
	 */
	private void raiseNoSuchBeanDefinitionException(
			Class<?> type, String dependencyDescription, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException {

		throw new NoSuchBeanDefinitionException(type, dependencyDescription,
				"expected at least 1 bean which qualifies as autowire candidate for this dependency. " +
				"Dependency annotations: " + ObjectUtils.nullSafeToString(descriptor.getAnnotations()));
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(ObjectUtils.identityToString(this));
		sb.append(": defining beans [");
		sb.append(StringUtils.arrayToCommaDelimitedString(getBeanDefinitionNames()));
		sb.append("]; ");
		BeanFactory parent = getParentBeanFactory();
		if (parent == null) {
			sb.append("root of factory hierarchy");
		}
		else {
			sb.append("parent: ").append(ObjectUtils.identityToString(parent));
		}
		return sb.toString();
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("DefaultListableBeanFactory itself is not deserializable - " +
				"just a SerializedBeanFactoryReference is");
	}

	protected Object writeReplace() throws ObjectStreamException {
		if (this.serializationId != null) {
			return new SerializedBeanFactoryReference(this.serializationId);
		}
		else {
			throw new NotSerializableException("DefaultListableBeanFactory has no serialization id");
		}
	}


	/**
	 * Minimal id reference to the factory.
	 * Resolved to the actual factory instance on deserialization.
	 */
	private static class SerializedBeanFactoryReference implements Serializable {

		private final String id;

		public SerializedBeanFactoryReference(String id) {
			this.id = id;
		}

		private Object readResolve() {
			Reference<?> ref = serializableFactories.get(this.id);
			if (ref == null) {
				throw new IllegalStateException(
						"Cannot deserialize BeanFactory with id " + this.id + ": no factory registered for this id");
			}
			Object result = ref.get();
			if (result == null) {
				throw new IllegalStateException(
						"Cannot deserialize BeanFactory with id " + this.id + ": factory has been garbage-collected");
			}
			return result;
		}
	}


	/**
	 * Serializable ObjectFactory for lazy resolution of a dependency.
	 */
	private class DependencyObjectFactory implements ObjectFactory<Object>, Serializable {

		private final DependencyDescriptor descriptor;

		private final String beanName;

		public DependencyObjectFactory(DependencyDescriptor descriptor, String beanName) {
			this.descriptor = new DependencyDescriptor(descriptor);
			this.descriptor.increaseNestingLevel();
			this.beanName = beanName;
		}

		public Object getObject() throws BeansException {
			return doResolveDependency(this.descriptor, this.descriptor.getDependencyType(), this.beanName, null, null);
		}
	}


	/**
	 * Serializable ObjectFactory for lazy resolution of a dependency.
	 */
	private class DependencyProvider extends DependencyObjectFactory implements Provider<Object> {

		public DependencyProvider(DependencyDescriptor descriptor, String beanName) {
			super(descriptor, beanName);
		}

		public Object get() throws BeansException {
			return getObject();
		}
	}


	/**
	 * Separate inner class for avoiding a hard dependency on the {@code javax.inject} API.
	 */
	private class DependencyProviderFactory {

		public Object createDependencyProvider(DependencyDescriptor descriptor, String beanName) {
			return new DependencyProvider(descriptor, beanName);
		}
	}

}
