package org.ros2.java.di;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ros2.java.di.annotations.Init;
import org.ros2.java.di.annotations.Publish;
import org.ros2.java.di.annotations.Repeat;
import org.ros2.java.di.annotations.Subscribe;
import org.ros2.java.di.exceptions.CreationException;
import org.ros2.java.di.internal.Initializer;
import org.ros2.java.di.internal.Repeater;
import org.ros2.java.di.internal.RosJavaDiLog;
import org.ros2.java.di.internal.RosJavaSubscriber;
import org.ros2.java.maven.Ros2JavaLibraries;
import org.ros2.rcljava.RCLJava;
import org.ros2.rcljava.common.JNIUtils;
import org.ros2.rcljava.executors.SingleThreadedExecutor;
import org.ros2.rcljava.interfaces.MessageDefinition;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.publisher.Publisher;

public class RosJavaDi {

	private static Log LOG = LogFactory.getLog(RosJavaDi.class);

	private Object monitor = new Object();

	private ArrayList<Initializer> initializers = new ArrayList<>();
	private ArrayList<Repeater> repeaters = new ArrayList<>();
	private ArrayList<RosJavaSubscriber<?>> subscribers = new ArrayList<>();

	private long contextHandle;
	private SingleThreadedExecutor executor;
	private BaseComposableNode composablenode;

	public RosJavaDi(String name, String[] args) {
		Ros2JavaLibraries.installLibraryLoader();
		contextHandle = RCLJava.rclJavaInit(args);
		executor = new SingleThreadedExecutor();
		composablenode = new BaseComposableNode(name, args, true, contextHandle);
	}

	public void start() {
		// start all initializers
		for (Initializer initializer : initializers) {
			try {
				initializer.method.invoke(initializer.object);
			} catch (Throwable e) {
				LOG.error("Exception caught while calling node initializer " + initializer.method.toGenericString(), e);
			}
		}

		// start all repeaters
		for (Repeater repeater : repeaters) {
			startRepeater(repeater);
		}

		// register all the subscribers
		for (RosJavaSubscriber<?> subscriber : subscribers) {
			subscriber.start();
		}

		executor.addNode(composablenode);
		executor.spin(contextHandle);
		executor.removeNode(composablenode);
	}

	public void shutdown() {
		RCLJava.shutdown(contextHandle);

		// shutdown all repeaters
		for (Repeater repeater : repeaters) {
			repeater.shutdown();
		}

		// shutdown all subscribers
		for (RosJavaSubscriber<?> subscriber : subscribers) {
			subscriber.shutdown();
		}
	}

	/**
	 * Returns logger wrapped with /rosout log publisher.
	 */
	public static LogSeldom getLog(String name) {
		return new RosJavaDiLog(name);
	}

	/**
	 * Returns logger wrapped with /rosout log publisher.
	 */
	public static LogSeldom getLog(Class<?> clazz) {
		return new RosJavaDiLog(clazz);
	}

	/**
	 * Returns logger wrapped with /rosout log publisher that gets caller class
	 * name.
	 */
	public static LogSeldom getLog() {
		Throwable dummyException = new Throwable();
		StackTraceElement locations[] = dummyException.getStackTrace();
		String cname = "unknown";
		if (locations != null && locations.length > 1) {
			StackTraceElement caller = locations[1];
			cname = caller.getClassName();
		}
		return new RosJavaDiLog(cname);
	}

	/**
	 * Creates new object of the given class and injects the properties according to
	 * the annotations. Requires connectToRemoteMaster to be called before.
	 */
	public <T> T create(Class<T> clazz) throws CreationException {
		try {
			return inject(clazz.newInstance());
		} catch (InstantiationException | IllegalAccessException e) {
			throw new CreationException("Could not create class " + clazz.toGenericString(), e);
		}
	}

	/**
	 * Injects the properties according to the annotations. Requires
	 * connectToRemoteMaster to be called before.
	 */
	public <T> T inject(T object) throws CreationException {
		Class<?> clazz = object.getClass();
		try {
			// for each field
			for (Field field : clazz.getDeclaredFields()) {
				injectPublishers(field, object);
				injectParameters(field, object);
			}

			// for each method
			for (Method method : clazz.getDeclaredMethods()) {
				collectInitializers(method, object);
				collectRepeaters(method, object);
				createSubscribers(method, object);
			}
		} catch (IllegalAccessException e) {
			throw new CreationException("Exception while creating " + clazz.toString(), e);
		}
		return object;
	}

