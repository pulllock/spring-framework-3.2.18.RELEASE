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

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.aop.aspectj.AspectJAfterAdvice;
import org.springframework.aop.aspectj.AspectJAfterReturningAdvice;
import org.springframework.aop.aspectj.AspectJAfterThrowingAdvice;
import org.springframework.aop.aspectj.AspectJAroundAdvice;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.aspectj.AspectJMethodBeforeAdvice;
import org.springframework.aop.aspectj.AspectJPointcutAdvisor;
import org.springframework.aop.aspectj.DeclareParentsAdvisor;
import org.springframework.aop.support.DefaultBeanFactoryPointcutAdvisor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanReference;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanNameReference;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.parsing.ParseState;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.util.StringUtils;
import org.springframework.util.xml.DomUtils;

/**
 * {@link BeanDefinitionParser} for the {@code &lt;aop:config&gt;} tag.
 *
 * @author Rob Harrop
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @since 2.0
 * <aop:config></aop:config>标签解析器
 */
class ConfigBeanDefinitionParser implements BeanDefinitionParser {

	private static final String ASPECT = "aspect";
	private static final String EXPRESSION = "expression";
	private static final String ID = "id";
	private static final String POINTCUT = "pointcut";
	private static final String ADVICE_BEAN_NAME = "adviceBeanName";
	private static final String ADVISOR = "advisor";
	private static final String ADVICE_REF = "advice-ref";
	private static final String POINTCUT_REF = "pointcut-ref";
	private static final String REF = "ref";
	private static final String BEFORE = "before";
	private static final String DECLARE_PARENTS = "declare-parents";
	private static final String TYPE_PATTERN = "types-matching";
	private static final String DEFAULT_IMPL = "default-impl";
	private static final String DELEGATE_REF = "delegate-ref";
	private static final String IMPLEMENT_INTERFACE = "implement-interface";
	private static final String AFTER = "after";
	private static final String AFTER_RETURNING_ELEMENT = "after-returning";
	private static final String AFTER_THROWING_ELEMENT = "after-throwing";
	private static final String AROUND = "around";
	private static final String RETURNING = "returning";
	private static final String RETURNING_PROPERTY = "returningName";
	private static final String THROWING = "throwing";
	private static final String THROWING_PROPERTY = "throwingName";
	private static final String ARG_NAMES = "arg-names";
	private static final String ARG_NAMES_PROPERTY = "argumentNames";
	private static final String ASPECT_NAME_PROPERTY = "aspectName";
	private static final String DECLARATION_ORDER_PROPERTY = "declarationOrder";
	private static final String ORDER_PROPERTY = "order";
	private static final int METHOD_INDEX = 0;
	private static final int POINTCUT_INDEX = 1;
	private static final int ASPECT_INSTANCE_FACTORY_INDEX = 2;

	private ParseState parseState = new ParseState();


    /**
     * 解析aop相关标签，解析成BeanDefinition注册到容器中，会默认注册一个AspectJAwareAdvisorAutoProxyCreator到容器中
     *     <!--业务实现类-->
     *     <bean id="loginService" class="fun.pullock.spring.aop.config.LoginServiceImpl"></bean>
     *
     *     <!--增强类-->
     *     <bean id="logBeforeLogin" class="fun.pullock.spring.aop.config.LogBeforeLogin"></bean>
     *
     *     <aop:config>
     *         <aop:aspect id="loginAspect" ref="logBeforeLogin">
     *             <aop:pointcut expression="execution(* fun.pullock.spring.aop.config.*.*(..))" id="beforeLoginPointCut"/>
     *             <aop:before method="beforeLogin" pointcut-ref="beforeLoginPointCut"/>
     *         </aop:aspect>
     *     </aop:config>
     * @param element the element that is to be parsed into one or more {@link BeanDefinition BeanDefinitions}
     * @param parserContext the object encapsulating the current state of the parsing process;
     * provides access to a {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
     * @return
     */
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		CompositeComponentDefinition compositeDef =
				new CompositeComponentDefinition(element.getTagName(), parserContext.extractSource(element));
		parserContext.pushContainingComponent(compositeDef);

		/**
		 * 配置自动代理创建器
		 * 向Spring容器注册一个beanName为o.s.a.c.internalAutoProxyCreator的BeanDefinition，
		 * 可以自定义也可以使用Spring提供的，需要根据优先级来判断。
		 * Spring默认提供AspectJAwareAdvisorAutoProxyCreator
		 */
		configureAutoProxyCreator(parserContext, element);

