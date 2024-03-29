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

package org.springframework.aop.framework.autoproxy;

import java.util.List;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.OrderComparator;

/**
 * Generic auto proxy creator that builds AOP proxies for specific beans
 * based on detected Advisors for each bean.
 *
 * <p>Subclasses must implement the abstract {@link #findCandidateAdvisors()}
 * method to return a list of Advisors applying to any object. Subclasses can
 * also override the inherited {@link #shouldSkip} method to exclude certain
 * objects from auto-proxying.
 *
 * <p>Advisors or advices requiring ordering should implement the
 * {@link org.springframework.core.Ordered} interface. This class sorts
 * Advisors by Ordered order value. Advisors that don't implement the
 * Ordered interface will be considered as unordered; they will appear
 * at the end of the advisor chain in undefined order.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #findCandidateAdvisors
 * 会自动获取Spring容器中注册的所有的Advisor类，
 * 给Spring容器中满足Advisor的pointCut创建代理
 */
@SuppressWarnings("serial")
public abstract class AbstractAdvisorAutoProxyCreator extends AbstractAutoProxyCreator {

	private BeanFactoryAdvisorRetrievalHelper advisorRetrievalHelper;


	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		super.setBeanFactory(beanFactory);
		if (!(beanFactory instanceof ConfigurableListableBeanFactory)) {
			throw new IllegalStateException("Cannot use AdvisorAutoProxyCreator without a ConfigurableListableBeanFactory");
		}
		initBeanFactory((ConfigurableListableBeanFactory) beanFactory);
	}

	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.advisorRetrievalHelper = new BeanFactoryAdvisorRetrievalHelperAdapter(beanFactory);
	}


	/*
		<?xml version="1.0" encoding="UTF-8" ?>
		<!DOCTYPE beans PUBLIC "-//SPRING//DTD BEAN//EN" "http://www.springframework.org/dtd/spring-beans.dtd">
		<beans>
			<!--业务处理类，也就是被代理的类-->
			<bean id="loginService" class="fun.pullock.spring.aop.LoginServiceImpl"/>

			<!--通知类-->
			<bean id="logBeforeLogin" class="fun.pullock.spring.aop.LogBeforeLogin"/>

			<bean id="logBeforeAdvisor" class="org.springframework.aop.support.RegexpMethodPointcutAdvisor">
				<property name="advice">
					<ref bean="logBeforeLogin"/>
				</property>
				<property name="pattern">
					<value>fun.pullock.spring.aop.*</value>
				</property>
			</bean>

			<!--代理类-->
			<bean id="advisorAutoProxy" class="org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator">
			</bean>
		</beans>
	 */
	@Override
	protected Object[] getAdvicesAndAdvisorsForBean(Class beanClass, String beanName, TargetSource targetSource) {
		// 获取可选的增强器，这些增强器可以用在beanName上面
		List advisors = findEligibleAdvisors(beanClass, beanName);
		if (advisors.isEmpty()) {
			return DO_NOT_PROXY;
		}
		return advisors.toArray();
	}

	/**
	 * Find all eligible Advisors for auto-proxying this class.
	 * @param beanClass the clazz to find advisors for
	 * @param beanName the name of the currently proxied bean
	 * @return the empty List, not {@code null},
	 * if there are no pointcuts or interceptors
	 * @see #findCandidateAdvisors
	 * @see #sortAdvisors
	 * @see #extendAdvisors
	 * 获取所有可选的增强器
	 */
	protected List<Advisor> findEligibleAdvisors(Class beanClass, String beanName) {
		// 获取所有的候选增强器，也就是从容器中查找所有类型是Advisor的Bean
		// 缓存有关的BeanFactoryCacheOperationSourceAdvisor也会被查找到
		List<Advisor> candidateAdvisors = findCandidateAdvisors();
		/**
		 * 上面找到了所有的Advisor类型的Bean，下面需要找适合当前bean的Advisor
		 * 获取适用于beanClass的增强器Advisor，并应用
		 * 通过ClassFilter和MethodMatcher对目标类和方法进行匹配
		 *
		 * 缓存相关的注解是在这一步进行解析的，缓存相关的Advisor是BeanFactoryCacheOperationSourceAdvisor
		 *
		 */
		List<Advisor> eligibleAdvisors = findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName);
		// 留给子类实现，筛选出通知器列表
		extendAdvisors(eligibleAdvisors);
		if (!eligibleAdvisors.isEmpty()) {
			// 排序
			eligibleAdvisors = sortAdvisors(eligibleAdvisors);
		}
		return eligibleAdvisors;
	}

	/**
	 * Find all candidate Advisors to use in auto-proxying.
	 * @return the List of candidate Advisors
	 * 从容器中查找Advisor类型的Bean
	 */
	protected List<Advisor> findCandidateAdvisors() {
		return this.advisorRetrievalHelper.findAdvisorBeans();
	}

	/**
	 * Search the given candidate Advisors to find all Advisors that
	 * can apply to the specified bean.
	 * @param candidateAdvisors the candidate Advisors
	 * @param beanClass the target's bean class
	 * @param beanName the target's bean name
	 * @return the List of applicable Advisors
	 * @see ProxyCreationContext#getCurrentProxiedBeanName()
	 * 搜索所有的候选增强器，找到合适的
	 */
	protected List<Advisor> findAdvisorsThatCanApply(
			List<Advisor> candidateAdvisors, Class beanClass, String beanName) {

		ProxyCreationContext.setCurrentProxiedBeanName(beanName);
		try {
			// 查找可以用到当前bean的Advisor
			return AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
		}
		finally {
			ProxyCreationContext.setCurrentProxiedBeanName(null);
		}
	}

	/**
	 * Return whether the Advisor bean with the given name is eligible
	 * for proxying in the first place.
	 * @param beanName the name of the Advisor bean
	 * @return whether the bean is eligible
	 */
	protected boolean isEligibleAdvisorBean(String beanName) {
		return true;
	}

	/**
	 * Sort advisors based on ordering. Subclasses may choose to override this
	 * method to customize the sorting strategy.
	 * @param advisors the source List of Advisors
	 * @return the sorted List of Advisors
	 * @see org.springframework.core.Ordered
	 * @see org.springframework.core.OrderComparator
	 */
	protected List<Advisor> sortAdvisors(List<Advisor> advisors) {
		OrderComparator.sort(advisors);
		return advisors;
	}

	/**
	 * Extension hook that subclasses can override to register additional Advisors,
	 * given the sorted Advisors obtained to date.
	 * <p>The default implementation is empty.
	 * <p>Typically used to add Advisors that expose contextual information
	 * required by some of the later advisors.
	 * @param candidateAdvisors Advisors that have already been identified as
	 * applying to a given bean
	 */
	protected void extendAdvisors(List<Advisor> candidateAdvisors) {
	}

	/**
	 * This auto-proxy creator always returns pre-filtered Advisors.
	 */
	@Override
	protected boolean advisorsPreFiltered() {
		return true;
	}


	/**
	 * Subclass of BeanFactoryAdvisorRetrievalHelper that delegates to
	 * surrounding AbstractAdvisorAutoProxyCreator facilities.
	 */
	private class BeanFactoryAdvisorRetrievalHelperAdapter extends BeanFactoryAdvisorRetrievalHelper {

		public BeanFactoryAdvisorRetrievalHelperAdapter(ConfigurableListableBeanFactory beanFactory) {
			super(beanFactory);
		}

		@Override
		protected boolean isEligibleBean(String beanName) {
			return AbstractAdvisorAutoProxyCreator.this.isEligibleAdvisorBean(beanName);
		}
	}

}
