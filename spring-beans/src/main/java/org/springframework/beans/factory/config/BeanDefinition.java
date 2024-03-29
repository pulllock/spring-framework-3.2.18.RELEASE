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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.core.AttributeAccessor;

/**
 * A BeanDefinition describes a bean instance, which has property values,
 * constructor argument values, and further information supplied by
 * concrete implementations.
 *
 * <p>This is just a minimal interface: The main intention is to allow a
 * {@link BeanFactoryPostProcessor} such as {@link PropertyPlaceholderConfigurer}
 * to introspect and modify property values and other bean metadata.
 *
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @since 19.03.2004
 * @see ConfigurableListableBeanFactory#getBeanDefinition
 * @see org.springframework.beans.factory.support.RootBeanDefinition
 * @see org.springframework.beans.factory.support.ChildBeanDefinition
 * BeanDefinition，Bean的定义，存储一个Bean最原始的数据。可理解为从XML（或其它）配置文件中
 * 读取到的元数据。
 */
public interface BeanDefinition extends AttributeAccessor, BeanMetadataElement {

	/**
	 * 默认只提供singleton和prototype两种
	 * 而request，session，globalSession，application，websocket等这几种
	 * 都是基于web的扩展
	 */

	/**
	 * Scope identifier for the standard singleton scope: "singleton".
	 * <p>Note that extended bean factories might support further scopes.
	 * @see #setScope
	 * 单例模式
	 */
	String SCOPE_SINGLETON = ConfigurableBeanFactory.SCOPE_SINGLETON;

	/**
	 * Scope identifier for the standard prototype scope: "prototype".
	 * <p>Note that extended bean factories might support further scopes.
	 * @see #setScope
	 * 原型模式
	 */
	String SCOPE_PROTOTYPE = ConfigurableBeanFactory.SCOPE_PROTOTYPE;


	/**
	 * Role hint indicating that a {@code BeanDefinition} is a major part
	 * of the application. Typically corresponds to a user-defined bean.
	 * 表示这个Bean是用户自定义的Bean
	 */
	int ROLE_APPLICATION = 0;

	/**
	 * Role hint indicating that a {@code BeanDefinition} is a supporting
	 * part of some larger configuration, typically an outer
	 * {@link org.springframework.beans.factory.parsing.ComponentDefinition}.
	 * {@code SUPPORT} beans are considered important enough to be aware
	 * of when looking more closely at a particular
	 * {@link org.springframework.beans.factory.parsing.ComponentDefinition},
	 * but not when looking at the overall configuration of an application.
	 * 表示这个Bean是某些复杂配置的支撑部分
	 */
	int ROLE_SUPPORT = 1;

	/**
	 * Role hint indicating that a {@code BeanDefinition} is providing an
	 * entirely background role and has no relevance to the end-user. This hint is
	 * used when registering beans that are completely part of the internal workings
	 * of a {@link org.springframework.beans.factory.parsing.ComponentDefinition}.
	 * 表示一个Bean是Spring内部的Bean
	 */
	int ROLE_INFRASTRUCTURE = 2;


	/**
	 * Return the name of the parent definition of this bean definition, if any.
	 */
	String getParentName();

	/**
	 * Set the name of the parent definition of this bean definition, if any.
	 * 设置父Bean
	 * 涉及到Bean的继承，继承父Bean的配置信息
	 * 对应xml中的<bean parent=""></bean>
	 */
	void setParentName(String parentName);

	/**
	 * Return the current bean class name of this bean definition.
	 * <p>Note that this does not have to be the actual class name used at runtime, in
	 * case of a child definition overriding/inheriting the class name from its parent.
	 * Hence, do <i>not</i> consider this to be the definitive bean type at runtime but
	 * rather only use it for parsing purposes at the individual bean definition level.
	 */
	String getBeanClassName();

	/**
	 * Override the bean class name of this bean definition.
	 * <p>The class name can be modified during bean factory post-processing,
	 * typically replacing the original class name with a parsed variant of it.
	 * 设置Bean的类名称，将来要通过反射来生成实例
	 * 对应xml中的<bean class=""></bean>
	 */
	void setBeanClassName(String beanClassName);

	/**
	 * Return the factory bean name, if any.
	 */
	String getFactoryBeanName();

	/**
	 * Specify the factory bean to use, if any.
	 * 如果该Bean采用工厂方法生成，指定工厂名称。
	 * 有些实例不是用反射生成的，而是用工厂模式生成的、
	 * 对应xml中的<bean factory-bean=""></bean>
	 */
	void setFactoryBeanName(String factoryBeanName);

	/**
	 * Return a factory method, if any.
	 */
	String getFactoryMethodName();

