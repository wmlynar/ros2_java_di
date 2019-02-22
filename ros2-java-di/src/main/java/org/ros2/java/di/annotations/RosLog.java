package org.ros2.java.di.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Please use private static Log LOG = RosJavaDi.getLogWithClassName() instead.
 *
 */
@Deprecated
@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
public @interface RosLog {

}
