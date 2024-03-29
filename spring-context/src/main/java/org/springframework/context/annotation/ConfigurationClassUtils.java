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

package org.springframework.context.annotation;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.core.Conventions;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;

/**
 * Utilities for processing @{@link Configuration} classes.
 *
 * @author Chris Beams
 * @since 3.1
 */
abstract class ConfigurationClassUtils {

	private static final String CONFIGURATION_CLASS_FULL = "full";

	private static final String CONFIGURATION_CLASS_LITE = "lite";

	private static final String CONFIGURATION_CLASS_ATTRIBUTE =
			Conventions.getQualifiedAttributeName(ConfigurationClassPostProcessor.class, "configurationClass");

	private static final Log logger = LogFactory.getLog(ConfigurationClassUtils.class);


	/**
	 * Check whether the given bean definition is a candidate for a configuration class,
	 * and mark it accordingly.
	 * @param beanDef the bean definition to check
	 * @param metadataReaderFactory the current factory in use by the caller
	 * @return whether the candidate qualifies as (any kind of) configuration class
	 * 检查一个Bean定义是不是配置类
	 * 被@Configuration、@Bean、@Component注解的类
	 *
	 * 从Bean定义的类的元数据中获取注解元数据，检查是否有相关注解，如果有的话，设置这个Bean定义的属性，
	 * 标记这个Bean定义是个配置类
	 */
	public static boolean checkConfigurationClassCandidate(BeanDefinition beanDef, MetadataReaderFactory metadataReaderFactory) {
		AnnotationMetadata metadata = null;

		// Check already loaded Class if present...
		// since we possibly can't even load the class file for this Class.
		if (beanDef instanceof AbstractBeanDefinition && ((AbstractBeanDefinition) beanDef).hasBeanClass()) {
			Class<?> beanClass = ((AbstractBeanDefinition) beanDef).getBeanClass();
			metadata = new StandardAnnotationMetadata(beanClass, true);
		}
		else {
			// Bean定义的class名称
			String className = beanDef.getBeanClassName();
			if (className != null) {
				try {
					// 获取这个Bean对应的元数据读取器，可以获得这个Bean的元数据
					MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(className);
					// 从元数据中获取注解元数据
					metadata = metadataReader.getAnnotationMetadata();
				}
				catch (IOException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Could not find class file for introspecting factory methods: " + className, ex);
					}
					return false;
				}
			}
		}

		if (metadata != null) {
		    // 标注了@Configuration注解，是一个full模式的配置类
			if (isFullConfigurationCandidate(metadata)) {
				// 如果是@Configuration注解的类，设置属性org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass : full
				beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);
				return true;
			}
			// 标注了@Component注解的类或者一个类中有@Bean注解的方法，这个类是一个lite模式的配置类
			else if (isLiteConfigurationCandidate(metadata)) {
				// 设置属性：org.springframework.context.annotation.ConfigurationClassPostProcessor.configurationClass : lite
				beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);
				return true;
			}
		}
		return false;
	}

	/**
	 * Check the given metadata for a configuration class candidate
	 * (or nested component class declared within a configuration/component class).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be registered as a
	 * reflection-detected bean definition; {@code false} otherwise
	 */
	public static boolean isConfigurationCandidate(AnnotationMetadata metadata) {
		return (isFullConfigurationCandidate(metadata) || isLiteConfigurationCandidate(metadata));
	}

	/**
	 * Check the given metadata for a full configuration class candidate
	 * (i.e. a class annotated with {@code @Configuration}).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be processed as a full
	 * configuration class, including cross-method call interception
	 */
	public static boolean isFullConfigurationCandidate(AnnotationMetadata metadata) {
		return metadata.isAnnotated(Configuration.class.getName());
	}

	/**
	 * Check the given metadata for a lite configuration class candidate
	 * (e.g. a class annotated with {@code @Component} or just having
	 * {@code @Bean methods}).
	 * @param metadata the metadata of the annotated class
	 * @return {@code true} if the given class is to be processed as a lite
	 * configuration class, just registering it and scanning it for {@code @Bean} methods
	 */
	public static boolean isLiteConfigurationCandidate(AnnotationMetadata metadata) {
		// Do not consider an interface or an annotation...
		return (!metadata.isInterface() && (
				metadata.isAnnotated(Component.class.getName()) || metadata.hasAnnotatedMethods(Bean.class.getName())));
	}

	/**
	 * Determine whether the given bean definition indicates a full
	 * {@code @Configuration} class.
	 */
	public static boolean isFullConfigurationClass(BeanDefinition beanDef) {
		return CONFIGURATION_CLASS_FULL.equals(beanDef.getAttribute(CONFIGURATION_CLASS_ATTRIBUTE));
	}

}
