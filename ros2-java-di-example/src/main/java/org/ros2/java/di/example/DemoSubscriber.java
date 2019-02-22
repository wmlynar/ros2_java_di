package org.ros2.java.di.example;

import org.ros2.java.di.annotations.Subscribe;

public class DemoSubscriber {

	@Subscribe("topic")
	public void stringSubscription(std_msgs.msg.String string) {
		
	}
	
}
