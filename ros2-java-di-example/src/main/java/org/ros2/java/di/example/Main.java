package org.ros2.java.di.example;

import org.ros2.java.di.RosJavaDi;
import org.ros2.java.di.exceptions.CreationException;

public class Main {
	
	public static void main(String[] args) throws CreationException {
		RosJavaDi rosJavaDi = new RosJavaDi("rosjavadi_example", args);
		rosJavaDi.create(DemoPublisher.class);
		rosJavaDi.create(DemoSubscriber.class);
		rosJavaDi.start();
	}
}
