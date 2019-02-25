package org.ros2.java.di;

import builtin_interfaces.msg.Time;

public class Clock {

	public double now() {
		return ((double) System.currentTimeMillis()) / 1000.;
	}

	public Time timeNow() {
		long m = System.currentTimeMillis();
		Time t = new Time();
		t.setSec((int) (m / 1000L));
		t.setNanosec((int) (m % 1000L) * 1000);
		return t;
	}

}
