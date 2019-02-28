package org.ros2.java.di.example;

import org.ros2.java.di.RosJavaDi;

public class Main {
	
	public static void main(String[] args) throws Exception {
		RosJavaDi rosJavaDi = new RosJavaDi("rosjavadi_example", args);
		rosJavaDi.create(DemoPublisher.class);
		rosJavaDi.create(DemoSubscriber.class);
		rosJavaDi.create(DemoNamedInstance.class, "name");
		rosJavaDi.start();
	}
}
