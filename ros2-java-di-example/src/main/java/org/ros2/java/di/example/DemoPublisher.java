package org.ros2.java.di.example;

import org.ros2.java.di.annotations.Init;
import org.ros2.java.di.annotations.Parameter;
import org.ros2.java.di.annotations.Publish;
import org.ros2.java.di.annotations.Repeat;
import org.ros2.rcljava.publisher.Publisher;

import std_msgs.msg.String;

public class DemoPublisher {
	
	private int counter = 0;
	
	@Parameter("parameter")
	private int parameter = 0;
	
	@Publish("topic")
	Publisher<std_msgs.msg.String> stringPublisher;

	@Init
	public void init() {
		System.out.println("Publisher initialization");
	}
	
	@Repeat(interval=1000)
	public void repeat() {
		std_msgs.msg.String msg = new String();
		msg.setData("Message " + counter++);
		System.out.println("Publishing: " + msg.getData() + " parameter: " + parameter);
		stringPublisher.publish(msg);
	}
}
