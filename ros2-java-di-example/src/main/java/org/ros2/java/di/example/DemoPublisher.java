package org.ros2.java.di.example;

import org.ros2.java.di.annotations.Publish;
import org.ros2.rcljava.publisher.Publisher;

public class DemoPublisher {
	
	@Publish("topic")
	Publisher<std_msgs.msg.String> stringPublisher;

}
