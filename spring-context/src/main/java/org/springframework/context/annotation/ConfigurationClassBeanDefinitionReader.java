/*
 * Copyright 2002-2013 the original author or authors.
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

package org.springframework.context.annotation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.annotation.Autowire;
import org.springframework.beans.factory.annotation.RequiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.StringUtils;

/**
 * Reads a given fully-populated set of ConfigurationClass instances, registering bean
 * definitions with the given {@link BeanDefinitionRegistry} based on its contents.
 *
 * <p>This class was modeled after the {@link BeanDefinitionReader} hierarchy, but does
 * not implement/extend any of its artifacts as a set of configuration classes is not a
 * {@link Resource}.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @since 3.0
 * @see ConfigurationClassParser
 */
class ConfigurationClassBeanDefinitionReader {

	private static final Log logger = LogFactory.getLog(ConfigurationClassBeanDefinitionReader.class);

	private final BeanDefinitionRegistry registry;

	private final SourceExtractor sourceExtractor;

	private final ProblemReporter problemReporter;

	private final MetadataReaderFactory metadataReaderFactory;

	private final ResourceLoader resourceLoader;

	private final Environment environment;

	private final BeanNameGenerator importBeanNameGenerator;


	/**
	 * Create a new {@link ConfigurationClassBeanDefinitionReader} instance that will be used
	 * to populate the given {@link BeanDefinitionRegistry}.
	 */
	public ConfigurationClassBeanDefinitionReader(
			BeanDefinitionRegistry registry, SourceExtractor sourceExtractor,
			ProblemReporter problemReporter, MetadataReaderFactory metadataReaderFactory,
			ResourceLoader resourceLoader, Environment environment, BeanNameGenerator importBeanNameGenerator) {

		this.registry = registry;
		this.sourceExtractor = sourceExtractor;
		this.problemReporter = problemReporter;
		this.metadataReaderFactory = metadataReaderFactory;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
		this.importBeanNameGenerator = importBeanNameGenerator;
	}


	/**
	 * Read {@code configurationModel}, registering bean definitions with {@link #registry}
	 * based on its contents.
	 */
	public void loadBeanDefinitions(Set<ConfigurationClass> configurationModel) {
		// 遍历扫描到的配置类，挨个加载为BeanDefinition
		for (ConfigurationClass configClass : configurationModel) {
			loadBeanDefinitionsForConfigurationClass(configClass);
		}
	}

	/**
	 * Read a particular {@link ConfigurationClass}, registering bean definitions for the
	 * class itself, all its {@link Bean} methods
	 */
	private void loadBeanDefinitionsForConfigurationClass(ConfigurationClass configClass) {
		// 被@Import注解的，也是就是要被导入到其他类中的类
		if (configClass.isImported()) {
			// 注册成BeanDefinition，这里被导入的类不包括ImportSelector和ImportBeanDefinitionRegistrar实现类，只是@Import导入的普通配置类
			registerBeanDefinitionForImportedConfigurationClass(configClass);
		}
		// 注册被@Bean注解的方法的bean
		for (BeanMethod beanMethod : configClass.getBeanMethods()) {
			loadBeanDefinitionsForBeanMethod(beanMethod);
		}
		// 注册被@ImportResource注解的资源文件中的bean
		loadBeanDefinitionsFromImportedResources(configClass.getImportedResources());
	}

	/**
	 * Register the {@link Configuration} class itself as a bean definition.
	 * 将被导入的配置类解析为BeanDefinition添加到容器中
	 */
	private void registerBeanDefinitionForImportedConfigurationClass(ConfigurationClass configClass) {
		// 获取类上的注解元数据
		AnnotationMetadata metadata = configClass.getMetadata();
		// 封装成AnnotatedGenericBeanDefinition
		BeanDefinition configBeanDef = new AnnotatedGenericBeanDefinition(metadata);
		// 判断是否是配置类
		if (ConfigurationClassUtils.checkConfigurationClassCandidate(configBeanDef, this.metadataReaderFactory)) {
			// 获取配置类名
			String configBeanName = this.importBeanNameGenerator.generateBeanName(configBeanDef, this.registry);
			// 注册成BeanDefinition到容器中去
			this.registry.registerBeanDefinition(configBeanName, configBeanDef);
			configClass.setBeanName(configBeanName);
			if (logger.isDebugEnabled()) {
				logger.debug(String.format("Registered bean definition for imported @Configuration class %s", configBeanName));
			}
		}
		else {
			this.problemReporter.error(
					new InvalidConfigurationImportProblem(metadata.getClassName(), configClass.getResource(), metadata));
		}
	}

