package com.android.server;

import java.io.Serializable;

public class MotionEventWrapper implements Serializable{
	/**
	 * 
	 */
	private static final long serialVersionUID = -1460443519930179975L;
	public static final int TYPE_MOTION = 0x10000;
	public static final int TYPE_KEY = 0x10001;
	private float X;
	private float Y;
	private int metaData;
	private int action;
	private int type;
	private int keycode;
	
	public MotionEventWrapper(float x,float y,int meta,int act)
	{
		X = x;
		Y = y;
		metaData = meta;
		action = act;
	}

	public float getX() {
		return X;
	}

	public float getY() {
		return Y;
	}

	public int getMetaData() {
		return metaData;
	}

	public int getAction() {
		return action;
	}

	public int getType() {
		return type;
	}

	public int getKeycode() {
		return keycode;
	}

	public void setX(float x) {
		X = x;
	}

	public void setY(float y) {
		Y = y;
	}

	public void setMetaData(int metaData) {
		this.metaData = metaData;
	}

	public void setAction(int action) {
		this.action = action;
	}

	public void setType(int type) {
		this.type = type;
	}

	public void setKeycode(int keycode) {
		this.keycode = keycode;
	}
	
}
