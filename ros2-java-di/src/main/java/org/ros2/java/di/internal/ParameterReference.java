package org.ros2.java.di.internal;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Future;

import org.ros2.rcljava.parameters.ParameterVariant;

public class ParameterReference {

	public String parameterName;
	public Object object;
	public Field field;
	public Future<List<ParameterVariant>> future;
	
	public ParameterReference(String parameterName, Object object,
			Field field, Future<List<ParameterVariant>> future) {
		this.parameterName = parameterName;
		this.object = object;
		this.field = field;
		this.future = future;
	}
}
