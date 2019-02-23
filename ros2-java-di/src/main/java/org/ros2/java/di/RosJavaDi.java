package org.ros2.java.di;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ros2.java.di.annotations.Init;
import org.ros2.java.di.annotations.Publish;
import org.ros2.java.di.annotations.Repeat;
import org.ros2.java.di.annotations.Subscribe;
import org.ros2.java.di.annotations.SystemClock;
import org.ros2.java.di.exceptions.CreationException;
import org.ros2.java.di.internal.Initializer;
import org.ros2.java.di.internal.ParameterReference;
import org.ros2.java.di.internal.Repeater;
import org.ros2.java.di.internal.RosJavaDiLog;
import org.ros2.java.di.internal.RosJavaSubscriber;
import org.ros2.java.di.internal.RosoutPublisher;
import org.ros2.java.maven.Ros2JavaLibraries;
import org.ros2.rcljava.RCLJava;
import org.ros2.rcljava.executors.SingleThreadedExecutor;
import org.ros2.rcljava.interfaces.MessageDefinition;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.node.Node;
import org.ros2.rcljava.parameters.ParameterCallback;
import org.ros2.rcljava.parameters.ParameterType;
import org.ros2.rcljava.parameters.ParameterVariant;
import org.ros2.rcljava.parameters.client.AsyncParametersClientImpl;
import org.ros2.rcljava.parameters.service.ParameterServiceImpl;
import org.ros2.rcljava.publisher.Publisher;
import org.yaml.snakeyaml.Yaml;

import rcl_interfaces.msg.SetParametersResult;

public class RosJavaDi {

	private static Log LOG = LogFactory.getLog(RosJavaDi.class);
	public static AtomicReference<RosoutPublisher> ROSOUT_PUBLISHER = new AtomicReference<>();

	private Object monitor = new Object();
	private Clock clock = new Clock();

	private ArrayList<Initializer> initializers = new ArrayList<>();
	private ArrayList<Repeater> repeaters = new ArrayList<>();
	private ArrayList<RosJavaSubscriber<?>> subscribers = new ArrayList<>();
	private ArrayList<ParameterReference> parameterReferences = new ArrayList<>();
	private HashMap<String, ParameterReference> parameterReferenceMap = new HashMap<>();

	private long contextHandle;
	private SingleThreadedExecutor executor;
	private BaseComposableNode composablenode;
	private Node node;
	@SuppressWarnings("unused")
	private ParameterServiceImpl parametersService;
	private AsyncParametersClientImpl asyncParametersClient;
	
	private Yaml yaml = new Yaml();

	public RosJavaDi(String name, String[] args) throws Exception {
		Ros2JavaLibraries.unpack();
		contextHandle = RCLJava.rclJavaInit(args);
		executor = new SingleThreadedExecutor();
		composablenode = new BaseComposableNode(name, args, true, contextHandle);
		node = composablenode.getNode();
		parametersService = new ParameterServiceImpl(composablenode.getNode());
		asyncParametersClient = new AsyncParametersClientImpl(composablenode.getNode(), contextHandle);
	}

	public void start() throws NoSuchFieldException, IllegalAccessException {
		// create logging publisher
		ROSOUT_PUBLISHER.set(new RosoutPublisher(node, clock));
		
		// get all the parameters
		for (ParameterReference ref : parameterReferences) {
			processParameterReference(ref);
		}

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

		// add callback on parameter change
		registerParameterChangeCallback();

		executor.addNode(composablenode);

		new Thread(() -> {
			while (RCLJava.ok(contextHandle)) {
				try {
					executor.spinOnce();
				} catch (Throwable t) {
					LOG.warn("Exception in executor.spinOnce()", t);
				}
			}
		}).start();
	}