	private void startRepeater(Repeater repeater) {
		Object object = repeater.object;
		Method method = repeater.method;
		Repeat repeat = repeater.repeat;
		boolean isDelay = repeat.delay() != 0;
		boolean isInterval = repeat.interval() != 0;
		repeater.thread = new Thread(new Runnable() {
			@Override
			public void run() {
				int count = 0;
				long start = System.currentTimeMillis();
				while ((repeat.count() == 0 || count < repeat.count()) && !repeater.shutdown) {
					count++;
					try {
						Object result = method.invoke(object);

						// Check if it returned false
						if (result != null) {
							if (!(Boolean) result) {
								break;
							}
						}
					} catch (Throwable e) {
						LOG.error("Exception caught while calling repeater " + method.toGenericString(), e);
					}
					try {
						if (isDelay) {
							Thread.sleep(repeat.delay());
						} else if (isInterval) {
							long now = System.currentTimeMillis();
							long sleep = repeat.interval() - (now - start);
							start += repeat.interval();
							if (sleep > 0) {
								Thread.sleep(sleep);
							}
						}
					} catch (InterruptedException ie) {
						// do nothing
					}
				}
			}
		});
		repeater.thread.start();
	}

	private <T> void injectPublishers(Field field, T object)
			throws IllegalAccessException, IllegalArgumentException, CreationException {
		Publish publish = field.getAnnotation(Publish.class);
		if (publish != null) {
			makeAccessible(field);

			Publisher<?> publisher = createPublisher(publish, field);
			field.set(object, publisher);
		}
	}

	private <T> void injectParameters(Field field, T object) throws CreationException, IllegalAccessException {
		// inject parameters
		org.ros2.java.di.annotations.Parameter parameter = field
				.getAnnotation(org.ros2.java.di.annotations.Parameter.class);
		if (parameter != null) {
			makeAccessible(field);

			String parameterName = parameter.value();
			injectParameter(object, field, parameterName);
		}
	}

	private <T> void injectParameter(T object, Field field, String parameterName) throws IllegalAccessException {
//        parameterName = prefixParameterName(parameterName);
//        if (parameterTree.has(parameterName)) {
//                setParameterValueFromServer(object, field, parameterName);
//        } else {
//                publishParameter(object, field, parameterName);
//        }
//        parameterTree.addParameterListener(parameterName, new ParameterListener() {
//
//                @Override
//                public void onNewValue(Object value) {
//                        try {
//                                setParameterValueFromObject(object, field, value);
//                        } catch (IllegalArgumentException | IllegalAccessException e) {
//                                log.error("Exception caught while setting parameter " + field.getName() + " in "
//                                                + object.getClass().toGenericString() + " to value " + value.toString(), e);
//                        }
//                }
//        });
	}

	private <T> void createSubscribers(Method method, T object) throws CreationException {
		// create subscribers
		Subscribe subscribe = method.getAnnotation(Subscribe.class);
		if (subscribe != null) {
			RosJavaSubscriber<?> subscriber = createSubscriber(subscribe, object, method);
			synchronized (monitor) {
				subscribers.add(subscriber);
			}
		}
	}

	private <T> RosJavaSubscriber<?> createSubscriber(Subscribe subscribe, T object, Method method)
			throws CreationException {
		String topicName = subscribe.value();

		Parameter[] parameters = method.getParameters();
		if (parameters.length != 1) {
			throw new CreationException(
					"Subscriber at " + method.toGenericString() + " must have exactly one parameter");
		}

		Parameter parameter = parameters[0];
		final Class<?> topicType = parameter.getType();
		@SuppressWarnings("unchecked")
		Class<? extends MessageDefinition> topicTypeCasted = (Class<? extends MessageDefinition>) topicType;
		int timeout = subscribe.timeout();

		return new RosJavaSubscriber<>(composablenode.getNode(), object, method, topicName, topicTypeCasted, timeout,
				LOG);
	}

	private <T> void collectRepeaters(Method method, T object) throws CreationException {
		Repeat repeat = method.getAnnotation(Repeat.class);
		if (repeat != null) {
			synchronized (monitor) {
				repeaters.add(new Repeater(object, method, repeat));
			}
		}
	}

	private <T> void collectInitializers(Method method, T object) throws CreationException {
		Init init = method.getAnnotation(Init.class);
		if (init != null) {
			synchronized (monitor) {
				initializers.add(new Initializer(object, method, init));
			}
		}
	}

	private Publisher<?> createPublisher(Publish publish, Field field) {
		Type type = field.getGenericType();
		Type[] typeArgs = ((ParameterizedType) type).getActualTypeArguments();

		Class<?> topicType = getGenericParameterType(typeArgs[0]);
		@SuppressWarnings("unchecked")
		Class<? extends MessageDefinition> topicTypeCasted = (Class<? extends MessageDefinition>) topicType;

		if (topicType == null) {
			throw new UnsupportedClassVersionError(
					"Unrecognized type parameter for publisher at " + field.toGenericString());
		}

		String topicName = publish.value();
		return composablenode.getNode().createPublisher(topicTypeCasted, topicName);
	}

	private Class<?> getGenericParameterType(Type param) {
		Class<?> topicType = null;
		if (param instanceof Class) {
			topicType = (Class<?>) param;
		} else if (param instanceof ParameterizedType) {
			Type rawType = ((ParameterizedType) param).getRawType();
			if (rawType instanceof Class) {
				topicType = (Class<?>) rawType;
			}
		}
		return topicType;
	}

	private void makeAccessible(Field field) {
		if (!Modifier.isPublic(field.getModifiers())) {
			field.setAccessible(true);
		}
	}

}
