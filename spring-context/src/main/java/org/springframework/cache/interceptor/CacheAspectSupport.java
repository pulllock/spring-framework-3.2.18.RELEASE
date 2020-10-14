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

package org.springframework.cache.interceptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.expression.EvaluationContext;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Base class for caching aspects, such as the {@link CacheInterceptor}
 * or an AspectJ aspect.
 *
 * <p>This enables the underlying Spring caching infrastructure to be
 * used easily to implement an aspect for any aspect system.
 *
 * <p>Subclasses are responsible for calling methods in this class in
 * the correct order.
 *
 * <p>Uses the <b>Strategy</b> design pattern. A {@link CacheManager}
 * implementation will perform the actual cache management, and a
 * {@link CacheOperationSource} is used for determining caching
 * operations.
 *
 * <p>A cache aspect is serializable if its {@code CacheManager} and
 * {@code CacheOperationSource} are serializable.
 *
 * @author Costin Leau
 * @author Juergen Hoeller
 * @author Chris Beams
 * @author Phillip Webb
 * @since 3.1
 */
public abstract class CacheAspectSupport implements InitializingBean {

	private static final String CACHEABLE = "cacheable";

	private static final String UPDATE = "cacheupdate";

	private static final String EVICT = "cacheevict";


	protected final Log logger = LogFactory.getLog(getClass());

	private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

	private CacheManager cacheManager;

	private CacheOperationSource cacheOperationSource;

	private KeyGenerator keyGenerator = new DefaultKeyGenerator();

	private boolean initialized = false;


