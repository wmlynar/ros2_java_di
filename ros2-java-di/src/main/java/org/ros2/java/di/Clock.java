package org.ros2.java.di;

import builtin_interfaces.msg.Time;

public class Clock {
	
	public double now() {
		return ((double)System.nanoTime())/1000000.;
	}

	public Time timeNow() {
		long nano = System.nanoTime();
		Time t = new Time();
		t.setSec((int) (nano/1000000));
		t.setNanosec((int) (nano % 1000000));
		return t;
	}

}
