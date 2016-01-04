/**
 * 
 */
package com.meila.meigou.cachehelper;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;

/**
 * @author flong
 *
 */
@Component
@Aspect
public class CacheAspect {
    private static final Logger log = LoggerFactory.getLogger(CacheAspect.class);

    @Autowired
    private RedisAdapter redisAdapter;
    @Autowired
    private MeilaCacheUtils cacheUtils;

    @Value("${meila.meigou.cachehelper.expiretime:3600}")
    private Integer expireTime;

    private final static String DEFAULT_TABLE = "MEILACACHE";

    @Pointcut("@annotation(com.meila.meigou.cachehelper.MeilaCached)")
    public void cachedPoint() {
    }

    @Pointcut("@annotation(com.meila.meigou.cachehelper.MeilaCacheClear)")
    public void cacheClearPoint() {
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    @Around("cachedPoint()")
    public Object cached(ProceedingJoinPoint pjp) throws Throwable {
        String targetName = pjp.getTarget().getClass().toString();
        String methodName = pjp.getSignature().getName();
        Object[] arguments = pjp.getArgs();



        // 试图得到标注的Cached类
        Method method = getMethod(pjp);
        ParameterNameDiscoverer discoverer = new LocalVariableTableParameterNameDiscoverer();
        String[] parameterNames = discoverer.getParameterNames(method);
        if (method == null) {
            return proceed(pjp, arguments);
        }
        MeilaCached anno = method.getAnnotation(MeilaCached.class);
        if (anno == null) {
            return proceed(pjp, arguments);
        }
        Object result = null;
        /*
         * 为适应Product、Seller。Comment等特殊情形，增加type进行匹配
         */
        Class returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
        if (anno.type() == null || anno.type() == MeilaCacheType.None) {
            String cacheKey = null;
            String hashKey = null;
            // 使用table当做redis key，key参数当做hset的key
            if (anno.table() == null || "".equals(anno.table())) {
                // 不填table时，直接使用key-value进行缓存
                if (anno.key() == null || "".equals(anno.key())) {
                    cacheKey = getCacheKey(targetName, methodName, arguments);
                } else {
                    cacheKey = anno.key();
                }
                result = get(cacheKey, returnType);
                if (result == null) {
                    result = proceed(pjp, arguments);
                    int expire = expireTime;

                    if (anno.expireTime() > 0) {// 当注解中存在配置时，替换当前值
                        expire = anno.expireTime();
                    }
                    put(cacheKey, result, expire);
                }
                return result;
            }
            cacheKey = anno.table();
            if (anno.key() == null || "".equals(anno.key())) {
                hashKey = getCacheKey(targetName, methodName, arguments);
            } else {
                hashKey = anno.key();
            }
            // 试图获取cache中的值
            result = get(cacheKey, hashKey, returnType);
            if (result == null) {
                result = proceed(pjp, arguments);
                int expire = expireTime;

                if (anno.expireTime() > 0) {// 当注解中存在配置时，替换当前值
                    expire = anno.expireTime();
                }
                put(cacheKey, hashKey, result, expire);
            }
        } else {
            // 到arguments中获取
            String cacheKey = null;
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            if (parameterAnnotations.length == 0) {
                // 该方法没有参数
                result = pjp.proceed();
            } else {
                for (int i = 0; i < parameterAnnotations.length; i++) {
                    int length = parameterAnnotations[i].length;
                    if (length > 0) {// 存在annotation
                        for (int j = 0; j < length; j++) {//找出所有带有
                            if (parameterAnnotations[i][j] instanceof MeilaCacheParam) {
                                MeilaCacheParam meilaCacheParam = (MeilaCacheParam) parameterAnnotations[i][j];
                                String value = meilaCacheParam.value();
                                ExpressionParser parser = new SpelExpressionParser();
                                EvaluationContext context = new StandardEvaluationContext();
                                context.setVariable(parameterNames[i],arguments[i]);
                                Expression expression = parser.parseExpression(value);
                                Object key = expression.getValue();
                                cacheKey = cacheKey + key;

                            }
                        }
                    }
                }

                cacheKey = anno.type().getPrefix() + cacheKey;
                // 试图获取cache中的值
                result = get(cacheKey, returnType);
                if (result == null) {
                    result = proceed(pjp, arguments);
                    int expire = expireTime;

                    if (anno.expireTime() > 0) {// 当注解中存在配置时，替换当前值
                        expire = anno.expireTime();
                    }
                    put(cacheKey, result, expire);
                }
            }

        }

        return result;
    }

    @AfterReturning("cacheClearPoint()")
    public void cacheClear(JoinPoint joinPoint) throws Throwable {
        // 试图得到标注的Cached类
        Method method = getMethod(joinPoint);
        if (method == null) {
            return;
        }
        MeilaCacheClear anno = method.getAnnotation(MeilaCacheClear.class);
        if (anno == null) {
            return;
        }
        if (anno.type() == null || anno.type() == MeilaCacheType.None) {
            if (anno.table() == null || "".equals(anno.table())) {
                return;
            }
            if (anno.table().indexOf(",") >= 0) {
                String[] tables = anno.table().split(",");
                for (String table : tables) {
                    redisAdapter.del(table);
                }
            } else {
                redisAdapter.del(anno.table());
            }
        } else {
            String cacheKey = null;
            Object[] arguments = joinPoint.getArgs();
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            if (parameterAnnotations.length > 0) {
                for (int i = 0; i < parameterAnnotations.length; i++) {
                    int length = parameterAnnotations[i].length;
                    if (length > 0) {// 存在annotation
                        for (int j = 0; j < length; j++) {
                            if (parameterAnnotations[i][j] instanceof MeilaCacheParam) {
                                cacheKey = String.valueOf(arguments[i]);
                                break;
                            }
                        }
                    }
                }
                if (cacheKey == null) {// 没找到MeilaCacheParam，默认使用第一个参数
                    cacheKey = String.valueOf(arguments[0]);
                }
                cacheKey = anno.type().getPrefix() + cacheKey;
                redisAdapter.del(cacheKey);

            }
        }

    }

    private Object proceed(ProceedingJoinPoint pjp, Object[] arguments) throws Throwable {
        Object result = null;
        if ((arguments != null) && (arguments.length != 0)) {
            result = pjp.proceed(arguments);
        } else {
            result = pjp.proceed();
        }
        return result;
    }

    /**
     * 获取被拦截方法对象
     * 
     * MethodSignature.getMethod() 获取的是顶层接口或者父类的方法对象 而缓存的注解在实现类的方法上 所以应该使用反射获取当前对象的方法对象
     */
    @SuppressWarnings("rawtypes")
    public Method getMethod(ProceedingJoinPoint pjp) {
        // 获取参数的类型
        Object[] args = pjp.getArgs();
        Class[] argTypes = new Class[pjp.getArgs().length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i].getClass();
        }
        Method method = null;
        try {

            method = pjp.getTarget().getClass().getMethod(pjp.getSignature().getName(), argTypes);
        } catch (NoSuchMethodException e) {
            method = findMethod(pjp.getTarget().getClass(), pjp.getSignature().getName(), argTypes);
            if (method == null) {
                log.error("CacheAspect cannot get method for " + pjp.getSignature().getName(), e);
            }
        } catch (SecurityException e) {
            log.error("CacheAspect cannot get method with expect annotation", e);
        }
        return method;

    }

