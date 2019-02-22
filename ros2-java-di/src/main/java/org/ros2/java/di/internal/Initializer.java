package org.ros2.java.di.internal;

import java.lang.reflect.Method;

import org.ros2.java.di.annotations.Init;

public class Initializer {

	public Object object;
	public Method method;
	public Init init;
	
	public Initializer(Object object, Method method, Init init) {
		this.object = object;
		this.method = method;
		this.init = init;
	}

}