	/**
	 * Specify a factory method, if any. This method will be invoked with
	 * constructor arguments, or with no arguments if none are specified.
	 * The method will be invoked on the specified factory bean, if any,
	 * or otherwise as a static method on the local bean class.
	 * @param factoryMethodName static factory method name,
	 * or {@code null} if normal constructor creation should be used
	 * @see #getBeanClassName()
	 * 指定工厂类中年的 工厂方法名称
	 * 对应xml中的<bean factory-method=""></bean>
	 */
	void setFactoryMethodName(String factoryMethodName);

	/**
	 * Return the name of the current target scope for this bean,
	 * or {@code null} if not known yet.
	 */
	String getScope();

	/**
	 * Override the target scope of this bean, specifying a new scope name.
	 * @see #SCOPE_SINGLETON
	 * @see #SCOPE_PROTOTYPE
	 */
	void setScope(String scope);

	/**
	 * Return whether this bean should be lazily initialized, i.e. not
	 * eagerly instantiated on startup. Only applicable to a singleton bean.
	 */
	boolean isLazyInit();

	/**
	 * Set whether this bean should be lazily initialized.
	 * <p>If {@code false}, the bean will get instantiated on startup by bean
	 * factories that perform eager initialization of singletons.
	 */
	void setLazyInit(boolean lazyInit);

	/**
	 * Return the bean names that this bean depends on.
	 */
	String[] getDependsOn();

	/**
	 * Set the names of the beans that this bean depends on being initialized.
	 * The bean factory will guarantee that these beans get initialized first.
	 * 设置该Bean依赖的所有的Bean
	 * 这里的依赖不是指属性依赖如@Autowire标记的
	 * 而是depends-on=""属性设置的值
	 */
	void setDependsOn(String[] dependsOn);

	/**
	 * Return whether this bean is a candidate for getting autowired into some other bean.
	 */
	boolean isAutowireCandidate();

	/**
	 * Set whether this bean is a candidate for getting autowired into some other bean.
	 * 设置该Bean是否可以注入到其他Bean中，只对根据类型注入有效
	 *
	 * 如果根据名称注入，这边设置了false，也是可以注入的
	 */
	void setAutowireCandidate(boolean autowireCandidate);

	/**
	 * Return whether this bean is a primary autowire candidate.
	 * If this value is true for exactly one bean among multiple
	 * matching candidates, it will serve as a tie-breaker.
	 */
	boolean isPrimary();

	/**
	 * Set whether this bean is a primary autowire candidate.
	 * <p>If this value is true for exactly one bean among multiple
	 * matching candidates, it will serve as a tie-breaker.
	 * 同一接口的多个不同实现，如果不指定名字的话，Spring会优先选择设置primary为true的Bean
	 */
	void setPrimary(boolean primary);


	/**
	 * Return the constructor argument values for this bean.
	 * <p>The returned instance can be modified during bean factory post-processing.
	 * @return the ConstructorArgumentValues object (never {@code null})
	 * 构造器参数
	 */
	ConstructorArgumentValues getConstructorArgumentValues();

	/**
	 * Return the property values to be applied to a new instance of the bean.
	 * <p>The returned instance can be modified during bean factory post-processing.
	 * @return the MutablePropertyValues object (never {@code null})
	 * Bean中的属性值
	 */
	MutablePropertyValues getPropertyValues();


	/**
	 * Return whether this a <b>Singleton</b>, with a single, shared instance
	 * returned on all calls.
	 * @see #SCOPE_SINGLETON
	 */
	boolean isSingleton();

	/**
	 * Return whether this a <b>Prototype</b>, with an independent instance
	 * returned for each call.
	 * @see #SCOPE_PROTOTYPE
	 */
	boolean isPrototype();

	/**
	 * Return whether this bean is "abstract", that is, not meant to be instantiated.
	 * 如果Bean被设置成abstract，就不能实例化
	 * 常用于作为父Bean 用于继承
	 */
	boolean isAbstract();

	/**
	 * Get the role hint for this {@code BeanDefinition}. The role hint
	 * provides tools with an indication of the importance of a particular
	 * {@code BeanDefinition}.
	 * @see #ROLE_APPLICATION
	 * @see #ROLE_INFRASTRUCTURE
	 * @see #ROLE_SUPPORT
	 */
	int getRole();

	/**
	 * Return a human-readable description of this bean definition.
	 */
	String getDescription();

	/**
	 * Return a description of the resource that this bean definition
	 * came from (for the purpose of showing context in case of errors).
	 */
	String getResourceDescription();

	/**
	 * Return the originating BeanDefinition, or {@code null} if none.
	 * Allows for retrieving the decorated bean definition, if any.
	 * <p>Note that this method returns the immediate originator. Iterate through the
	 * originator chain to find the original BeanDefinition as defined by the user.
	 */
	BeanDefinition getOriginatingBeanDefinition();

}
