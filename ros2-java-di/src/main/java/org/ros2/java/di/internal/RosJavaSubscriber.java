package org.ros2.java.di.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.apache.commons.logging.Log;
import org.ros2.rcljava.consumers.Consumer;
import org.ros2.rcljava.interfaces.MessageDefinition;
import org.ros2.rcljava.node.Node;
import org.ros2.rcljava.subscription.Subscription;

/**
 * RosJava subscriber implementation that calls the annotated method.
 */
public class RosJavaSubscriber<T extends MessageDefinition> {

	private Subscription<T> subscriber;
	private Node node;
	private Object object;
	private Method method;
	private int queueLength;
	private int timeout;
	private Log log;
	private long lastMessageTime;
	private boolean keepRunning = true;
	private Thread thread;
	private String topicName;
	private Class<T> topicType;

	public RosJavaSubscriber(Node connectedNode, Object object, Method method, String topicName,
			Class<T> topicType, int timeout, Log log) {
		this.node = connectedNode;
		this.object = object;
		this.method = method;
		this.topicName = topicName;
		this.topicType = topicType;
		this.log = log;
		this.timeout = timeout;
	}

	public void start() {
		
	    this.subscriber = node.createSubscription(topicType, topicName, new Consumer<T>() {
			@Override
			public void accept(T message) {
				lastMessageTime = System.currentTimeMillis();
				callMessage(message);
			}
		});
		if (timeout <= 0) {
			return;
		}
		lastMessageTime = System.currentTimeMillis();
		thread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (keepRunning) {
					long time = System.currentTimeMillis();
					long dt = time - lastMessageTime;
					if (dt < 0) {
						log.error("Subscriber timeout: something wrong with time in the system, dt=" + dt);
						dt = 0;
					}
					if (dt >= timeout) {
						lastMessageTime = time;
						dt = 0;
						callMessage(null);
					}
					try {
						Thread.sleep(timeout - dt);
					} catch (InterruptedException e) {
					}
				}
			}
		});
		thread.start();
	}

	public void shutdown() {
		keepRunning = false;
		if (thread != null) {
			thread.interrupt();
		}
	}

	private void callMessage(T message) {
		try {
			method.invoke(object, message);
		} catch (IllegalAccessException | IllegalArgumentException e) {
			log.error("Could not call method " + method.toGenericString(), e);
		} catch (InvocationTargetException e) {
			log.error("Exception caught while handling message in method " + method.toGenericString() + ", message: "
					+ message, e);
		}

	}
}
