/*
 * Copyright 2002-2012 the original author or authors.
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

package org.springframework.aop.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.Ordered;
import org.springframework.util.Assert;

/**
 * Utility class for handling registration of AOP auto-proxy creators.
 *
 * <p>Only a single auto-proxy creator can be registered yet multiple concrete
 * implementations are available. Therefore this class wraps a simple escalation
 * protocol, allowing classes to request a particular auto-proxy creator and know
 * that class, {@code or a subclass thereof}, will eventually be resident
 * in the application context.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @since 2.5
 * @see AopNamespaceUtils
 */
public abstract class AopConfigUtils {

	/**
	 * The bean name of the internally managed auto-proxy creator.
	 */
	public static final String AUTO_PROXY_CREATOR_BEAN_NAME =
			"org.springframework.aop.config.internalAutoProxyCreator";

	/**
	 * Stores the auto proxy creator classes in escalation order.
	 */
	private static final List<Class> APC_PRIORITY_LIST = new ArrayList<Class>();

	/**
	 * Setup the escalation list.
	 */
	static {
		APC_PRIORITY_LIST.add(InfrastructureAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AspectJAwareAdvisorAutoProxyCreator.class);
		APC_PRIORITY_LIST.add(AnnotationAwareAspectJAutoProxyCreator.class);
	}


	public static BeanDefinition registerAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAutoProxyCreatorIfNecessary(registry, null);
	}

	public static BeanDefinition registerAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry, Object source) {
		return registerOrEscalateApcAsRequired(InfrastructureAdvisorAutoProxyCreator.class, registry, source);
	}

	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAutoProxyCreatorIfNecessary(registry, null);
	}

	public static BeanDefinition registerAspectJAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry, Object source) {
		return registerOrEscalateApcAsRequired(AspectJAwareAdvisorAutoProxyCreator.class, registry, source);
	}

	/**
	 * 注册一个注解的代理创建器
	 * @param registry
	 * @return
	 */
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry) {
		return registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry, null);
	}

	/**
	 *  注册或升级自动代理创建器，定义beanName为internalAutoProxyCreator的BeanDefinition
	 * @param registry
	 * @param source
	 * @return
	 */
	public static BeanDefinition registerAspectJAnnotationAutoProxyCreatorIfNecessary(BeanDefinitionRegistry registry, Object source) {
		// 注册或升级自动代理创建器
		return registerOrEscalateApcAsRequired(AnnotationAwareAspectJAutoProxyCreator.class, registry, source);
	}

	public static void forceAutoProxyCreatorToUseClassProxying(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			// 设置proxyTargetClass属性为true
			definition.getPropertyValues().add("proxyTargetClass", Boolean.TRUE);
		}
	}

	static void forceAutoProxyCreatorToExposeProxy(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			BeanDefinition definition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			// 设置exposeProxy属性为true
			definition.getPropertyValues().add("exposeProxy", Boolean.TRUE);
		}
	}


	/**
	 * 注册或升级AutoProxyCreator，定义beanName为internalAutoProxyCreator的BeanDefinition
	 * @param cls AnnotationAwareAspectJAutoProxyCreator
	 * @param registry
	 * @param source
	 * @return
	 */
	private static BeanDefinition registerOrEscalateApcAsRequired(Class cls, BeanDefinitionRegistry registry, Object source) {
		Assert.notNull(registry, "BeanDefinitionRegistry must not be null");
		// 如果已经包含了internalAutoProxyCreator
		if (registry.containsBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME)) {
			// 获取已经注册的internalAutoProxyCreator
			BeanDefinition apcDefinition = registry.getBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME);
			// 已经注册的internalAutoProxyCreator与我们现在要注册的不一致，需要根据优先级来判断 到底使用哪个
			if (!cls.getName().equals(apcDefinition.getBeanClassName())) {
				/**
				 * 这个list中按照顺序添加了一下三个自动创建器，我们要指定的也必须是这三个中的一个
				 * APC_PRIORITY_LIST.add(InfrastructureAdvisorAutoProxyCreator.class);
				 * APC_PRIORITY_LIST.add(AspectJAwareAdvisorAutoProxyCreator.class);
				 * APC_PRIORITY_LIST.add(AnnotationAwareAspectJAutoProxyCreator.class);
				 *
				 * AnnotationAwareAspectJAutoProxyCreator的优先级最高，我们的项目中可能会存在注解的形式和xml配置共存，
				 * AnnotationAwareAspectJAutoProxyCreator的逻辑中包含了对xml的处理，所以如果存在多个的话
				 * AnnotationAwareAspectJAutoProxyCreator的优先级要变成最高的。
				 *
				 * 找到已经注册的internalAutoProxyCreator的优先级
				 */
				int currentPriority = findPriorityForClass(apcDefinition.getBeanClassName());
				// 找到我们现在的要注册优先级
				int requiredPriority = findPriorityForClass(cls);
				// 已经注册的没有我们现在要注册的优先级大，使用我们的现在要注册的
				if (currentPriority < requiredPriority) {
					apcDefinition.setBeanClassName(cls.getName());
				}
			}
			// 已经存在internalAutoProxyCreator，并且与我们现在要创建的一致，就不需要再创建
			return null;
		}
		// 如果不存在已有的创建器，需要注册一个新的自动代理创建器并注册到容器中
		RootBeanDefinition beanDefinition = new RootBeanDefinition(cls);
		beanDefinition.setSource(source);
		beanDefinition.getPropertyValues().add("order", Ordered.HIGHEST_PRECEDENCE);
		// 角色是基础设施，容器内部使用
		beanDefinition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		// BeanDefinition的名字是org.springframework.aop.config.internalAutoProxyCreator
		registry.registerBeanDefinition(AUTO_PROXY_CREATOR_BEAN_NAME, beanDefinition);
		return beanDefinition;
	}

	private static int findPriorityForClass(Class clazz) {
		return APC_PRIORITY_LIST.indexOf(clazz);
	}

	private static int findPriorityForClass(String className) {
		for (int i = 0; i < APC_PRIORITY_LIST.size(); i++) {
			Class clazz = APC_PRIORITY_LIST.get(i);
			if (clazz.getName().equals(className)) {
				return i;
			}
		}
		throw new IllegalArgumentException(
				"Class name [" + className + "] is not a known auto-proxy creator class");
	}

}
