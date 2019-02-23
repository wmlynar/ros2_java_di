package org.ros2.java.di.internal;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.ros2.rcljava.node.Node;
import org.ros2.rcljava.publisher.Publisher;

/**
 * Publisher to /rosout topic that is used by RosJavaDi logger wrappers.
 */
public class RosoutPublisher {

	private Node node;
	private Publisher<rcl_interfaces.msg.Log> publisher;

	public RosoutPublisher(Node node) {
		this.node = node;
		this.publisher = node.createPublisher(rcl_interfaces.msg.Log.class, "/rosout");
		// TODO: add time / clock when it is ready
	}

	public void publish(byte level, String sourceClass, String sourceMethod, int line, Object message, Throwable throwable) {
		StringWriter stringWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(stringWriter);
		throwable.printStackTrace(printWriter);
		publish(level, sourceClass, sourceMethod, line, message.toString() + '\n' + stringWriter.toString());
	}

	public void publish(byte level, String sourceClass, String sourceMethod, int line, Object message) {
		rcl_interfaces.msg.Log logMessage = new rcl_interfaces.msg.Log();
		//TODO: add time / clock when it is ready
		//logMessage.setStamp(0);
		logMessage.setLevel(level);
		logMessage.setName(node.getName());
		logMessage.setMsg(message.toString());
		logMessage.setFile(sourceClass);
		logMessage.setFunction(sourceMethod);
		logMessage.setLine(line);
		publisher.publish(logMessage);
	}
}