	/**
	 * Set the CacheManager that this cache aspect should delegate to.
	 */
	public void setCacheManager(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	/**
	 * Return the CacheManager that this cache aspect delegates to.
	 */
	public CacheManager getCacheManager() {
		return this.cacheManager;
	}

	/**
	 * Set one or more cache operation sources which are used to find the cache
	 * attributes. If more than one source is provided, they will be aggregated using a
	 * {@link CompositeCacheOperationSource}.
	 * @param cacheOperationSources must not be {@code null}
	 */
	public void setCacheOperationSources(CacheOperationSource... cacheOperationSources) {
		Assert.notEmpty(cacheOperationSources, "At least 1 CacheOperationSource needs to be specified");
		this.cacheOperationSource = (cacheOperationSources.length > 1 ?
				new CompositeCacheOperationSource(cacheOperationSources) : cacheOperationSources[0]);
	}

	/**
	 * Return the CacheOperationSource for this cache aspect.
	 */
	public CacheOperationSource getCacheOperationSource() {
		return this.cacheOperationSource;
	}

	/**
	 * Set the KeyGenerator for this cache aspect.
	 * Default is {@link DefaultKeyGenerator}.
	 */
	public void setKeyGenerator(KeyGenerator keyGenerator) {
		this.keyGenerator = keyGenerator;
	}

	/**
	 * Return the KeyGenerator for this cache aspect,
	 */
	public KeyGenerator getKeyGenerator() {
		return this.keyGenerator;
	}

	public void afterPropertiesSet() {
		if (this.cacheManager == null) {
			throw new IllegalStateException("Property 'cacheManager' is required");
		}
		if (this.cacheOperationSource == null) {
			throw new IllegalStateException("Property 'cacheOperationSources' is required: " +
					"If there are no cacheable methods, then don't use a cache aspect.");
		}

		this.initialized = true;
	}


	/**
	 * Convenience method to return a String representation of this Method
	 * for use in logging. Can be overridden in subclasses to provide a
	 * different identifier for the given method.
	 * @param method the method we're interested in
	 * @param targetClass class the method is on
	 * @return log message identifying this method
	 * @see org.springframework.util.ClassUtils#getQualifiedMethodName
	 */
	protected String methodIdentification(Method method, Class<?> targetClass) {
		Method specificMethod = ClassUtils.getMostSpecificMethod(method, targetClass);
		return ClassUtils.getQualifiedMethodName(specificMethod);
	}

	protected Collection<Cache> getCaches(CacheOperation operation) {
		Set<String> cacheNames = operation.getCacheNames();
		Collection<Cache> caches = new ArrayList<Cache>(cacheNames.size());
		for (String cacheName : cacheNames) {
			Cache cache = this.cacheManager.getCache(cacheName);
			if (cache == null) {
				throw new IllegalArgumentException("Cannot find cache named '" + cacheName + "' for " + operation);
			}
			caches.add(cache);
		}
		return caches;
	}

	protected CacheOperationContext getOperationContext(CacheOperation operation, Method method, Object[] args,
			Object target, Class<?> targetClass) {

		return new CacheOperationContext(operation, method, args, target, targetClass);
	}

	/**
	 * 执行缓存相关方法
	 * @param invoker
	 * @param target 目标类
	 * @param method 调用的目标方法
	 * @param args 方法参数
	 * @return
	 */
	protected Object execute(Invoker invoker, Object target, Method method, Object[] args) {
		// check whether aspect is enabled
		// to cope with cases where the AJ is pulled in automatically
		if (!this.initialized) {
			return invoker.invoke();
		}

		// get backing class
		// 目标类
		Class<?> targetClass = AopProxyUtils.ultimateTargetClass(target);
		if (targetClass == null && target != null) {
			targetClass = target.getClass();
		}
		// cacheOperationSource在解析BeanDefinition的时候设置，是缓存操作方法的一些元数据
		Collection<CacheOperation> cacheOp = getCacheOperationSource().getCacheOperations(method, targetClass);

		// analyze caching information
		if (!CollectionUtils.isEmpty(cacheOp)) {
			// 创建缓存操作的上下文
			Map<String, Collection<CacheOperationContext>> ops = createOperationContext(cacheOp, method, args, target, targetClass);
			// start with evictions
			// 清除缓存前的检查，在缓存操作前可能需要先清除缓存
			inspectBeforeCacheEvicts(ops.get(EVICT));
			// follow up with cacheable
			// 可缓存的，会尝试从缓存中获取
			CacheStatus status = inspectCacheables(ops.get(CACHEABLE));
			Object retVal;
			// 检查需要更新的
			Map<CacheOperationContext, Object> updates = inspectCacheUpdates(ops.get(UPDATE));
			// status不为null，表示命中了缓存或者是能匹配到设置的条件
			if (status != null) {
				// 匹配到条件，未命中缓存，需要更新缓存
				if (status.updateRequired) {
					updates.putAll(status.cacheUpdates);
				}
				// return cached object
				else {
					// 命中缓存，返回缓存中的值
					return status.retVal;
				}
			}
			// 能匹配到条件，但没命中缓存，需要执行真实方法逻辑
			// 不能匹配到条件的，直接执行真实方法逻辑
			retVal = invoker.invoke();
			// 调用后可能也需要清除缓存
			inspectAfterCacheEvicts(ops.get(EVICT), retVal);
			if (!updates.isEmpty()) {
				// 更新缓存
				update(updates, retVal);
			}
			return retVal;
		}
		// 没有缓存操作，直接返回方法真实调用
		return invoker.invoke();
	}

	private void inspectBeforeCacheEvicts(Collection<CacheOperationContext> evictions) {
		inspectCacheEvicts(evictions, true, ExpressionEvaluator.NO_RESULT);
	}

	private void inspectAfterCacheEvicts(Collection<CacheOperationContext> evictions, Object result) {
		inspectCacheEvicts(evictions, false, result);
	}

	private void inspectCacheEvicts(Collection<CacheOperationContext> evictions, boolean beforeInvocation, Object result) {
		if (!evictions.isEmpty()) {
			boolean log = logger.isTraceEnabled();
			for (CacheOperationContext context : evictions) {
				CacheEvictOperation evictOp = (CacheEvictOperation) context.operation;
				// 调用前清除缓存或者调用后清除缓存
				if (beforeInvocation == evictOp.isBeforeInvocation()) {
					if (context.isConditionPassing(result)) {
						// for each cache
						// lazy key initialization
						Object key = null;
						for (Cache cache : context.getCaches()) {
							// cache-wide flush
							// 整个缓存都要清除
							if (evictOp.isCacheWide()) {
								// 清除缓存中所有数据
								cache.clear();
								if (log) {
									logger.trace("Invalidating entire cache for operation " + evictOp + " on method " + context.method);
								}
							}
							else {
								// check key
								if (key == null) {
									key = context.generateKey();
								}
								if (log) {
									logger.trace("Invalidating cache key " + key + " for operation " + evictOp + " on method " + context.method);
								}
								// 清除指定key对应的缓存
								cache.evict(key);
							}
						}
					}
					else {
						if (log) {
							logger.trace("Cache condition failed on method " + context.method + " for operation " + context.operation);
						}
					}
				}
			}
		}
	}

	private CacheStatus inspectCacheables(Collection<CacheOperationContext> cacheables) {
		Map<CacheOperationContext, Object> cacheUpdates = new LinkedHashMap<CacheOperationContext, Object>(cacheables.size());
		boolean cacheHit = false;
		Object retVal = null;

		if (!cacheables.isEmpty()) {
			boolean log = logger.isTraceEnabled();
			boolean atLeastOnePassed = false;
			for (CacheOperationContext context : cacheables) {
				if (context.isConditionPassing()) {
					atLeastOnePassed = true;
					Object key = context.generateKey();
					if (log) {
						logger.trace("Computed cache key " + key + " for operation " + context.operation);
					}
					if (key == null) {
						throw new IllegalArgumentException("Null key returned for cache operation (maybe you " +
								"are using named params on classes without debug info?) " + context.operation);
					}
					// add op/key (in case an update is discovered later on)
					cacheUpdates.put(context, key);
					// check whether the cache needs to be inspected or not (the method will be invoked anyway)
					if (!cacheHit) {
						for (Cache cache : context.getCaches()) {
							// 从对应的缓存中获取缓存的结果
							Cache.ValueWrapper wrapper = cache.get(key);
							// 命中缓存，可以返回获取到的缓存值
							if (wrapper != null) {
								retVal = wrapper.get();
								cacheHit = true;
								break;
							}
						}
					}
				}
				else {
					if (log) {
						logger.trace("Cache condition failed on method " + context.method + " for operation " + context.operation);
					}
				}
			}

			// return a status only if at least one cacheable matched
			// 至少有一个命中了缓存
			if (atLeastOnePassed) {
				return new CacheStatus(cacheUpdates, !cacheHit, retVal);
			}
		}

		return null;
	}

	private Map<CacheOperationContext, Object> inspectCacheUpdates(Collection<CacheOperationContext> updates) {
		Map<CacheOperationContext, Object> cacheUpdates = new LinkedHashMap<CacheOperationContext, Object>(updates.size());
		if (!updates.isEmpty()) {
			boolean log = logger.isTraceEnabled();
			for (CacheOperationContext context : updates) {
				if (context.isConditionPassing()) {
					Object key = context.generateKey();
					if (log) {
						logger.trace("Computed cache key " + key + " for operation " + context.operation);
					}
					if (key == null) {
						throw new IllegalArgumentException("Null key returned for cache operation (maybe you " +
								"are using named params on classes without debug info?) " + context.operation);
					}
					// add op/key (in case an update is discovered later on)
					cacheUpdates.put(context, key);
				}
				else {
					if (log) {
						logger.trace("Cache condition failed on method " + context.method + " for operation " + context.operation);
					}
				}
			}
		}
		return cacheUpdates;
	}

	private void update(Map<CacheOperationContext, Object> updates, Object retVal) {
		for (Map.Entry<CacheOperationContext, Object> entry : updates.entrySet()) {
			CacheOperationContext operationContext = entry.getKey();
			// 判断是否可以放进缓存里，根据配置的unless来判断
			if (operationContext.canPutToCache(retVal)) {
				for (Cache cache : operationContext.getCaches()) {
					// 放到缓存里
					cache.put(entry.getValue(), retVal);
				}
			}
		}
	}

	/**
	 * 创建缓存操作上线问
	 * @param cacheOperations 方法对应的缓存操作集合
	 * @param method 使用缓存的方法
	 * @param args 方法的参数
	 * @param target 目标对象
	 * @param targetClass 目标类
	 * @return
	 */
	private Map<String, Collection<CacheOperationContext>> createOperationContext(
			Collection<CacheOperation> cacheOperations, Method method, Object[] args, Object target, Class<?> targetClass) {

		Map<String, Collection<CacheOperationContext>> result = new LinkedHashMap<String, Collection<CacheOperationContext>>(3);
		// 将缓存操作分类，分为cacheable、evict、update三类
		Collection<CacheOperationContext> cacheables = new ArrayList<CacheOperationContext>();
		Collection<CacheOperationContext> evicts = new ArrayList<CacheOperationContext>();
		Collection<CacheOperationContext> updates = new ArrayList<CacheOperationContext>();

		for (CacheOperation cacheOperation : cacheOperations) {
			CacheOperationContext opContext = getOperationContext(cacheOperation, method, args, target, targetClass);
			if (cacheOperation instanceof CacheableOperation) {
				cacheables.add(opContext);
			}
			if (cacheOperation instanceof CacheEvictOperation) {
				evicts.add(opContext);
			}
			if (cacheOperation instanceof CachePutOperation) {
				updates.add(opContext);
			}
		}

		result.put(CACHEABLE, cacheables);
		result.put(EVICT, evicts);
		result.put(UPDATE, updates);
		return result;
	}


	public interface Invoker {

		Object invoke();
	}


	protected class CacheOperationContext {

		private final CacheOperation operation;

		private final Method method;

		private final Object[] args;

		private final Object target;

		private final Class<?> targetClass;

		private final Collection<Cache> caches;

		public CacheOperationContext(CacheOperation operation, Method method, Object[] args, Object target, Class<?> targetClass) {
			this.operation = operation;
			this.method = method;
			this.args = args;
			this.target = target;
			this.targetClass = targetClass;
			this.caches = CacheAspectSupport.this.getCaches(operation);
		}

		protected boolean isConditionPassing() {
			return isConditionPassing(ExpressionEvaluator.NO_RESULT);
		}

		protected boolean isConditionPassing(Object result) {
			if (StringUtils.hasText(this.operation.getCondition())) {
				EvaluationContext evaluationContext = createEvaluationContext(result);
				return evaluator.condition(this.operation.getCondition(), this.method, evaluationContext);
			}
			return true;
		}

		protected boolean canPutToCache(Object value) {
			String unless = "";
			if (this.operation instanceof CacheableOperation) {
				unless = ((CacheableOperation) this.operation).getUnless();
			}
			else if (this.operation instanceof CachePutOperation) {
				unless = ((CachePutOperation) this.operation).getUnless();
			}
			if (StringUtils.hasText(unless)) {
				EvaluationContext evaluationContext = createEvaluationContext(value);
				return !evaluator.unless(unless, this.method, evaluationContext);
			}
			return true;
		}

		/**
		 * Computes the key for the given caching operation.
		 * @return generated key (null if none can be generated)
		 */
		protected Object generateKey() {
			if (StringUtils.hasText(this.operation.getKey())) {
				EvaluationContext evaluationContext = createEvaluationContext(ExpressionEvaluator.NO_RESULT);
				return evaluator.key(this.operation.getKey(), this.method, evaluationContext);
			}
			return keyGenerator.generate(this.target, this.method, this.args);
		}

		private EvaluationContext createEvaluationContext(Object result) {
			return evaluator.createEvaluationContext(this.caches, this.method, this.args, this.target, this.targetClass, result);
		}

		protected Collection<Cache> getCaches() {
			return this.caches;
		}
	}


	private static class CacheStatus {

		// caches/key
		final Map<CacheOperationContext, Object> cacheUpdates;

		final boolean updateRequired;

		final Object retVal;

		CacheStatus(Map<CacheOperationContext, Object> cacheUpdates, boolean updateRequired, Object retVal) {
			this.cacheUpdates = cacheUpdates;
			this.updateRequired = updateRequired;
			this.retVal = retVal;
		}
	}

}