	/**
	 * Read the given {@link BeanMethod}, registering bean definitions
	 * with the BeanDefinitionRegistry based on its contents.
	 * 将注解了@Bean的方法解析成BeanDefinition注册到容器中
	 */
	private void loadBeanDefinitionsForBeanMethod(BeanMethod beanMethod) {
		// method所在的类
		ConfigurationClass configClass = beanMethod.getConfigurationClass();
		// method元数据
		MethodMetadata metadata = beanMethod.getMetadata();

		// 封装成ConfigurationClassBeanDefinition
		ConfigurationClassBeanDefinition beanDef = new ConfigurationClassBeanDefinition(configClass);
		beanDef.setResource(configClass.getResource());
		beanDef.setSource(this.sourceExtractor.extractSource(metadata, configClass.getResource()));

		// 静态@Bean方法
		if (metadata.isStatic()) {
			// static @Bean method
			beanDef.setBeanClassName(configClass.getMetadata().getClassName());
			beanDef.setFactoryMethodName(metadata.getMethodName());
		}
		else { // 实例Bean方法
			// instance @Bean method
			beanDef.setFactoryBeanName(configClass.getBeanName());
			beanDef.setUniqueFactoryMethodName(metadata.getMethodName());
		}
		beanDef.setAutowireMode(RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
		beanDef.setAttribute(RequiredAnnotationBeanPostProcessor.SKIP_REQUIRED_CHECK_ATTRIBUTE, Boolean.TRUE);

		// consider role
		// 看有没有@Role注解
		AnnotationAttributes role = MetadataUtils.attributesFor(metadata, Role.class);
		if (role != null) {
			beanDef.setRole(role.<Integer>getNumber("value"));
		}

		// consider name and any aliases
		// 获取@Bean注解的name属性，作为别名
		AnnotationAttributes bean = MetadataUtils.attributesFor(metadata, Bean.class);
		List<String> names = new ArrayList<String>(Arrays.asList(bean.getStringArray("name")));
		String beanName = (names.size() > 0 ? names.remove(0) : beanMethod.getMetadata().getMethodName());
		for (String alias : names) {
			this.registry.registerAlias(beanName, alias);
		}

		// has this already been overridden (e.g. via XML)?
		// 看下这个BeanDefinition是不是已经被加载过
		if (this.registry.containsBeanDefinition(beanName)) {
			BeanDefinition existingBeanDef = this.registry.getBeanDefinition(beanName);
			// Is the existing bean definition one that was created from a configuration class?
			// -> allow the current bean method to override, since both are at second-pass level.
			// However, if the bean method is an overloaded case on the same configuration class,
			// preserve the existing bean definition.
			// 如果也是通过配置类中加载的BeanDefinition
			if (existingBeanDef instanceof ConfigurationClassBeanDefinition) {
				ConfigurationClassBeanDefinition ccbd = (ConfigurationClassBeanDefinition) existingBeanDef;
				// 同一个类中的相同名字的@Bean，不覆盖
				if (ccbd.getMetadata().getClassName().equals(beanMethod.getConfigurationClass().getMetadata().getClassName())) {
					return;
				}
			}
			else {
				// 如果是xml中已经有的BeanDefinition，不覆盖
				// no -> then it's an external override, probably XML
				// overriding is legal, return immediately
				if (logger.isDebugEnabled()) {
					logger.debug(String.format("Skipping loading bean definition for %s: a definition for bean " +
							"'%s' already exists. This is likely due to an override in XML.", beanMethod, beanName));
				}
				return;
			}
		}

		// @Primary注解
		if (metadata.isAnnotated(Primary.class.getName())) {
			beanDef.setPrimary(true);
		}

		// is this bean to be instantiated lazily?
		// @Lazy注解
		if (metadata.isAnnotated(Lazy.class.getName())) {
			AnnotationAttributes lazy = MetadataUtils.attributesFor(metadata, Lazy.class);
			beanDef.setLazyInit(lazy.getBoolean("value"));
		}
		else if (configClass.getMetadata().isAnnotated(Lazy.class.getName())){
			AnnotationAttributes lazy = MetadataUtils.attributesFor(configClass.getMetadata(), Lazy.class);
			beanDef.setLazyInit(lazy.getBoolean("value"));
		}

		// @DependsOn注解
		if (metadata.isAnnotated(DependsOn.class.getName())) {
			AnnotationAttributes dependsOn = MetadataUtils.attributesFor(metadata, DependsOn.class);
			String[] otherBeans = dependsOn.getStringArray("value");
			if (otherBeans.length > 0) {
				beanDef.setDependsOn(otherBeans);
			}
		}

		// @Bean的autowire属性
		Autowire autowire = bean.getEnum("autowire");
		if (autowire.isAutowire()) {
			beanDef.setAutowireMode(autowire.value());
		}

		// @Bean的initMethod属性
		String initMethodName = bean.getString("initMethod");
		if (StringUtils.hasText(initMethodName)) {
			beanDef.setInitMethodName(initMethodName);
		}

		// @Bean的destroyMethod属性
		String destroyMethodName = bean.getString("destroyMethod");
		if (StringUtils.hasText(destroyMethodName)) {
			beanDef.setDestroyMethodName(destroyMethodName);
		}

		// Consider scoping
		// @Scope注解
		ScopedProxyMode proxyMode = ScopedProxyMode.NO;
		AnnotationAttributes scope = MetadataUtils.attributesFor(metadata, Scope.class);
		if (scope != null) {
			beanDef.setScope(scope.getString("value"));
			proxyMode = scope.getEnum("proxyMode");
			if (proxyMode == ScopedProxyMode.DEFAULT) {
				proxyMode = ScopedProxyMode.NO;
			}
		}

		// Replace the original bean definition with the target one, if necessary
		// 这里可以根据配置，替换掉原始的BeanDefinition
		BeanDefinition beanDefToRegister = beanDef;
		if (proxyMode != ScopedProxyMode.NO) {
			BeanDefinitionHolder proxyDef = ScopedProxyCreator.createScopedProxy(
					new BeanDefinitionHolder(beanDef, beanName), this.registry, proxyMode == ScopedProxyMode.TARGET_CLASS);
			beanDefToRegister =
					new ConfigurationClassBeanDefinition((RootBeanDefinition) proxyDef.getBeanDefinition(), configClass);
		}

		if (logger.isDebugEnabled()) {
			logger.debug(String.format("Registering bean definition for @Bean method %s.%s()",
					configClass.getMetadata().getClassName(), beanName));
		}

		// 注册BeanDefinition到容器中
		this.registry.registerBeanDefinition(beanName, beanDefToRegister);
	}


	private void loadBeanDefinitionsFromImportedResources(
			Map<String, Class<? extends BeanDefinitionReader>> importedResources) {

		// 遍历要读取的xml资源文件
		Map<Class<?>, BeanDefinitionReader> readerInstanceCache = new HashMap<Class<?>, BeanDefinitionReader>();
		for (Map.Entry<String, Class<? extends BeanDefinitionReader>> entry : importedResources.entrySet()) {
			String resource = entry.getKey();
			Class<? extends BeanDefinitionReader> readerClass = entry.getValue();
			if (!readerInstanceCache.containsKey(readerClass)) {
				try {
					// Instantiate the specified BeanDefinitionReader
					BeanDefinitionReader readerInstance =
							readerClass.getConstructor(BeanDefinitionRegistry.class).newInstance(this.registry);
					// Delegate the current ResourceLoader to it if possible
					if (readerInstance instanceof AbstractBeanDefinitionReader) {
						AbstractBeanDefinitionReader abdr = ((AbstractBeanDefinitionReader) readerInstance);
						abdr.setResourceLoader(this.resourceLoader);
						abdr.setEnvironment(this.environment);
					}
					readerInstanceCache.put(readerClass, readerInstance);
				}
				catch (Exception ex) {
					throw new IllegalStateException(
							"Could not instantiate BeanDefinitionReader class [" + readerClass.getName() + "]");
				}
			}
			BeanDefinitionReader reader = readerInstanceCache.get(readerClass);
			// TODO SPR-6310: qualify relative path locations as done in AbstractContextLoader.modifyLocations
			// 使用BeanDefinitionReader从配置文件中加载BeanDefinition
			// 跟容器启动的时候加载xml文件一样
			reader.loadBeanDefinitions(resource);
		}
	}


	/**
	 * {@link RootBeanDefinition} marker subclass used to signify that a bean definition
	 * was created from a configuration class as opposed to any other configuration source.
	 * Used in bean overriding cases where it's necessary to determine whether the bean
	 * definition was created externally.
	 */
	@SuppressWarnings("serial")
	private static class ConfigurationClassBeanDefinition extends RootBeanDefinition implements AnnotatedBeanDefinition {

		private final AnnotationMetadata annotationMetadata;

		public ConfigurationClassBeanDefinition(ConfigurationClass configClass) {
			this.annotationMetadata = configClass.getMetadata();
			setLenientConstructorResolution(false);
		}

		public ConfigurationClassBeanDefinition(RootBeanDefinition original, ConfigurationClass configClass) {
			super(original);
			this.annotationMetadata = configClass.getMetadata();
		}

		private ConfigurationClassBeanDefinition(ConfigurationClassBeanDefinition original) {
			super(original);
			this.annotationMetadata = original.annotationMetadata;
		}

		public AnnotationMetadata getMetadata() {
			return this.annotationMetadata;
		}

		@Override
		public boolean isFactoryMethod(Method candidate) {
			return (super.isFactoryMethod(candidate) && BeanAnnotationHelper.isBeanAnnotated(candidate));
		}

		@Override
		public ConfigurationClassBeanDefinition cloneBeanDefinition() {
			return new ConfigurationClassBeanDefinition(this);
		}
	}


	/**
	 * Configuration classes must be annotated with {@link Configuration @Configuration} or
	 * declare at least one {@link Bean @Bean} method.
	 */
	private static class InvalidConfigurationImportProblem extends Problem {

		public InvalidConfigurationImportProblem(String className, Resource resource, AnnotationMetadata metadata) {
			super(String.format("%s was @Import'ed but is not annotated with @Configuration " +
					"nor does it declare any @Bean methods; it does not implement ImportSelector " +
					"or extend ImportBeanDefinitionRegistrar. Update the class to meet one of these requirements " +
					"or do not attempt to @Import it.", className), new Location(resource, metadata));
		}
	}

}