	private void registerParameterChangeCallback() {
		composablenode.getNode().setParameterChangeCallback(new ParameterCallback() {
			@Override
			public SetParametersResult onParamChange(List<ParameterVariant> parameters) {
				try {
					for (ParameterVariant parameter : parameters) {
						LOG.info("Parameter callback: " + parameter.getName() + " " + parameter.getTypeName() + " "
								+ parameter.getValueAsString() + " " + parameter.getType().toString());
						ParameterReference ref = parameterReferenceMap.get(parameter.getName());
						if (ref == null) {
							LOG.warn("Unknown parameter: " + parameter.toString());
						} else {
							setParameterValueFromServer(ref.object, ref.field, parameter);
						}
					}
					SetParametersResult result = new SetParametersResult();
					result.setSuccessful(true);
					return result;
				} catch (Throwable t) {
					LOG.warn("Exception in parameter callback", t);
					SetParametersResult result = new SetParametersResult();
					result.setSuccessful(false);
					return result;
				}
			}
		});
	}

	public void shutdown() {
		executor.removeNode(composablenode);

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
				collectParameters(field, object);
				injectClock(field, object);
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

	private <T> void collectParameters(Field field, T object) throws CreationException, IllegalAccessException {
		// inject parameters
		org.ros2.java.di.annotations.Parameter parameter = field
				.getAnnotation(org.ros2.java.di.annotations.Parameter.class);
		if (parameter != null) {
			makeAccessible(field);

			String parameterName = parameter.value();
			ParameterReference ref = collectParameter(object, field, parameterName);
			parameterReferences.add(ref);
			parameterReferenceMap.put(ref.parameterName, ref);
		}
	}

	private <T> ParameterReference collectParameter(T object, Field field, String parameterName) {
		Future<List<ParameterVariant>> future = asyncParametersClient.getParameters(Arrays.asList(parameterName));
		return new ParameterReference(parameterName, object, field, future);

	}

	private void processParameterReference(ParameterReference ref) {
		ParameterVariant variant = null;
		try {
			List<ParameterVariant> variants = ref.future.get();
			if (variants.isEmpty()) {
				LOG.info("Missing parameter: " + ref.parameterName);
			} else {
				variant = variants.get(0);
			}
		} catch (InterruptedException | ExecutionException e) {
			LOG.info("Error getting: " + ref.parameterName, e);
		}
		if (variant == null) {
			publishParameter(ref.object, ref.field, ref.parameterName);
		} else {
			setParameterValueFromServer(ref.object, ref.field, variant);
		}
	}

	private <T> void publishParameter(T object, Field field, String parameterName) {
		try {
			Object value = field.get(object);
			Class<?> type = field.getType();

			if (Boolean.class.isAssignableFrom(type) || boolean.class.isAssignableFrom(type)) {
				if (value != null) {
					node.setParameters(
							Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, (Boolean) value)));
				} else {
					node.setParameters(Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, "")));
				}
			} else if (Integer.class.isAssignableFrom(type) || int.class.isAssignableFrom(type)) {
				if (value != null) {
					node.setParameters(
							Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, (Integer) value)));
				} else {
					node.setParameters(Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, "")));
				}
			} else if (Double.class.isAssignableFrom(type) || double.class.isAssignableFrom(type)) {
				if (value != null) {
					node.setParameters(
							Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, (Double) value)));
				} else {
					node.setParameters(Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, "")));
				}
			} else if (List.class.isAssignableFrom(type)) {
				if (value != null) {
					node.setParameters(
							Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, yaml.dump(value))));
				} else {
					node.setParameters(Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, "[]")));
				}
			} else if (Map.class.isAssignableFrom(type)) {
				if (value != null) {
					node.setParameters(
							Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, yaml.dump(value))));
				} else {
					node.setParameters(Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, "{}")));
				}
			} else { // if (String.class.isAssignableFrom(type)) {
				if (value != null) {
					node.setParameters(
							Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, value.toString())));
				} else {
					node.setParameters(Arrays.<ParameterVariant>asList(new ParameterVariant(parameterName, "")));
				}
			}
		} catch (IllegalArgumentException | IllegalAccessException e) {
			LOG.info("Error getting parameter value: " + parameterName, e);
		}
	}

	private <T> void setParameterValueFromServer(T object, Field field, ParameterVariant variant) {
		Class<?> type = field.getType();
		try {
			if (Boolean.class.isAssignableFrom(type) || boolean.class.isAssignableFrom(type)) {
				if (variant.getType() == ParameterType.PARAMETER_STRING) {
					field.set(object, Boolean.parseBoolean(variant.asString()));
				} else {
					field.set(object, variant.asBool());
				}
			} else if (Integer.class.isAssignableFrom(type) || int.class.isAssignableFrom(type)) {
				if (variant.getType() == ParameterType.PARAMETER_STRING) {
					field.set(object, (int) Double.parseDouble(variant.asString()));
				} else if (variant.getType() == ParameterType.PARAMETER_DOUBLE) {
					field.set(object, (int) variant.asDouble());
				} else {
					field.set(object, (int) variant.asInt());
				}
			} else if (Double.class.isAssignableFrom(type) || double.class.isAssignableFrom(type)) {
				if (variant.getType() == ParameterType.PARAMETER_STRING) {
					field.set(object, Double.parseDouble(variant.asString()));
				} else if (variant.getType() == ParameterType.PARAMETER_INTEGER) {
					field.set(object, (double) variant.asInt());
				} else {
					field.set(object, variant.asDouble());
				}
			} else if (String.class.isAssignableFrom(type)) {
				if (variant.getType() == ParameterType.PARAMETER_INTEGER) {
					field.set(object, Long.toString(variant.asInt()));
				} else if (variant.getType() == ParameterType.PARAMETER_DOUBLE) {
					field.set(object, Double.toString(variant.asDouble()));
				} else if (variant.getType() == ParameterType.PARAMETER_BOOL) {
					field.set(object, Boolean.toString(variant.asBool()));
				} else {
					field.set(object, variant.asString());
				}
			} else if (List.class.isAssignableFrom(type)) {
				field.set(object, yaml.loadAs(variant.asString(), ArrayList.class));
			} else if (Map.class.isAssignableFrom(type)) {
				field.set(object, yaml.loadAs(variant.asString(), HashMap.class));
			}
		} catch (NumberFormatException e) {
			LOG.error("Cannot set parameter " + field.getName() + " in " + object.getClass().getCanonicalName()
					+ ", wrong number format " + type + ", parameter: " + variant.getValueAsString() + " "
					+ variant.getTypeName(), e);
		} catch (IllegalArgumentException e) {
			LOG.error("Cannot set parameter " + field.getName() + " in " + object.getClass().getCanonicalName()
					+ ", incompatible types " + type + ", parameter: " + variant.getValueAsString() + " "
					+ variant.getTypeName() + ", for exmple consider maing it a List not an ArrayList", e);
		} catch (ClassCastException e) {
			LOG.error("Cannot set parameter " + field.getName() + " in " + object.getClass().getCanonicalName()
					+ ", incompatible types " + type + ", parameter: " + variant.getValueAsString() + " "
					+ variant.getTypeName() + ", for example consider maing it a List not an ArrayList", e);
		} catch (IllegalAccessException e) {
			LOG.error("Cannot set parameter " + field.getName() + " in " + object.getClass().getCanonicalName()
					+ ", illegal access " + type + ", parameter: " + variant.getValueAsString() + " "
					+ variant.getTypeName(), e);
		}

	}

	private <T> void injectClock(Field field, T object) throws IllegalArgumentException, IllegalAccessException {
		SystemClock systemClock = field.getAnnotation(SystemClock.class);
		if(systemClock!=null) {
			makeAccessible(field);
			field.set(object,systemClock);
		}
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
		return node.createPublisher(topicTypeCasted, topicName);
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
