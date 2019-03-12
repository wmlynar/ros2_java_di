package org.ros2.java.di.internal;

import java.lang.reflect.Field;

public class ParameterReference {

	public String parameterName;
	public Object object;
	public Field field;
	
	public ParameterReference(String parameterName, Object object, Field field) {
		this.parameterName = parameterName;
		this.object = object;
		this.field = field;
	}
}
