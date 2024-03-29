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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;

/**
 * Configuration interface to be implemented by most listable bean factories.
 * In addition to {@link ConfigurableBeanFactory}, it provides facilities to
 * analyze and modify bean definitions, and to pre-instantiate singletons.
 *
 * <p>This subinterface of {@link org.springframework.beans.factory.BeanFactory}
 * is not meant to be used in normal application code: Stick to
 * {@link org.springframework.beans.factory.BeanFactory} or
 * {@link org.springframework.beans.factory.ListableBeanFactory} for typical
 * use cases. This interface is just meant to allow for framework-internal
 * plug'n'play even when needing access to bean factory configuration methods.
 *
 * @author Juergen Hoeller
 * @since 03.11.2003
 * @see org.springframework.context.support.AbstractApplicationContext#getBeanFactory()
 * 提供解析，修改bean定义，还可以预实例化单例
 */
public interface ConfigurableListableBeanFactory
		extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory {

	/**
	 * Ignore the given dependency type for autowiring:
	 * for example, String. Default is none.
	 * @param type the dependency type to ignore
	 * 自动装配的时候忽略给定的依赖类型
	 * 如果使用了这个方法设置了一些类被忽略，这些类就不能被注入到其他的Bean中去
	 */
	void ignoreDependencyType(Class<?> type);

	/**
	 * Ignore the given dependency interface for autowiring.
	 * <p>This will typically be used by application contexts to register
	 * dependencies that are resolved in other ways, like BeanFactory through
	 * BeanFactoryAware or ApplicationContext through ApplicationContextAware.
	 * <p>By default, only the BeanFactoryAware interface is ignored.
	 * For further types to ignore, invoke this method for each type.
	 * @param ifc the dependency interface to ignore
	 * @see org.springframework.beans.factory.BeanFactoryAware
	 * @see org.springframework.context.ApplicationContextAware
	 * 自动装配的时候忽略规定的接口
	 *
	 * Spring中可以使用自动装配来设置Bean的依赖，比如：
	 * public class UserServiceImpl implements UserService {
	 *
	 *     private UserDao userDao;
	 *
	 *     public void setUserDao(UserDao userDao) {
	 *         this.userDao = userDao;
	 *     }
	 *
	 *     @Override
	 *     public String getUserName(Long userId) {
	 *         return userDao.queryUserName(userId);
	 *     }
	 * }
	 *
	 * xml的配置如下：
	 * <?xml version="1.0" encoding="UTF-8"?>
	 * <beans xmlns="http://www.springframework.org/schema/beans"
	 *        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	 *        xsi:schemaLocation="http://www.springframework.org/schema/beans
	 * 	http://www.springframework.org/schema/beans/spring-beans-3.0.xsd" default-autowire="byType">
	 *
	 *     <bean id="userDao" class="fun.pullock.spring.autowire.xml.dao.impl.UserDaoImpl"/>
	 *     <bean id="userService" class="fun.pullock.spring.autowire.xml.service.impl.UserServiceImpl"/>
	 *
	 * </beans>
	 * 配置中使用了default-autowire="byType"或者byName，可以让userDao自动注入到userService中去，这里使用的是
	 * setter方法进行的注入。
	 *
	 * Spring中还有一种Aware使用方式，示例如下：
	 * public class ApplicationContextUtilXml implements ApplicationContextAware {
	 *
	 *     private static ApplicationContext applicationContext;
	 *
	 *     @Override
	 *     public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
	 *         this.applicationContext = applicationContext;
	 *     }
	 *
	 *     public static Object getBean(String name) {
	 *         return applicationContext.getBean(name);
	 *     }
	 * }
	 *
	 * xml配置如下：
	 * <?xml version="1.0" encoding="UTF-8"?>
	 * <beans xmlns="http://www.springframework.org/schema/beans"
	 *        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	 *        xsi:schemaLocation="http://www.springframework.org/schema/beans
	 * 	http://www.springframework.org/schema/beans/spring-beans-3.0.xsd" default-autowire="byType">
	 *
	 *     <bean id="userDao" class="fun.pullock.spring.autowire.xml.dao.impl.UserDaoImpl"/>
	 *     <bean id="userService" class="fun.pullock.spring.autowire.xml.service.impl.UserServiceImpl"/>
	 *
	 *     <bean id="applicationContextUtilXml" class="fun.pullock.spring.utils.ApplicationContextUtilXml"/>
	 * </beans>
	 * 这种方式中ApplicationContextUtilXml中的applicationContext并不使用上面的自动注入通过setter方法进行注入，
	 * 为了不让Spring容器通过自动注入方式注入类似这种Aware接口的set方法中的Bean，Spring通过ignoreDependencyInterface方法
	 * 来将这些接口（Aware）进行忽略，遇到需要自动装配的时候如果发现是需要忽略的接口（Aware的setXXX方法），则不进行set方法
	 * 的自动注入，而是使用容器的另外的方法进行注入这些setXXX（实际是在initializeBean的时候直接调用的各个Aware的setXXX方法进行的设置）
	 */
	void ignoreDependencyInterface(Class<?> ifc);

	/**
	 * Register a special dependency type with corresponding autowired value.
	 * <p>This is intended for factory/context references that are supposed
	 * to be autowirable but are not defined as beans in the factory:
	 * e.g. a dependency of type ApplicationContext resolved to the
	 * ApplicationContext instance that the bean is living in.
	 * <p>Note: There are no such default types registered in a plain BeanFactory,
	 * not even for the BeanFactory interface itself.
	 * @param dependencyType the dependency type to register. This will typically
	 * be a base interface such as BeanFactory, with extensions of it resolved
	 * as well if declared as an autowiring dependency (e.g. ListableBeanFactory),
	 * as long as the given value actually implements the extended interface.
	 * @param autowiredValue the corresponding autowired value. This may also be an
	 * implementation of the {@link org.springframework.beans.factory.ObjectFactory}
	 * interface, which allows for lazy resolution of the actual target value.
	 *
	 * 往容器中配置一些特殊的依赖注入规则，当需要依赖dependencyType这些类型的Bean时，就可以
	 * 注入指定的autowiredValue对应的Bean。
	 * 比如容器中有很多BeanFactory的实现，当容器需要注入一个BeanFactory类型的Bean时，需要选择哪个？
	 * 容器就会选择使用通过registerResolvableDependency方法注册的autowiredValue这个Bean
	 */
	void registerResolvableDependency(Class<?> dependencyType, Object autowiredValue);

	/**
	 * Determine whether the specified bean qualifies as an autowire candidate,
	 * to be injected into other beans which declare a dependency of matching type.
	 * <p>This method checks ancestor factories as well.
	 * @param beanName the name of the bean to check
	 * @param descriptor the descriptor of the dependency to resolve
	 * @return whether the bean should be considered as autowire candidate
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * 决定给定的bean是否可以作为一个注入的候选者
	 */
	boolean isAutowireCandidate(String beanName, DependencyDescriptor descriptor)
			throws NoSuchBeanDefinitionException;

	/**
	 * Return the registered BeanDefinition for the specified bean, allowing access
	 * to its property values and constructor argument value (which can be
	 * modified during bean factory post-processing).
	 * <p>A returned BeanDefinition object should not be a copy but the original
	 * definition object as registered in the factory. This means that it should
	 * be castable to a more specific implementation type, if necessary.
	 * <p><b>NOTE:</b> This method does <i>not</i> consider ancestor factories.
	 * It is only meant for accessing local bean definitions of this factory.
	 * @param beanName the name of the bean
	 * @return the registered BeanDefinition
	 * @throws NoSuchBeanDefinitionException if there is no bean with the given name
	 * defined in this factory
	 * 返回指定名字的bean的BeanDefinition
	 */
	BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException;

	/**
	 * Freeze all bean definitions, signalling that the registered bean definitions
	 * will not be modified or post-processed any further.
	 * <p>This allows the factory to aggressively cache bean definition metadata.
	 * 冻结所有的bean配置
	 */
	void freezeConfiguration();

	/**
	 * Return whether this factory's bean definitions are frozen,
	 * i.e. are not supposed to be modified or post-processed any further.
	 * @return {@code true} if the factory's configuration is considered frozen
	 * 返回是否工厂中的bean定义被冻结
	 */
	boolean isConfigurationFrozen();

	/**
	 * Ensure that all non-lazy-init singletons are instantiated, also considering
	 * {@link org.springframework.beans.factory.FactoryBean FactoryBeans}.
	 * Typically invoked at the end of factory setup, if desired.
	 * @throws BeansException if one of the singleton beans could not be created.
	 * Note: This may have left the factory with some beans already initialized!
	 * Call {@link #destroySingletons()} for full cleanup in this case.
	 * @see #destroySingletons()
	 * 保证所有非延迟加载的单例都被实例化
	 */
	void preInstantiateSingletons() throws BeansException;

}