		List<Element> childElts = DomUtils.getChildElements(element);
		for (Element elt: childElts) {
			String localName = parserContext.getDelegate().getLocalName(elt);
			// pointcut标签，比较简单，就是解析id和expression
			if (POINTCUT.equals(localName)) {
				parsePointcut(elt, parserContext);
			}
			// advisor标签
			else if (ADVISOR.equals(localName)) {
				parseAdvisor(elt, parserContext);
			}
			// aspect标签
			else if (ASPECT.equals(localName)) {
				parseAspect(elt, parserContext);
			}
		}

		parserContext.popAndRegisterContainingComponent();
		return null;
	}

	/**
	 * Configures the auto proxy creator needed to support the {@link BeanDefinition BeanDefinitions}
	 * created by the '{@code &lt;aop:config/&gt;}' tag. Will force class proxying if the
	 * '{@code proxy-target-class}' attribute is set to '{@code true}'.
	 * @see AopNamespaceUtils
	 * 配置自动代理创建器
	 */
	private void configureAutoProxyCreator(ParserContext parserContext, Element element) {
		AopNamespaceUtils.registerAspectJAutoProxyCreatorIfNecessary(parserContext, element);
	}

	/**
	 * Parses the supplied {@code &lt;advisor&gt;} element and registers the resulting
	 * {@link org.springframework.aop.Advisor} and any resulting {@link org.springframework.aop.Pointcut}
	 * with the supplied {@link BeanDefinitionRegistry}.
	 */
	private void parseAdvisor(Element advisorElement, ParserContext parserContext) {
	    // 解析advisor节点，创建的beanClass为DefaultBeanFactoryPointcutAdvisor
		AbstractBeanDefinition advisorDef = createAdvisorBeanDefinition(advisorElement, parserContext);
		String id = advisorElement.getAttribute(ID);

		try {
			this.parseState.push(new AdvisorEntry(id));
			String advisorBeanName = id;
			if (StringUtils.hasText(advisorBeanName)) {
			    // 注册到beanFactory中
				parserContext.getRegistry().registerBeanDefinition(advisorBeanName, advisorDef);
			}
			else {
				advisorBeanName = parserContext.getReaderContext().registerWithGeneratedName(advisorDef);
			}

			// 解析point-cut属性，可能会返回BeanDefinition类型或者String类型
			Object pointcut = parsePointcutProperty(advisorElement, parserContext);
			// pointcut是BeanDefinition类型的
			if (pointcut instanceof BeanDefinition) {
				advisorDef.getPropertyValues().add(POINTCUT, pointcut);
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef, (BeanDefinition) pointcut));
			}
			// pointcut-ref是String类型
			else if (pointcut instanceof String) {
				advisorDef.getPropertyValues().add(POINTCUT, new RuntimeBeanReference((String) pointcut));
				parserContext.registerComponent(
						new AdvisorComponentDefinition(advisorBeanName, advisorDef));
			}
		}
		finally {
			this.parseState.pop();
		}
	}

	/**
	 * Create a {@link RootBeanDefinition} for the advisor described in the supplied. Does <strong>not</strong>
	 * parse any associated '{@code pointcut}' or '{@code pointcut-ref}' attributes.
	 */
	private AbstractBeanDefinition createAdvisorBeanDefinition(Element advisorElement, ParserContext parserContext) {
		RootBeanDefinition advisorDefinition = new RootBeanDefinition(DefaultBeanFactoryPointcutAdvisor.class);
		advisorDefinition.setSource(parserContext.extractSource(advisorElement));

		String adviceRef = advisorElement.getAttribute(ADVICE_REF);
		if (!StringUtils.hasText(adviceRef)) {
			parserContext.getReaderContext().error(
					"'advice-ref' attribute contains empty value.", advisorElement, this.parseState.snapshot());
		}
		else {
			advisorDefinition.getPropertyValues().add(
					ADVICE_BEAN_NAME, new RuntimeBeanNameReference(adviceRef));
		}

		if (advisorElement.hasAttribute(ORDER_PROPERTY)) {
			advisorDefinition.getPropertyValues().add(
					ORDER_PROPERTY, advisorElement.getAttribute(ORDER_PROPERTY));
		}

		return advisorDefinition;
	}

	/**
	 *     <!--业务实现类-->
	 *     <bean id="loginService" class="fun.pullock.spring.aop.config.LoginServiceImpl"></bean>
	 *
	 *     <!--增强类-->
	 *     <bean id="logBeforeLogin" class="fun.pullock.spring.aop.config.LogBeforeLogin"></bean>
	 *
	 *     <aop:config>
	 *         <aop:aspect id="loginAspect" ref="logBeforeLogin">
	 *             <aop:pointcut expression="execution(* fun.pullock.spring.aop.config.*.*(..))" id="beforeLoginPointCut"/>
	 *             <aop:before method="beforeLogin" pointcut-ref="beforeLoginPointCut"/>
	 *         </aop:aspect>
	 *     </aop:config>
	 * @param aspectElement
	 * @param parserContext
	 *
	 * 解析aspect标签
	 */
	private void parseAspect(Element aspectElement, ParserContext parserContext) {
		// id属性
		String aspectId = aspectElement.getAttribute(ID);
		// ref属性
		String aspectName = aspectElement.getAttribute(REF);

		try {
			this.parseState.push(new AspectEntry(aspectId, aspectName));
			List<BeanDefinition> beanDefinitions = new ArrayList<BeanDefinition>();
			List<BeanReference> beanReferences = new ArrayList<BeanReference>();

			// 处理declare-parents标签
			List<Element> declareParents = DomUtils.getChildElementsByTagName(aspectElement, DECLARE_PARENTS);
			for (int i = METHOD_INDEX; i < declareParents.size(); i++) {
				Element declareParentsElement = declareParents.get(i);
                /**
                 * 解析declare-parents，解析成DeclareParentsAdvisor类型的BeanDefinition
                 * declare-parents有四个属性，type-matching、implement-interface、default-impl、delegate-ref
                 *
                 * 意思是为符合type-matching指定的规则的类，新增implement-interface指定的接口，默认实现是default-impl或delegate-ref
                 * 就是为现有的功能新增一个额外新接口
                 */
				beanDefinitions.add(parseDeclareParents(declareParentsElement, parserContext));
			}

			// We have to parse "advice" and all the advice kinds in one loop, to get the
			// ordering semantics right.
			// <aop:aspect>下的子标签
			NodeList nodeList = aspectElement.getChildNodes();
			boolean adviceFoundAlready = false;
			for (int i = 0; i < nodeList.getLength(); i++) {
				Node node = nodeList.item(i);
				/**
				 * isAdviceNode方法判断aspect下的标签是否是：
				 * <aop:before></aop:before>
				 * <aop:after></aop:after>
				 * <aop:after-returning></aop:after-returning>
				 * <aop:after-throwing></aop:after-throwing>
				 * <aop:around></aop:around>
				 */
				if (isAdviceNode(node, parserContext)) {
					if (!adviceFoundAlready) {
						adviceFoundAlready = true;
						if (!StringUtils.hasText(aspectName)) {
							parserContext.getReaderContext().error(
									"<aspect> tag needs aspect bean reference via 'ref' attribute when declaring advices.",
									aspectElement, this.parseState.snapshot());
							return;
						}
						beanReferences.add(new RuntimeBeanReference(aspectName));
					}
					// 解析advice，解析后成一个advisor，包含advice、pointcut等，注册到beanFactory中，类型是AspectJPointcutAdvisor
					AbstractBeanDefinition advisorDefinition = parseAdvice(
							aspectName, i, aspectElement, (Element) node, parserContext, beanDefinitions, beanReferences);
					beanDefinitions.add(advisorDefinition);
				}
			}

			AspectComponentDefinition aspectComponentDefinition = createAspectComponentDefinition(
					aspectElement, aspectId, beanDefinitions, beanReferences, parserContext);
			parserContext.pushContainingComponent(aspectComponentDefinition);

			// <aop:aspect>下的pointcut标签，解析完并注册到beanFactory中
			List<Element> pointcuts = DomUtils.getChildElementsByTagName(aspectElement, POINTCUT);
			for (Element pointcutElement : pointcuts) {
				parsePointcut(pointcutElement, parserContext);
			}

			parserContext.popAndRegisterContainingComponent();
		}
		finally {
			this.parseState.pop();
		}
	}

	private AspectComponentDefinition createAspectComponentDefinition(
			Element aspectElement, String aspectId, List<BeanDefinition> beanDefs,
			List<BeanReference> beanRefs, ParserContext parserContext) {

		BeanDefinition[] beanDefArray = beanDefs.toArray(new BeanDefinition[beanDefs.size()]);
		BeanReference[] beanRefArray = beanRefs.toArray(new BeanReference[beanRefs.size()]);
		Object source = parserContext.extractSource(aspectElement);
		return new AspectComponentDefinition(aspectId, beanDefArray, beanRefArray, source);
	}

	/**
	 * Return {@code true} if the supplied node describes an advice type. May be one of:
	 * '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}'.
	 */
	private boolean isAdviceNode(Node aNode, ParserContext parserContext) {
		if (!(aNode instanceof Element)) {
			return false;
		}
		else {
			String name = parserContext.getDelegate().getLocalName(aNode);
			return (BEFORE.equals(name) || AFTER.equals(name) || AFTER_RETURNING_ELEMENT.equals(name) ||
					AFTER_THROWING_ELEMENT.equals(name) || AROUND.equals(name));
		}
	}

	/**
	 * Parse a '{@code declare-parents}' element and register the appropriate
	 * DeclareParentsAdvisor with the BeanDefinitionRegistry encapsulated in the
	 * supplied ParserContext.
     * 解析declare-parents元素
     * 解析成DeclareParentsAdvisor类型的BeanDefinition
	 */
	private AbstractBeanDefinition parseDeclareParents(Element declareParentsElement, ParserContext parserContext) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(DeclareParentsAdvisor.class);
		// implement-interface属性
		builder.addConstructorArgValue(declareParentsElement.getAttribute(IMPLEMENT_INTERFACE));
		// types-matching属性
		builder.addConstructorArgValue(declareParentsElement.getAttribute(TYPE_PATTERN));

		// default-impl属性
		String defaultImpl = declareParentsElement.getAttribute(DEFAULT_IMPL);

		// delegate-ref属性
		String delegateRef = declareParentsElement.getAttribute(DELEGATE_REF);

		if (StringUtils.hasText(defaultImpl) && !StringUtils.hasText(delegateRef)) {
			builder.addConstructorArgValue(defaultImpl);
		}
		else if (StringUtils.hasText(delegateRef) && !StringUtils.hasText(defaultImpl)) {
			builder.addConstructorArgReference(delegateRef);
		}
		else {
			parserContext.getReaderContext().error(
					"Exactly one of the " + DEFAULT_IMPL + " or " + DELEGATE_REF + " attributes must be specified",
					declareParentsElement, this.parseState.snapshot());
		}

		AbstractBeanDefinition definition = builder.getBeanDefinition();
		definition.setSource(parserContext.extractSource(declareParentsElement));
		parserContext.getReaderContext().registerWithGeneratedName(definition);
		return definition;
	}

	/**
	 * Parses one of '{@code before}', '{@code after}', '{@code after-returning}',
	 * '{@code after-throwing}' or '{@code around}' and registers the resulting
	 * BeanDefinition with the supplied BeanDefinitionRegistry.
	 * @return the generated advice RootBeanDefinition
     * 解析各种advice
     * <aop:before></aop:before>
     * <aop:after></aop:after>
     * <aop:after-returning></aop:after-returning>
     * <aop:after-throwing></aop:after-throwing>
     * <aop:around></aop:around>
	 */
	private AbstractBeanDefinition parseAdvice(
			String aspectName, int order, Element aspectElement, Element adviceElement, ParserContext parserContext,
			List<BeanDefinition> beanDefinitions, List<BeanReference> beanReferences) {

		try {
			this.parseState.push(new AdviceEntry(parserContext.getDelegate().getLocalName(adviceElement)));

			// create the method factory bean
			// 解析advice节点中的method属性，并封装为MethodLocatingFactoryBean对象
			RootBeanDefinition methodDefinition = new RootBeanDefinition(MethodLocatingFactoryBean.class);
			// 目标bean对象名，也就是<aop:aspect ref="xxx"> ref中指向的代理的bean
			methodDefinition.getPropertyValues().add("targetBeanName", aspectName);
			// advice方法名，比如 before after等
			methodDefinition.getPropertyValues().add("methodName", adviceElement.getAttribute("method"));
			methodDefinition.setSynthetic(true);

			// create instance factory definition
			RootBeanDefinition aspectFactoryDef =
					new RootBeanDefinition(SimpleBeanFactoryAwareAspectInstanceFactory.class);
			aspectFactoryDef.getPropertyValues().add("aspectBeanName", aspectName);
			aspectFactoryDef.setSynthetic(true);

			// register the pointcut
            // 解析point-cut属性，并结合上面两个bean，最终包装为AbstractAspectJAdvice通知对象
			AbstractBeanDefinition adviceDef = createAdviceDefinition(
					adviceElement, parserContext, aspectName, order, methodDefinition, aspectFactoryDef,
					beanDefinitions, beanReferences);

			// configure the advisor
			// 最终包装为AspectJPointcutAdvisor对象
			RootBeanDefinition advisorDefinition = new RootBeanDefinition(AspectJPointcutAdvisor.class);
			advisorDefinition.setSource(parserContext.extractSource(adviceElement));
			advisorDefinition.getConstructorArgumentValues().addGenericArgumentValue(adviceDef);
			if (aspectElement.hasAttribute(ORDER_PROPERTY)) {
				advisorDefinition.getPropertyValues().add(
						ORDER_PROPERTY, aspectElement.getAttribute(ORDER_PROPERTY));
			}

			// register the final advisor
			parserContext.getReaderContext().registerWithGeneratedName(advisorDefinition);

			return advisorDefinition;
		}
		finally {
			this.parseState.pop();
		}
	}

	/**
	 * Creates the RootBeanDefinition for a POJO advice bean. Also causes pointcut
	 * parsing to occur so that the pointcut may be associate with the advice bean.
	 * This same pointcut is also configured as the pointcut for the enclosing
	 * Advisor definition using the supplied MutablePropertyValues.
	 */
	private AbstractBeanDefinition createAdviceDefinition(
			Element adviceElement, ParserContext parserContext, String aspectName, int order,
			RootBeanDefinition methodDef, RootBeanDefinition aspectFactoryDef,
			List<BeanDefinition> beanDefinitions, List<BeanReference> beanReferences) {

		/**
		 * getAdviceClass方法根据不同的advice名字返回不同的class
		 * 比如 before返回的是AspectJMethodBeforeAdvice
		 */
		RootBeanDefinition adviceDefinition = new RootBeanDefinition(getAdviceClass(adviceElement, parserContext));
		adviceDefinition.setSource(parserContext.extractSource(adviceElement));

		adviceDefinition.getPropertyValues().add(ASPECT_NAME_PROPERTY, aspectName);
		adviceDefinition.getPropertyValues().add(DECLARATION_ORDER_PROPERTY, order);

		// 解析节点是否含有 returning throwing arg-names
		if (adviceElement.hasAttribute(RETURNING)) {
			adviceDefinition.getPropertyValues().add(
					RETURNING_PROPERTY, adviceElement.getAttribute(RETURNING));
		}
		if (adviceElement.hasAttribute(THROWING)) {
			adviceDefinition.getPropertyValues().add(
					THROWING_PROPERTY, adviceElement.getAttribute(THROWING));
		}
		if (adviceElement.hasAttribute(ARG_NAMES)) {
			adviceDefinition.getPropertyValues().add(
					ARG_NAMES_PROPERTY, adviceElement.getAttribute(ARG_NAMES));
		}

		ConstructorArgumentValues cav = adviceDefinition.getConstructorArgumentValues();
		cav.addIndexedArgumentValue(METHOD_INDEX, methodDef);

		// 解析point-cut属性
		Object pointcut = parsePointcutProperty(adviceElement, parserContext);
		if (pointcut instanceof BeanDefinition) {
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcut);
			beanDefinitions.add((BeanDefinition) pointcut);
		}
		else if (pointcut instanceof String) {
			RuntimeBeanReference pointcutRef = new RuntimeBeanReference((String) pointcut);
			cav.addIndexedArgumentValue(POINTCUT_INDEX, pointcutRef);
			beanReferences.add(pointcutRef);
		}

		cav.addIndexedArgumentValue(ASPECT_INSTANCE_FACTORY_INDEX, aspectFactoryDef);

		return adviceDefinition;
	}

	/**
	 * Gets the advice implementation class corresponding to the supplied {@link Element}.
	 */
	private Class getAdviceClass(Element adviceElement, ParserContext parserContext) {
		String elementName = parserContext.getDelegate().getLocalName(adviceElement);
		if (BEFORE.equals(elementName)) {
			return AspectJMethodBeforeAdvice.class;
		}
		else if (AFTER.equals(elementName)) {
			return AspectJAfterAdvice.class;
		}
		else if (AFTER_RETURNING_ELEMENT.equals(elementName)) {
			return AspectJAfterReturningAdvice.class;
		}
		else if (AFTER_THROWING_ELEMENT.equals(elementName)) {
			return AspectJAfterThrowingAdvice.class;
		}
		else if (AROUND.equals(elementName)) {
			return AspectJAroundAdvice.class;
		}
		else {
			throw new IllegalArgumentException("Unknown advice kind [" + elementName + "].");
		}
	}

	/**
	 * Parses the supplied {@code &lt;pointcut&gt;} and registers the resulting
	 * Pointcut with the BeanDefinitionRegistry.
	 * 解析pointcut
	 */
	private AbstractBeanDefinition parsePointcut(Element pointcutElement, ParserContext parserContext) {
	    // id
		String id = pointcutElement.getAttribute(ID);

		// expression
		String expression = pointcutElement.getAttribute(EXPRESSION);

		AbstractBeanDefinition pointcutDefinition = null;

		try {
			this.parseState.push(new PointcutEntry(id));
			// beanClass为AspectJExpressionPointcut类型
			pointcutDefinition = createPointcutDefinition(expression);
			pointcutDefinition.setSource(parserContext.extractSource(pointcutElement));

			String pointcutBeanName = id;
			if (StringUtils.hasText(pointcutBeanName)) {
			    // 注册pointcut的Bean定义到容器中
				parserContext.getRegistry().registerBeanDefinition(pointcutBeanName, pointcutDefinition);
			}
			else {
				// 没有指定id，就生成一个BeanName，再注册到容器中
				pointcutBeanName = parserContext.getReaderContext().registerWithGeneratedName(pointcutDefinition);
			}

			parserContext.registerComponent(
					new PointcutComponentDefinition(pointcutBeanName, pointcutDefinition, expression));
		}
		finally {
			this.parseState.pop();
		}

		return pointcutDefinition;
	}

	/**
	 * Parses the {@code pointcut} or {@code pointcut-ref} attributes of the supplied
	 * {@link Element} and add a {@code pointcut} property as appropriate. Generates a
	 * {@link org.springframework.beans.factory.config.BeanDefinition} for the pointcut if  necessary
	 * and returns its bean name, otherwise returns the bean name of the referred pointcut.
	 * 解析<aop:advisor></aop:advisor>标签下的pointcut或者pointcut-ref属性
	 */
	private Object parsePointcutProperty(Element element, ParserContext parserContext) {
		// pointcut和pointcut-ref不能同时存在+
		if (element.hasAttribute(POINTCUT) && element.hasAttribute(POINTCUT_REF)) {
			parserContext.getReaderContext().error(
					"Cannot define both 'pointcut' and 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
		// pointcut属性
		else if (element.hasAttribute(POINTCUT)) {
			// Create a pointcut for the anonymous pc and register it.
			String expression = element.getAttribute(POINTCUT);
			// AspectJExpressionPointcut类型的Bean定义
			AbstractBeanDefinition pointcutDefinition = createPointcutDefinition(expression);
			pointcutDefinition.setSource(parserContext.extractSource(element));
			return pointcutDefinition;
		}
		// pointcut-ref属性
		else if (element.hasAttribute(POINTCUT_REF)) {
			String pointcutRef = element.getAttribute(POINTCUT_REF);
			if (!StringUtils.hasText(pointcutRef)) {
				parserContext.getReaderContext().error(
						"'pointcut-ref' attribute contains empty value.", element, this.parseState.snapshot());
				return null;
			}
			return pointcutRef;
		}
		else {
			parserContext.getReaderContext().error(
					"Must define one of 'pointcut' or 'pointcut-ref' on <advisor> tag.",
					element, this.parseState.snapshot());
			return null;
		}
	}

	/**
	 * Creates a {@link BeanDefinition} for the {@link AspectJExpressionPointcut} class using
	 * the supplied pointcut expression.
	 */
	protected AbstractBeanDefinition createPointcutDefinition(String expression) {
	    // AspectJExpressionPointcut类型
		RootBeanDefinition beanDefinition = new RootBeanDefinition(AspectJExpressionPointcut.class);
		beanDefinition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
		beanDefinition.setSynthetic(true);
		beanDefinition.getPropertyValues().add(EXPRESSION, expression);
		return beanDefinition;
	}

}
