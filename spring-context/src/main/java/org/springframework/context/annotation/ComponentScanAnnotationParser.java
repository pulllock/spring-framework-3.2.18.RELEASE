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

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Parser for the @{@link ComponentScan} annotation.
 *
 * @author Chris Beams
 * @since 3.1
 * @see ClassPathBeanDefinitionScanner#scan(String...)
 * @see ComponentScanBeanDefinitionParser
 */
class ComponentScanAnnotationParser {

	private final ResourceLoader resourceLoader;

	private final Environment environment;

	private final BeanDefinitionRegistry registry;

	private final BeanNameGenerator beanNameGenerator;


	public ComponentScanAnnotationParser(ResourceLoader resourceLoader, Environment environment,
			BeanNameGenerator beanNameGenerator, BeanDefinitionRegistry registry) {

		this.resourceLoader = resourceLoader;
		this.environment = environment;
		this.beanNameGenerator = beanNameGenerator;
		this.registry = registry;
	}


	/**
	 * 解析@ComponentScan指定的包下面的所有的组件，解析成BeanDefinition
	 * @param componentScan
	 * @param declaringClass
	 * @return
	 */
	public Set<BeanDefinitionHolder> parse(AnnotationAttributes componentScan, final String declaringClass) {

		/**
		 * 和ComponentBeanDefinitionParser一样使用ClassPathBeanDefinitionScanner来扫描指定的包下面的类
		 * 往下面的步骤都是根据注解中配置的信息来设置scanner，最后进行解析，步骤都和ComponentBeanDefinitionParser一样
		 * ClassPathBeanDefinitionScanner用来在classpath上扫描组件并解析成BeanDefinition注册到容器中，
		 * 扫描的注解包括：@Component、@Repository、@Controller、@ManagedBean、@Named，或者使用自定义的type filters。
		 */
		ClassPathBeanDefinitionScanner scanner =
				new ClassPathBeanDefinitionScanner(this.registry, componentScan.getBoolean("useDefaultFilters"));

		Assert.notNull(this.environment, "Environment must not be null");
		scanner.setEnvironment(this.environment);

		Assert.notNull(this.resourceLoader, "ResourceLoader must not be null");
		scanner.setResourceLoader(this.resourceLoader);

		Class<? extends BeanNameGenerator> generatorClass = componentScan.getClass("nameGenerator");
		boolean useInheritedGenerator = BeanNameGenerator.class.equals(generatorClass);
		scanner.setBeanNameGenerator(useInheritedGenerator ? this.beanNameGenerator :
				BeanUtils.instantiateClass(generatorClass));

		ScopedProxyMode scopedProxyMode = componentScan.getEnum("scopedProxy");
		if (scopedProxyMode != ScopedProxyMode.DEFAULT) {
			scanner.setScopedProxyMode(scopedProxyMode);
		}
		else {
			Class<? extends ScopeMetadataResolver> resolverClass = componentScan.getClass("scopeResolver");
			scanner.setScopeMetadataResolver(BeanUtils.instantiateClass(resolverClass));
		}

		scanner.setResourcePattern(componentScan.getString("resourcePattern"));

		/**
		 * 要扫描的过滤器
		 */
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("includeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addIncludeFilter(typeFilter);
			}
		}

		/**
		 * 排除的过滤器
		 */
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("excludeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addExcludeFilter(typeFilter);
			}
		}

		List<String> basePackages = new ArrayList<String>();
		for (String pkg : componentScan.getStringArray("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : componentScan.getStringArray("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : componentScan.getClassArray("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(declaringClass));
		}

		scanner.addExcludeFilter(new AbstractTypeHierarchyTraversingFilter(false, false) {
			@Override
			protected boolean matchClassName(String className) {
				return declaringClass.equals(className);
			}
		});

		// 使用ClassPathBeanDefinitionScanner扫描basePackages下面的组件
		return scanner.doScan(StringUtils.toStringArray(basePackages));
	}

	private List<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {
		List<TypeFilter> typeFilters = new ArrayList<TypeFilter>();
		FilterType filterType = filterAttributes.getEnum("type");

		for (Class<?> filterClass : filterAttributes.getClassArray("value")) {
			switch (filterType) {
				case ANNOTATION:
					Assert.isAssignable(Annotation.class, filterClass,
							"An error occured when processing a @ComponentScan " +
							"ANNOTATION type filter: ");
					@SuppressWarnings("unchecked")
					Class<Annotation> annoClass = (Class<Annotation>)filterClass;
					typeFilters.add(new AnnotationTypeFilter(annoClass));
					break;
				case ASSIGNABLE_TYPE:
					typeFilters.add(new AssignableTypeFilter(filterClass));
					break;
				case CUSTOM:
					Assert.isAssignable(TypeFilter.class, filterClass,
							"An error occured when processing a @ComponentScan " +
							"CUSTOM type filter: ");
					typeFilters.add(BeanUtils.instantiateClass(filterClass, TypeFilter.class));
					break;
				default:
					throw new IllegalArgumentException("unknown filter type " + filterType);
			}
		}
		return typeFilters;
	}

}
