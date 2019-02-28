package org.ros2.java.di.example;

import java.util.ArrayList;

import org.ros2.java.di.LogSeldom;
import org.ros2.java.di.RosJavaDi;
import org.ros2.java.di.annotations.Init;
import org.ros2.java.di.annotations.Inject;
import org.ros2.java.di.annotations.Parameter;
import org.ros2.java.di.annotations.Publish;
import org.ros2.java.di.annotations.Repeat;
import org.ros2.rcljava.publisher.Publisher;

import std_msgs.msg.String;

public class DemoPublisher {

	public static final LogSeldom log = RosJavaDi.getLog();

	private int counter = 0;

	@Parameter("parameter")
	private int parameter = 0;

	@Parameter("list")
	private ArrayList<?> list;

	@Publish("/topic")
	Publisher<std_msgs.msg.String> stringPublisher;
	
	@Inject
	RosJavaDi rosJavaDi;

	@Init
	public void init() {
		System.out.println("Publisher initialization");
	}

	@Repeat(interval = 1000)
	public void repeat() {
		std_msgs.msg.String msg = new String();
		msg.setData("Message " + counter++);
		System.out.println("Publishing: " + msg.getData() + " parameter: " + parameter);
		stringPublisher.publish(msg);
		
		rosJavaDi.wakeupRepeater(this, DemoPublisher::repeatTestErrorMessage);
	}

	@Repeat(interval = 5000)
	public void repeatTestErrorMessage() {
		log.error("Test error message");
	}
}
