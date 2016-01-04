/**
 * 
 */
package com.meila.meigou.cachehelper;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author flong
 *
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MeilaCacheParam {
	String value() default "";//设置第二个key，暂时只用springEl中的获取语法，到时候再改装
}
