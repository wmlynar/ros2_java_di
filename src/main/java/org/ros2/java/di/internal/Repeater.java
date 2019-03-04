package org.ros2.java.di.internal;

import java.lang.reflect.Method;

import org.ros2.java.di.annotations.Repeat;

public class Repeater {
	
	public Object object;
	public Method method;
	public Repeat repeat;
	public Thread thread;
	
	/**
	 * Shutdown = true indicates that repeater should be shut down.
	 */
	public boolean shutdown = false;
	
	public Repeater(Object object, Method method, Repeat parameters) {
		this.object = object;
		this.method = method;
		this.repeat = parameters;
	}
	
	/**
	 * Shuts down the repeater.
	 */
	public void shutdown() {
		shutdown = true;
		if(thread!=null) {
			thread.interrupt();
		}
		
	}
}
