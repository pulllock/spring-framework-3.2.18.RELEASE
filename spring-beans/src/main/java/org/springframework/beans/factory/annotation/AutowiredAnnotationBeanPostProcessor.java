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

package org.springframework.beans.factory.annotation;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link org.springframework.beans.factory.config.BeanPostProcessor} implementation
 * that autowires annotated fields, setter methods and arbitrary config methods.
 * Such members to be injected are detected through a Java 5 annotation: by default,
 * Spring's {@link Autowired @Autowired} and {@link Value @Value} annotations.
 * 支持@Autowired注解和@Value注解
 *
 * <p>Also supports JSR-330's {@link javax.inject.Inject @Inject} annotation,
 * if available, as a direct alternative to Spring's own {@code @Autowired}.
 * 也支持JSR-330的@Inject注解
 *
 * <p>Only one constructor (at max) of any given bean class may carry this
 * annotation with the 'required' parameter set to {@code true},
 * indicating <i>the</i> constructor to autowire when used as a Spring bean.
 * If multiple <i>non-required</i> constructors carry the annotation, they
 * will be considered as candidates for autowiring. The constructor with
 * the greatest number of dependencies that can be satisfied by matching
 * beans in the Spring container will be chosen. If none of the candidates
 * can be satisfied, then a default constructor (if present) will be used.
 * An annotated constructor does not have to be public.
 * 只能有一个构造器可以注解@Autowired，并且required设置为true
 *
 * <p>Fields are injected right after construction of a bean, before any
 * config methods are invoked. Such a config field does not have to be public.
 * 域的注入在bean的构造之后，在配置方法调用之前
 *
 * <p>Config methods may have an arbitrary name and any number of arguments; each of
 * those arguments will be autowired with a matching bean in the Spring container.
 * Bean property setter methods are effectively just a special case of such a
 * general config method. Config methods do not have to be public.
 *
 * <p>Note: A default AutowiredAnnotationBeanPostProcessor will be registered
 * by the "context:annotation-config" and "context:component-scan" XML tags.
 * Remove or turn off the default annotation configuration there if you intend
 * to specify a custom AutowiredAnnotationBeanPostProcessor bean definition.
 * <p><b>NOTE:</b> Annotation injection will be performed <i>before</i> XML injection;
 * thus the latter configuration will override the former for properties wired through
 * both approaches.
 * 使用<context:annotation-config/>和<context:component-scan/>标签，
 * 或注册默认的AutowiredAnnotationBeanPostProcessor
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.5
 * @see #setAutowiredAnnotationType
 * @see Autowired
 * @see Value
 */
