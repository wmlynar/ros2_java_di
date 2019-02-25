package org.ros2.java.di.internal;

public class InstanceWithName {

	public Object instance;
	public String name;

	public InstanceWithName(Object instance, String name) {
		if (instance == null) {
			throw new IllegalArgumentException("Instance cannot be null");
		}
		this.instance = instance;
		if (name == null) {
			name = "";
		}
		// if (!name.isEmpty() && !name.endsWith("/")) {
		// name = name + "/";
		// }
		this.name = name;
	}
}
