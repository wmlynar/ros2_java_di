package org.ros2.java.di.example;

import org.ros2.java.di.annotations.Init;
import org.ros2.java.di.annotations.Subscribe;

public class DemoSubscriber {

	@Init
	public void init() {
		System.out.println("Subscriber initialization");
	}
	
	@Subscribe("/topic")
	public void stringSubscription(std_msgs.msg.String msg) {
		System.out.println("Received: " + msg.getData());
	}
	
}