public class AutowiredAnnotationBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter
		implements MergedBeanDefinitionPostProcessor, PriorityOrdered, BeanFactoryAware {

	protected final Log logger = LogFactory.getLog(getClass());

	private final Set<Class<? extends Annotation>> autowiredAnnotationTypes =
			new LinkedHashSet<Class<? extends Annotation>>();

	private String requiredParameterName = "required";

	private boolean requiredParameterValue = true;

	private int order = Ordered.LOWEST_PRECEDENCE - 2;

	private ConfigurableListableBeanFactory beanFactory;

	/**
	 * 候选构造器缓存，存储被@Autowired、@Inject、@Value注解的构造方法
	 */
	private final Map<Class<?>, Constructor<?>[]> candidateConstructorsCache =
			new ConcurrentHashMap<Class<?>, Constructor<?>[]>(64);

	/**
	 * 注入的元数据缓存，key是Bean名称，value是对应的注入元数据
	 */
	private final Map<String, InjectionMetadata> injectionMetadataCache =
			new ConcurrentHashMap<String, InjectionMetadata>(64);


	/**
	 * Create a new AutowiredAnnotationBeanPostProcessor
	 * for Spring's standard {@link Autowired} annotation.
	 * <p>Also supports JSR-330's {@link javax.inject.Inject} annotation, if available.
	 * 实例化的时候会先把Autowired、Value、Inject注解加入到Set中缓存起来
	 */
	@SuppressWarnings("unchecked")
	public AutowiredAnnotationBeanPostProcessor() {
		/**
		 * 注解类型：
		 * Autowired
		 * Value
		 * Inject
		 */
		this.autowiredAnnotationTypes.add(Autowired.class);
		this.autowiredAnnotationTypes.add(Value.class);
		try {
			this.autowiredAnnotationTypes.add((Class<? extends Annotation>)
					ClassUtils.forName("javax.inject.Inject", AutowiredAnnotationBeanPostProcessor.class.getClassLoader()));
			logger.info("JSR-330 'javax.inject.Inject' annotation found and supported for autowiring");
		}
		catch (ClassNotFoundException ex) {
			// JSR-330 API not available - simply skip.
		}
	}


	/**
	 * Set the 'autowired' annotation type, to be used on constructors, fields,
	 * setter methods and arbitrary config methods.
	 * <p>The default autowired annotation type is the Spring-provided
	 * {@link Autowired} annotation, as well as {@link Value}.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation type to indicate that a member is
	 * supposed to be autowired.
	 */
	public void setAutowiredAnnotationType(Class<? extends Annotation> autowiredAnnotationType) {
		Assert.notNull(autowiredAnnotationType, "'autowiredAnnotationType' must not be null");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.add(autowiredAnnotationType);
	}

	/**
	 * Set the 'autowired' annotation types, to be used on constructors, fields,
	 * setter methods and arbitrary config methods.
	 * <p>The default autowired annotation type is the Spring-provided
	 * {@link Autowired} annotation, as well as {@link Value}.
	 * <p>This setter property exists so that developers can provide their own
	 * (non-Spring-specific) annotation types to indicate that a member is
	 * supposed to be autowired.
	 */
	public void setAutowiredAnnotationTypes(Set<Class<? extends Annotation>> autowiredAnnotationTypes) {
		Assert.notEmpty(autowiredAnnotationTypes, "'autowiredAnnotationTypes' must not be empty");
		this.autowiredAnnotationTypes.clear();
		this.autowiredAnnotationTypes.addAll(autowiredAnnotationTypes);
	}

	/**
	 * Set the name of a parameter of the annotation that specifies
	 * whether it is required.
	 * @see #setRequiredParameterValue(boolean)
	 */
	public void setRequiredParameterName(String requiredParameterName) {
		this.requiredParameterName = requiredParameterName;
	}

	/**
	 * Set the boolean value that marks a dependency as required
	 * <p>For example if using 'required=true' (the default),
	 * this value should be {@code true}; but if using
	 * 'optional=false', this value should be {@code false}.
	 * @see #setRequiredParameterName(String)
	 */
	public void setRequiredParameterValue(boolean requiredParameterValue) {
		this.requiredParameterValue = requiredParameterValue;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public int getOrder() {
		return this.order;
	}

	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ConfigurableListableBeanFactory");
		}
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}


	/**
	 * MergedBeanDefinitionPostProcessor中的方法，
	 * 在Bean实例化之后可以用来修改MergedBeanDefinition的一些属性或者用来缓存一些元数据信息供后来使用
	 * @param beanDefinition the merged bean definition for the bean
	 * @param beanType the actual type of the managed bean instance
	 * @param beanName the name of the bean
	 *
	 * 这里用来查找自动注入的元数据
	 */
	public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
		if (beanType != null) {
			/**
			 * 查找注入的元数据
			 * InjectionMetadata 注入元数据，用来表示注入的元数据信息，里面包括了目标类和注入的元素等
			 */
			InjectionMetadata metadata = findAutowiringMetadata(beanName, beanType, null);
			// TODO 检查一些啥啥啥。。。
			metadata.checkConfigMembers(beanDefinition);
		}
	}

	/**
	 * 查看类的构造方法是否有@Autowired注解，
	 * 并且被注解了的构造方法必须有参数，
	 * 且最多只能有一个构造方法被设置为required=true
	 * @param beanClass
	 * @param beanName
	 * @return 空或者构造方法数组
	 * @throws BeansException
	 * 这个方法用来查找构造器，在实例化当前Bean的时候会被先执行
	 */
	@Override
	public Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) throws BeansException {
		// Quick check on the concurrent map first, with minimal locking.
		// 先从缓存中查找查找Bean有没有已经缓存的构造器
		Constructor<?>[] candidateConstructors = this.candidateConstructorsCache.get(beanClass);
		// 缓存中没有Bean对应的构造器，需要进行解析构造器
		if (candidateConstructors == null) {
			synchronized (this.candidateConstructorsCache) {
				candidateConstructors = this.candidateConstructorsCache.get(beanClass);
				if (candidateConstructors == null) {
					// 利用反射得到Bean的构造器
					Constructor<?>[] rawCandidates = beanClass.getDeclaredConstructors();
					List<Constructor<?>> candidates = new ArrayList<Constructor<?>>(rawCandidates.length);
					Constructor<?> requiredConstructor = null;
					Constructor<?> defaultConstructor = null;

					// 遍历构造器
					for (Constructor<?> candidate : rawCandidates) {
						// 查找构造方法上有没有@Autowired等注解，需要从autowiredAnnotationTypes这个Set中进行遍历
						// 这个Set中保存有Autowired、Value、Inject三个注解
						Annotation ann = findAutowiredAnnotation(candidate);
						/**
						 * 如果构造方法上有@Autowired注解的话：
						 * 如果已经有required=true的构造方法，直接抛异常；
						 * 如果被注解的构造方法没有参数，直接抛异常；
						 */
						if (ann != null) {

							// 如果有一个注解的required为true，则其他的构造方法上不能有@Autowired等注解
							if (requiredConstructor != null) {
								throw new BeanCreationException(beanName,
										"Invalid autowire-marked constructor: " + candidate +
										". Found constructor with 'required' Autowired annotation already: " +
										requiredConstructor);
							}
							// 如果是无参构造方法，抛异常。@Autowired注解的构造方法需要有参数
							if (candidate.getParameterTypes().length == 0) {
								throw new IllegalStateException(
										"Autowired annotation requires at least one argument: " + candidate);
							}
							// 查询required参数的状态，即required=true或者false
							// @Inject和@Value没有required参数，默认返回true
							boolean required = determineRequiredStatus(ann);
							// 如果有个Autowired的required参数为true，并且还有其他的@Autowired或者@Inject或者@Value注解，抛异常
							if (required) {
								if (!candidates.isEmpty()) {
									throw new BeanCreationException(beanName,
											"Invalid autowire-marked constructors: " + candidates +
											". Found constructor with 'required' Autowired annotation: " +
											candidate);
								}
								requiredConstructor = candidate;
							}
							// 加入到候选构造器集合中去
							candidates.add(candidate);
						}
						// 参数个数为0的是默认构造方法
						else if (candidate.getParameterTypes().length == 0) {
							defaultConstructor = candidate;
						}
					}
					// 候选集合不为空的话，说明有@Autowired注解的构造方法
					if (!candidates.isEmpty()) {
						// Add default constructor to list of optional constructors, as fallback.
						/**
						 * 如果没有required=true的构造器：
						 * 如果有默认的构造器，就将默认的构造器添加到list中，备用
						 */
						if (requiredConstructor == null) {
							if (defaultConstructor != null) {
								candidates.add(defaultConstructor);
							}
							else if (candidates.size() == 1 && logger.isWarnEnabled()) {
								logger.warn("Inconsistent constructor declaration on bean with name '" + beanName +
										"': single autowire-marked constructor flagged as optional - this constructor " +
										"is effectively required since there is no default constructor to fall back to: " +
										candidates.get(0));
							}
						}
						candidateConstructors = candidates.toArray(new Constructor<?>[candidates.size()]);
					}
					// 没有构造器
					else {
						candidateConstructors = new Constructor<?>[0];
					}
					// 放到候选构造器缓存中
					this.candidateConstructorsCache.put(beanClass, candidateConstructors);
				}
			}
		}
		// 返回构造器列表或者null
		return (candidateConstructors.length > 0 ? candidateConstructors : null);
	}

	/**
	 * 在Bean的属性被设置之前调用，可以用来将数据注入到属性中
	 * @param pvs
	 * @param pds
	 * @param bean
	 * @param beanName
	 * @return
	 * @throws BeansException
	 */
	@Override
	public PropertyValues postProcessPropertyValues(
			PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) throws BeansException {

		// 查找要注入的元数据，从缓存中或者新构造元数据
		// 在上一步Bean实例化后的postProcessMergedBeanDefinition方法中会有构造注入元数据到缓存的步骤
		InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
		try {
			// 注入元数据，有AutowiredFieldElement和AutowiredMethodElement两种注入
			// 循环调用已经解析过的InjectElement的inject方法，挨个注入
			metadata.inject(bean, beanName, pvs);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
		}
		return pvs;
	}

	/**
	 * 'Native' processing method for direct calls with an arbitrary target instance,
	 * resolving all of its fields and methods which are annotated with {@code @Autowired}.
	 * @param bean the target instance to process
	 * @throws BeansException if autowiring failed
	 */
	public void processInjection(Object bean) throws BeansException {
		Class<?> clazz = bean.getClass();
		InjectionMetadata metadata = findAutowiringMetadata(clazz.getName(), clazz, null);
		try {
			metadata.inject(bean, null, null);
		}
		catch (Throwable ex) {
			throw new BeanCreationException("Injection of autowired dependencies failed for class [" + clazz + "]", ex);
		}
	}


	/**
	 * 查找注入的元数据
	 * @param beanName
	 * @param clazz
	 * @param pvs
	 * @return
	 */
	private InjectionMetadata findAutowiringMetadata(String beanName, Class<?> clazz, PropertyValues pvs) {
		// Fall back to class name as cache key, for backwards compatibility with custom callers.
		// 缓存key，使用Bean的名称
		String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());
		// Quick check on the concurrent map first, with minimal locking.
		// 从注入元数据缓存中查找
		InjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
		// 缓存中查找不到元数据或者注入元数据中的目标类和clazz不同，就需要刷新
		if (InjectionMetadata.needsRefresh(metadata, clazz)) {
			synchronized (this.injectionMetadataCache) {
				metadata = this.injectionMetadataCache.get(cacheKey);
				if (InjectionMetadata.needsRefresh(metadata, clazz)) {
					if (metadata != null) {
						metadata.clear(pvs);
					}
					// 构造自动注入元数据，并加入到注入元数据缓存
					metadata = buildAutowiringMetadata(clazz);
					this.injectionMetadataCache.put(cacheKey, metadata);
				}
			}
		}
		return metadata;
	}

	/**
	 * 构造自动注入元数据
	 * 对指定的class查找这个class和父类，一直找到最顶层，
	 * 找到所有@Autowired注解的字段和方法，如果是桥接方法，
	 * 需要找到原始方法，最后构造成InjectionMetadata对象返回。
	 * @param clazz
	 * @return
	 */
	private InjectionMetadata buildAutowiringMetadata(Class<?> clazz) {
		LinkedList<InjectionMetadata.InjectedElement> elements = new LinkedList<InjectionMetadata.InjectedElement>();
		Class<?> targetClass = clazz;

		// 从当前类开始查找，依次往上查找父类，一直到Object
		do {
			LinkedList<InjectionMetadata.InjectedElement> currElements = new LinkedList<InjectionMetadata.InjectedElement>();
			// 先遍历当前类的字段
			for (Field field : targetClass.getDeclaredFields()) {
				// 看这个字段有没有被@Autowired等注解标注
				Annotation ann = findAutowiredAnnotation(field);
				// 有被注解
				if (ann != null) {
					// 如果字段是静态字段，则注解@Autowired等没有效果，直接跳过不处理
					if (Modifier.isStatic(field.getModifiers())) {
						if (logger.isWarnEnabled()) {
							logger.warn("Autowired annotation is not supported on static fields: " + field);
						}
						continue;
					}
					// 查找required属性的值
					boolean required = determineRequiredStatus(ann);
					// 构造一个AutowiredFieldElement对象，添加到列表中，AutowiredFieldElement对象用来存储注入的元素的一些元数据信息
					currElements.add(new AutowiredFieldElement(field, required));
				}
			}

			// 遍历当前类的方法，看有没有被@Autowired等注解标注
			for (Method method : targetClass.getDeclaredMethods()) {
				Annotation ann = null;
				// 如果是桥接方法，则要找到原始方法；如果不是原始方法，则直接返回
				// TODO 桥接方法
				Method bridgedMethod = BridgeMethodResolver.findBridgedMethod(method);
				if (BridgeMethodResolver.isVisibilityBridgeMethodPair(method, bridgedMethod)) {
					// 查找看该方法上面有没有被@Autowired注解标注
					ann = findAutowiredAnnotation(bridgedMethod);
				}
				// 方法有被@Autowired注解标注
				if (ann != null && method.equals(ClassUtils.getMostSpecificMethod(method, clazz))) {
					// 方法是静态方法，注解无效，直接跳过
					if (Modifier.isStatic(method.getModifiers())) {
						if (logger.isWarnEnabled()) {
							logger.warn("Autowired annotation is not supported on static methods: " + method);
						}
						continue;
					}
					// 方法没有参数，打印警告日志
					if (method.getParameterTypes().length == 0) {
						if (logger.isWarnEnabled()) {
							logger.warn("Autowired annotation should be used on methods with actual parameters: " + method);
						}
					}
					// 获取required属性值
					boolean required = determineRequiredStatus(ann);
					// 获取PropertyDescriptor TODO
					PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, clazz);
					// 构建AutowiredMethodElement对象，放到列表中，AutowiredMethodElement表示的是注解的方法的元数据
					currElements.add(new AutowiredMethodElement(method, required, pd));
				}
			}
			// 将当前类的注入元数据添加到elements集合中
			elements.addAll(0, currElements);
			// 继续查找父类
			targetClass = targetClass.getSuperclass();
		}
		// 一直查到Object类才结束
		while (targetClass != null && targetClass != Object.class);

		// 构造一个注入元数据对象，其中包括注入的目标类以及要注入的元数据信息
		return new InjectionMetadata(clazz, elements);
	}

	/**
	 *  查找构造方法上有没有@Autowired等注解，需要从autowiredAnnotationTypes这个Set中进行遍历
	 * 	这个Set中保存有Autowired、Value、Inject三个注解
	 * @param ao
	 * @return
	 */
	private Annotation findAutowiredAnnotation(AccessibleObject ao) {
		for (Class<? extends Annotation> type : this.autowiredAnnotationTypes) {
			Annotation ann = AnnotationUtils.getAnnotation(ao, type);
			if (ann != null) {
				return ann;
			}
		}
		return null;
	}

	/**
	 * Obtain all beans of the given type as autowire candidates.
	 * @param type the type of the bean
	 * @return the target beans, or an empty Collection if no bean of this type is found
	 * @throws BeansException if bean retrieval failed
	 */
	protected <T> Map<String, T> findAutowireCandidates(Class<T> type) throws BeansException {
		if (this.beanFactory == null) {
			throw new IllegalStateException("No BeanFactory configured - " +
					"override the getBeanOfType method or specify the 'beanFactory' property");
		}
		return BeanFactoryUtils.beansOfTypeIncludingAncestors(this.beanFactory, type);
	}

	/**
	 * Determine if the annotated field or method requires its dependency.
	 * <p>A 'required' dependency means that autowiring should fail when no beans
	 * are found. Otherwise, the autowiring process will simply bypass the field
	 * or method when no beans are found.
	 * @param ann the Autowired annotation
	 * @return whether the annotation indicates that a dependency is required
	 * 查看注解中的required属性值
	 * 在@Inject和@Value中没有required属性，默认直接返回true
	 */
	protected boolean determineRequiredStatus(Annotation ann) {
		try {
			Method method = ReflectionUtils.findMethod(ann.annotationType(), this.requiredParameterName);
			if (method == null) {
				// Annotations like @Inject and @Value don't have a method (attribute) named "required"
				// -> default to required status
				return true;
			}
			return (this.requiredParameterValue == (Boolean) ReflectionUtils.invokeMethod(method, ann));
		}
		catch (Exception ex) {
			// An exception was thrown during reflective invocation of the required attribute
			// -> default to required status
			return true;
		}
	}

	/**
	 * Register the specified bean as dependent on the autowired beans.
	 * 注册依赖的Bean
	 * beanName 是注入的目标对象
	 * autowiredBeanNames 是目标对象中需要被注入的Bean
	 */
	private void registerDependentBeans(String beanName, Set<String> autowiredBeanNames) {
		if (beanName != null) {
			for (String autowiredBeanName : autowiredBeanNames) {
				// 从容器中查找需要被注入的Bean
				if (this.beanFactory.containsBean(autowiredBeanName)) {
					// 将autowiredBeanName对象注入到beanName代表的对象中去
					// 其实就是在容器中记录谁依赖了我和我依赖了谁的关系，记录到两个map中去
					this.beanFactory.registerDependentBean(autowiredBeanName, beanName);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Autowiring by type from bean name '" + beanName +
							"' to bean named '" + autowiredBeanName + "'");
				}
			}
		}
	}

	/**
	 * Resolve the specified cached method argument or field value.
	 */
	private Object resolvedCachedArgument(String beanName, Object cachedArgument) {
		if (cachedArgument instanceof DependencyDescriptor) {
			DependencyDescriptor descriptor = (DependencyDescriptor) cachedArgument;
			TypeConverter typeConverter = this.beanFactory.getTypeConverter();
			return this.beanFactory.resolveDependency(descriptor, beanName, null, typeConverter);
		}
		else if (cachedArgument instanceof RuntimeBeanReference) {
			return this.beanFactory.getBean(((RuntimeBeanReference) cachedArgument).getBeanName());
		}
		else {
			return cachedArgument;
		}
	}


	/**
	 * Class representing injection information about an annotated field.
	 * 自动注入的字段元数据
	 */
	private class AutowiredFieldElement extends InjectionMetadata.InjectedElement {

		/**
		 * required属性值
		 */
		private final boolean required;

		/**
		 * 第一次注入完后，就会缓存起来
		 */
		private volatile boolean cached = false;

		private volatile Object cachedFieldValue;

		public AutowiredFieldElement(Field field, boolean required) {
			super(field, null);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, String beanName, PropertyValues pvs) throws Throwable {
			// 要被注入的字段
			Field field = (Field) this.member;
			try {
				Object value;
				// 有过缓存的，直接解析缓存的方法参数或者字段的值
				if (this.cached) {
					value = resolvedCachedArgument(beanName, this.cachedFieldValue);
				}
				else {
					// 创建一个依赖描述符对象，表示被注入的字段的封装
					DependencyDescriptor desc = new DependencyDescriptor(field, this.required);
					Set<String> autowiredBeanNames = new LinkedHashSet<String>(1);
					TypeConverter typeConverter = beanFactory.getTypeConverter();
					// 用来解析被注入的Bean对象，就是根据被注入的字段查找对应的Bean
					value = beanFactory.resolveDependency(desc, beanName, autowiredBeanNames, typeConverter);
					synchronized (this) {
						// 如果没有被缓存，需要根据条件进行缓存
						if (!this.cached) {
							// required需要为true
							if (value != null || this.required) {
								// 缓存字段值的描述符
								this.cachedFieldValue = desc;
								// 注册依赖的Bean，beanName代表注入的目标对象，autowiredBeanNames表示要被注入的依赖对象
								// 就是记录相互关系
								registerDependentBeans(beanName, autowiredBeanNames);
								if (autowiredBeanNames.size() == 1) {
									String autowiredBeanName = autowiredBeanNames.iterator().next();
									if (beanFactory.containsBean(autowiredBeanName)) {
										if (beanFactory.isTypeMatch(autowiredBeanName, field.getType())) {
											// 依赖关系完成后，将cachedFieldValue换成一个RuntimeBeanReference对象
											this.cachedFieldValue = new RuntimeBeanReference(autowiredBeanName);
										}
									}
								}
							}
							else {
								this.cachedFieldValue = null;
							}
							// 记录已经主如果，缓存
							this.cached = true;
						}
					}
				}
				// 通过反射注入
				// 上面记录完依赖和被依赖关系后，这里使用反射将value注入到bean的field中
				if (value != null) {
					ReflectionUtils.makeAccessible(field);
					field.set(bean, value);
				}
			}
			catch (Throwable ex) {
				throw new BeanCreationException("Could not autowire field: " + field, ex);
			}
		}
	}


	/**
	 * Class representing injection information about an annotated method.
	 * 方法的注入
	 */
	private class AutowiredMethodElement extends InjectionMetadata.InjectedElement {

		/**
		 * required值
		 */
		private final boolean required;

		private volatile boolean cached = false;

		private volatile Object[] cachedMethodArguments;

		public AutowiredMethodElement(Method method, boolean required, PropertyDescriptor pd) {
			super(method, pd);
			this.required = required;
		}

		@Override
		protected void inject(Object bean, String beanName, PropertyValues pvs) throws Throwable {
			if (checkPropertySkipping(pvs)) {
				return;
			}
			// 被注入的方法
			Method method = (Method) this.member;
			try {
				// 被注入方法的参数
				Object[] arguments;
				if (this.cached) {
					// Shortcut for avoiding synchronization...
					arguments = resolveCachedArguments(beanName);
				}
				else {
					// 被注入方法的参数
					Class<?>[] paramTypes = method.getParameterTypes();
					arguments = new Object[paramTypes.length];
					DependencyDescriptor[] descriptors = new DependencyDescriptor[paramTypes.length];
					Set<String> autowiredBeanNames = new LinkedHashSet<String>(paramTypes.length);
					TypeConverter typeConverter = beanFactory.getTypeConverter();
					for (int i = 0; i < arguments.length; i++) {
						// 方法的参数的表示形式
						MethodParameter methodParam = new MethodParameter(method, i);
						GenericTypeResolver.resolveParameterType(methodParam, bean.getClass());
						descriptors[i] = new DependencyDescriptor(methodParam, this.required);
						// 解析参数为容器中Bean对象
						arguments[i] = beanFactory.resolveDependency(
								descriptors[i], beanName, autowiredBeanNames, typeConverter);
						if (arguments[i] == null && !this.required) {
							arguments = null;
							break;
						}
					}
					synchronized (this) {
						if (!this.cached) {
							if (arguments != null) {
								this.cachedMethodArguments = new Object[arguments.length];
								for (int i = 0; i < arguments.length; i++) {
									this.cachedMethodArguments[i] = descriptors[i];
								}
								// 记录依赖和被依赖关系到容器中
								registerDependentBeans(beanName, autowiredBeanNames);
								if (autowiredBeanNames.size() == paramTypes.length) {
									Iterator<String> it = autowiredBeanNames.iterator();
									for (int i = 0; i < paramTypes.length; i++) {
										String autowiredBeanName = it.next();
										if (beanFactory.containsBean(autowiredBeanName)) {
											if (beanFactory.isTypeMatch(autowiredBeanName, paramTypes[i])) {
												this.cachedMethodArguments[i] = new RuntimeBeanReference(autowiredBeanName);
											}
										}
									}
								}
							}
							else {
								this.cachedMethodArguments = null;
							}
							this.cached = true;
						}
					}
				}
				// 反射调用方法
				// 上面在容器中记录完了依赖和被依赖的关系后，下面使用反射将arguments注入到bean的method方法中
				if (arguments != null) {
					ReflectionUtils.makeAccessible(method);
					method.invoke(bean, arguments);
				}
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
			catch (Throwable ex) {
				throw new BeanCreationException("Could not autowire method: " + method, ex);
			}
		}

		private Object[] resolveCachedArguments(String beanName) {
			if (this.cachedMethodArguments == null) {
				return null;
			}
			Object[] arguments = new Object[this.cachedMethodArguments.length];
			for (int i = 0; i < arguments.length; i++) {
				arguments[i] = resolvedCachedArgument(beanName, this.cachedMethodArguments[i]);
			}
			return arguments;
		}
	}

}