    @SuppressWarnings("rawtypes")
    public Method getMethod(JoinPoint jp) {
        // 获取参数的类型
        Object[] args = jp.getArgs();
        Class[] argTypes = new Class[jp.getArgs().length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i].getClass();
        }
        Method method = null;
        try {
            method = jp.getTarget().getClass().getMethod(jp.getSignature().getName(), argTypes);
        } catch (NoSuchMethodException e) {
            method = findMethod(jp.getTarget().getClass(), jp.getSignature().getName(), argTypes);
            if (method == null) {
                log.error("CacheAspect cannot get method for " + jp.getSignature().getName(), e);
            }
        } catch (SecurityException e) {
            log.error("CacheAspect cannot get method with expect annotation", e);
        }
        return method;

    }

    public static Method findMethod(Class clazz, String methodName, Class[] paramsTypes) {
        Method[] methods = clazz.getDeclaredMethods();
        List<Method> sameMethods = new ArrayList<Method>();
        for (Method method : methods) {
            if (method.getName().equals(methodName)) {
                sameMethods.add(method);
            }
        }
        if (sameMethods.isEmpty()) {
            return null;
        }
        List<Method> sameCountParams = new ArrayList<Method>();
        for (Method method : sameMethods) {
            if (method.getParameterTypes().length == paramsTypes.length) {
                sameCountParams.add(method);
            }
        }
        if (sameCountParams.isEmpty()) {
            return null;
        }
        for (Method method : sameCountParams) {
            Class<?>[] params = method.getParameterTypes();
            boolean good = true;
            for (int i = 0; i < params.length && good; i++) {
                if (params[i].isAssignableFrom(paramsTypes[i])) {
                    good = true;
                    continue;
                }
                if (params[i].isInterface() && Arrays.asList(paramsTypes[i].getInterfaces()).contains(params[i])) {
                    good = true;
                    continue;
                } else {
                    if (paramsTypes[i].getSuperclass().equals(params[i])) {
                        good = true;
                        continue;
                    }
                }
                good = false;
            }
            if (good) {
                return method;
            }
        }
        return null;
    }

    /**
     * 获得cache key的方法，cache key是Cache中一个Element的唯一标识 cache key包括 包名+类名+方法名，如com.meila.service.UserServiceImpl.getAllUser
     */
    private String getCacheKey(String targetName, String methodName, Object[] arguments) {
        StringBuffer sb = new StringBuffer();
        sb.append(targetName).append(".").append(methodName);
        if ((arguments != null) && (arguments.length != 0)) {
            for (int i = 0; i < arguments.length; i++) {
                sb.append(".").append(JSON.toJSONString(arguments[i]));
            }
        }
        return sb.toString();
    }

    public void put(final String key, final String field, Object value, int expireTime) {
        redisAdapter.hset(key.getBytes(), field.getBytes(), cacheUtils.serialize(value));
        redisAdapter.expire(key, expireTime);
    }

    public <T> T get(final String key, final String hashKey, Class<T> elementType) {
        // 增加缓存命中率统计
        cacheUtils.addTotal();
        if (redisAdapter.hexists(key, hashKey)) {
            byte[] cacheValue = redisAdapter.hget(key.getBytes(), hashKey.getBytes());
            @SuppressWarnings("unchecked")
            T value = (T) cacheUtils.unserialize(cacheValue);
            return value;
        }
        cacheUtils.addMiss();
        return null;
    }

    public void put(final String key, Object value, int expireTime) {
        redisAdapter.set(key.getBytes(), cacheUtils.serialize(value));
        redisAdapter.expire(key, expireTime);
    }

    public <T> T get(final String key, Class<T> elementType) {
        // 增加缓存命中率统计
        cacheUtils.addTotal();
        if (redisAdapter.exists(key)) {
            byte[] cacheValue = redisAdapter.get(key.getBytes());
            @SuppressWarnings("unchecked")
            T value = (T) cacheUtils.unserialize(cacheValue);
            return value;
        }
        cacheUtils.addMiss();
        return null;
    }

}
