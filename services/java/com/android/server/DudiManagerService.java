package com.android.server;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;

import com.android.server.am.ActivityManagerService;
import com.android.server.am.ActivityRecord;
import com.android.server.am.ActivityStackSupervisor;
import com.android.server.am.TaskRecord;
import com.android.server.input.InputManagerService;
import com.android.server.wm.AppWindowToken;
import com.android.server.wm.InputMonitor;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowState;
import com.example.mira4u.MotionEventWrapper;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.hardware.input.InputManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.IDudiFloatService;
import android.util.Base64;
import android.util.Log;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.os.IDudiManagerService;


public class DudiManagerService extends IDudiManagerService.Stub{
	private static final String TAG = "DudiManagerService";
	private OurWorkerThread mWorker;
	private OurWorkerHandler mHandler;
	private Context mContext;
	//For Activity Stack, state
	private final ActivityManagerService mActivityManagerService;
	private final ActivityStackSupervisor mSupervisor;
	//For inputFocus, Event Injection
	private final WindowManagerService mWindowManagerService;
	private final InputManagerService mInputManagerService;
	private final InputMonitor mInputMonitor;
	private ActivityRecord mCurrentInputFocusedActivity;
	private boolean isAcquired;
	
	// For WifiDisplayStatus
	private final DisplayManager mDisplayManagerService;
	private WifiDisplayStatus mCurrentWifiDisplayStatus;
	
	// For WifiManager
	private final WifiManager mWifiManager;
	
	// Informations
	private TaskRecord mTargetTaskRecord; // Registered TaskRecord
	private ActivityRecord mCurrentActivityRecord; // current
	private Stack<ActivityRecord> mRegisteredActivityStack;
	private boolean isRegistered; // if register
	private final Object mLock = new Object();
	private boolean unregistering;
	
	private static native void setTargetActivityName(String name);
	private static native String getEncodedInputEvent();
	
	// for pulling
	
	private InputEventPullingThread mPullingThread;
	
	// communicate with DudiFloatService
	private IDudiFloatService dudiFloatService;
	
	private ServiceConnection dudiFloatConnection = new ServiceConnection()
	{

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			// TODO Auto-generated method stub
			
			dudiFloatService = IDudiFloatService.Stub.asInterface(service);
			Log.i(TAG,"dudiFloatService is now available");
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			// TODO Auto-generated method stub
			
		}
		
	};
	
	// Float Service Interface
	public void bindWithFloatService()
	{
		Intent filter = new Intent("com.android.service.MyService");
		mContext.bindService(filter,dudiFloatConnection,Context.BIND_AUTO_CREATE);
	}
	
	public void unbindWithFloatService()
	{
		// unregister must be preceed;
		unregisterSavedInfo(false);
		mContext.unbindService(dudiFloatConnection);
		dudiFloatService = null;
		Log.e(TAG,"dudiFloatService is unbinded");
	}
	
	
	public DudiManagerService(Context context)
	{
		super();
		//
		//System.loadLibrary("android_servers");
		//initializations
		mContext = context;
		// State of dudiFloatservice is false, when boot finished
		mContext.getSharedPreferences("dudi", 0).edit().putBoolean("state", false).apply();
		
		
		mWorker = new OurWorkerThread("DudiManagerServiceServiceWorker");
		mWorker.start();
		
		// Get ActivityManagerService
		mActivityManagerService = ActivityManagerService.self();
		
		// Get ActivityStackSupervisor
		mSupervisor = mActivityManagerService.getSupervisor();
		
		// Get WindowManagerService
		mWindowManagerService = mActivityManagerService.getWindowManagerService();
		
		// Get InputManagerService
		mInputManagerService = mWindowManagerService.getInputManagerService();
		
		// Get InputMonitor
		mInputMonitor = mWindowManagerService.getInputMonitor();
		
		// Get DisplayManagerService
		mDisplayManagerService = (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
		
		// Get WifiManager
		
		mWifiManager = (WifiManager)mContext.getSystemService(Context.WIFI_SERVICE);
		
		// This is stack for trace current registered activity.
		mRegisteredActivityStack = new Stack<ActivityRecord>();
		
		// State of registeration
		isRegistered = false;
		
		// Flag of unregisteration
		unregistering = false;
		
		// State of acquiring input focus
		isAcquired = false;
		
		// Initialize first state of WifiDisplayStatus
		mCurrentWifiDisplayStatus = mDisplayManagerService.getWifiDisplayStatus();
		
		// BroadcastReceiver Activated
		IntentFilter filter = new IntentFilter();
        filter.addAction(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
        filter.addAction("android.net.wifi.WIFI_STATE_CHANGED");
        filter.addAction("android.net.wifi.STATE.CHANGE");
        
        mContext.registerReceiver(mReceiver, filter);
        
        // Instantiate Pulling Thread
        
        mPullingThread = new InputEventPullingThread();
        
        mPullingThread.start();
        

		Log.i(TAG,"Spawned DudiManagerService thread!!!");
	}
	public boolean registerCurrentTopActivity()
	{
		ActivityRecord r = mSupervisor.topRunningActivityLocked();
		if(r.getRealActivity().contains("launcher3"))
		{
			return false;
		}
		
		if(isRegistered)
		{
			// if this, unregister for switching
			unregisterSavedInfo(false);
		}
		
		if(r == null)
		{
			return false;
		}
		// prevent register launcher app
		
		mCurrentActivityRecord = r;
		setTargetActivityName(mCurrentActivityRecord.getRealActivity());
		mTargetTaskRecord = r.getTask();
		
		for(ActivityRecord history : mTargetTaskRecord.getmActivities())
		{
			mRegisteredActivityStack.push(history);
		}
		
		Log.i(TAG,"registerCurrentTopActivity success current Activity "+mCurrentActivityRecord);
		isRegistered = true;
		
		isAcquired = false;
		mSupervisor.resumeHomeActivity(null);
		return true;
	}
	public boolean unregisterSavedInfo(boolean mustbedestroyed)
	{
		if(!isRegistered)
		{
			return false;
		}
		// clear current target activity state ( RESUMED to STOPPED 
		synchronized(mLock)
		{
			unregistering = true;
		}
		mTargetTaskRecord.getStack().makeStopAndInvisible(mCurrentActivityRecord,mustbedestroyed);
		mSupervisor.pauseForReregisteration(mCurrentActivityRecord);
		// makeStopAndInvisible must not be invoked.
		// clear information
		mCurrentActivityRecord = null;
		setTargetActivityName("");
		mTargetTaskRecord = null;
		isRegistered = false;
		mRegisteredActivityStack.clear();
		
		synchronized(mLock)
		{
			unregistering = false;
		}
		return true;
	}
	public String getCurrentRealActivityName()
	{
		Log.i(TAG,"getCurrentRealActivityName called");
		if(mCurrentActivityRecord == null)
		{
			return "";
		}
		return mCurrentActivityRecord.getRealActivity();
	}
	public boolean checkCurrentActivitySpecial(String realName,int taskid)
	{
		// if Activity Class name is same, it is special activity
		if(!isRegistered)
		{
			// not registered, invalid
			return false;
		}
		// this is for updating, new information or deleting
		synchronized(mLock)
		{
			if(unregistering)
			{
				return false;
			}
		}
		if(realName == null)
		{
			return false;
		}
		if(realName.equals(mCurrentActivityRecord.getRealActivity())
				&& mTargetTaskRecord.getTaskId() == taskid)
		{
			// package, classname and task id must be same
			return true;
		}
		// not matched, invalid
		return false;
	}
	public void topmostActivityChanged(String name)
	{
		/*if(isRegistered)
		{
			if(mCurrentActivityRecord.getRealActivity().equals(name))
			{
				// prevent make foreground current registered activity
				mSupervisor.resumeHomeActivity(null);
			}
		}*/
		// this method is called by ActivityStack.resumTopActivityLocked
		// param is Current Topmost real activity name
		try
		{
			if(dudiFloatService != null)
			{
				// notify to dudifloatservice
				dudiFloatService.notifyCurrentTopActivityName(name);
			}
		}catch(RemoteException e)
		{
			
		}
	}
	// test method
	public String inquireSomething(String type)
	{
		String ret = "null";
		
		if(type.equals("gettopactivity"))
		{
			ActivityRecord r = mSupervisor.topRunningActivityLocked();
			
			if(r != null)
			{
				ret = String.format("Current Packagename : %s Taskid : %d ActivateState : %s", r.getPackageName(),r.getTask().getTaskId(),r.getState());
			}
		}else if(type.equals("getrealactivity"))
		{
			if(isRegistered)
			{
				ret = mCurrentActivityRecord.getRealActivity();
			}
		}
		return ret;
	}
	private class OurWorkerThread extends Thread{
		public OurWorkerThread(String name)
		{
			super(name);
		}
		public void run()
		{
			Looper.prepare();
			mHandler = new OurWorkerHandler();
			Looper.loop();
		}
	}
	public void sendCurrentActivityToTouchEvent(MotionEvent event)
	{
		// 20150214
		// TouchEvent doesn't reach to our registered activity.
		// even though, input window and focus has been changed.
		
		// touch event dispatch mechanism is different with Key's
		
		// Must inspect InputDispatcher.cpp or lower
		mInputManagerService.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
		
	}
	// test function : just toggle input focus
	private void testFunc_1()
	{
		// just acquire application and input focus
		if(!isAcquired)
		{
			mCurrentInputFocusedActivity = mSupervisor.topRunningActivityLocked();
		
			if(mCurrentActivityRecord == null)
			{
				return;
			}
			// registered activity and actual activity are same
			if(mCurrentActivityRecord == mCurrentInputFocusedActivity)
			{
				return;
			}
			acquireInputFocus();
		}
		else
		{
			releaseInputFocus();
		}
	}
	// inject key event to current registered activity
	public void sendCurrentActivityToKeyEvent(KeyEvent event)
	{
		// key event consists of ACTION_DOWN and ACTION_UP pairly
		if(acquireInputFocus())
		{
			// inject Key event
			mInputManagerService.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
			// Just up event
			event.mAction = KeyEvent.ACTION_UP;
			// inject again
			mInputManagerService.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
			Log.e("WindowManager","inject finished");
			
			releaseInputFocus();
		}
	}
	// get input and window focus for current registered activity
	public boolean acquireInputFocus()
	{
		if(isAcquired)
		{
			return false;
		}
		
		mCurrentInputFocusedActivity = mSupervisor.topRunningActivityLocked();
		
		if(mCurrentActivityRecord == null)
		{
			return false;
		}
		// registered activity and actual activity are same
		
		if(mCurrentActivityRecord == mCurrentInputFocusedActivity)
		{
			return false;
		}
		
		// get application token
		IApplicationToken registeredActivityToken = mCurrentActivityRecord.getAppToken();
		
		// application token to WindowToken
		AppWindowToken targetWindowToken = mWindowManagerService.findAppWindowToken(registeredActivityToken.asBinder());
		
		Log.i(TAG,"Registered Activity windowToken : "+targetWindowToken.toString());
		
		// windowToken to WindowState
		WindowState targetWindow = targetWindowToken.findMainWindow();
		
		Log.i(TAG,"Target Windowstate : "+targetWindow+"canReceiveKeys : "+targetWindow.canReceiveKeys());
		
		// change Activity focus for injecting event
		mActivityManagerService.sbhSetFocusedActivityLocked(mCurrentActivityRecord,targetWindowToken.findMainWindow());
		
		// change input focus to registered activity's main window
		isAcquired = true;
		Log.e("WindowManager","acquireSuccess");
		return true;
	}
	public boolean releaseInputFocus()
	{
		if(!isAcquired)
		{
			return false;
		}
		WindowState originalWindow = mWindowManagerService.findAppWindowToken(mCurrentInputFocusedActivity.getAppToken().asBinder()).findMainWindow();
		mActivityManagerService.sbhSetFocusedActivityLocked(mCurrentInputFocusedActivity,originalWindow);
		
		mCurrentInputFocusedActivity = null;
		isAcquired = false;
		Log.e("WindowManager","releaseSuccess");
		return true;
	}
	// just getter
	public boolean isTargetAcquiringFocus()
	{
		return isAcquired;
	}
	// check if candidate activity is validate in current task
	private ActivityRecord searchActivityFromCurrentTask(String realName)
	{
		ActivityRecord ret = null;
		
		ArrayList<ActivityRecord> p = mTargetTaskRecord.getmActivities();
		
		for(int i = 0;i<p.size();i++)
		{
			ActivityRecord r = p.get(i);
			
			String realActivityName = r.getRealActivity();
			
			if(realActivityName != null && realActivityName.equals(realName))
			{
				ret = r;
				break;
			}
		}
		return ret;
	}
	private WifiDisplay findWifiDisplay(String deviceAddress) {
		//mDisplayManagerService.
		WifiDisplayStatus tempWds = mDisplayManagerService.getWifiDisplayStatus();
		
        if (tempWds != null && deviceAddress != null) {
            for (WifiDisplay display : tempWds.getDisplays()) {
            	Log.d(TAG,"Candidate Display : "+display);
                if (display.getDeviceAddress().equals(deviceAddress)) {
                    return display;
                }
            }
        }
        return null;
    }
	public void activateConnectToSink()
	{
		String deviceID = "fa:a9:d0:51:ab:14";
		
		WifiDisplay wd;
		
		do
		{
			wd = findWifiDisplay(deviceID);
			
			Log.d(TAG,"Target Display : "+wd);
			
		}while(wd == null);
		
		if(wd.canConnect())
		{
			mDisplayManagerService.connectWifiDisplay(wd.getDeviceAddress());
		}
	}
	//
	public boolean notifyActivityChanged(String realName, boolean isFinish,boolean isNew)
	{
		// update registered activity stack,
		// create -> push
		// finish -> pop
		if(!isRegistered)
		{
			// if unregistered, nothing to do.
			return false;
		}
		if(realName == null)
		{
			return false;
		}
		if(isFinish)
		{
			if(mRegisteredActivityStack.size() == 1)
			{
				if(realName.equals(mCurrentActivityRecord.getRealActivity()))
				{
					//ActivityRecord tempR = mCurrentActivityRecord;
					//TaskRecord tempT = mTargetTaskRecord;
					unregisterSavedInfo(true);
					
					//tempT.getStack().makeStopAndInvisible(tempR);
					
				}
				return false;
			}
			// finish
			ActivityRecord p = searchActivityFromCurrentTask(realName);
			
			if(p == null)
			{
				return false;
			}
			ActivityRecord currentTop = mRegisteredActivityStack.peek();
			
			if(currentTop.getRealActivity().equals(realName))
			{
				// pop
				mRegisteredActivityStack.pop();
				// peek
				mCurrentActivityRecord = mRegisteredActivityStack.peek();
				// change
				setTargetActivityName(mCurrentActivityRecord.getRealActivity());
				Log.e("sbhdebug","finishActivity new top activity : "+mCurrentActivityRecord);
				
				mTargetTaskRecord.getStack().makeVisible(mCurrentActivityRecord);
				
				return true;
			}
			return false;
		}
		else
		{
			// create
			if(isNew)
			{
				// ignore, user just start another activity, which is NOT unregistered
				return false;
			}
			ActivityRecord target = searchActivityFromCurrentTask(realName);
			
			if(target == null)
			{
				return false;
			}
			// pop
			mRegisteredActivityStack.push(target);
			
			mCurrentActivityRecord = target;
			// change
			setTargetActivityName(mCurrentActivityRecord.getRealActivity());
			Log.e("sbhdebug","startactivity : new top activity : "+mCurrentActivityRecord);
			return true;
		}
	}
	// Input Event from sink device
	public boolean sendBase64EncodedInputEvent(String encodedEvent)
	{
		Log.d(TAG,"sendBase64EncodedInputEvent invoked : "+encodedEvent);
		return true;
	}
	public int getOverallWifiStatus()
	{
		// return overall wifi status by priority
		int ret = 0x0;
		
		return ret;
	}
	
	private void updateWifiStatus()
	{
		//Check Wifi status and notify to DudiFloatService
		int wififlag = mWifiManager.getWifiState();
		
		
		
	}
	
	// Broadcast receiver for wifi relevant status
	private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED)) {
            	mCurrentWifiDisplayStatus = mDisplayManagerService.getWifiDisplayStatus();
            	
            	Log.d(TAG,"WifiDisplayStatus changed : "+mCurrentWifiDisplayStatus);
            	
            }else if(action.equals("android.net.wifi.WIFI_STATE_CHANGED"))
            {
            	Log.d(TAG,"WIFI_STATE_CHANGED received");
            }else if(action.equals("android.net.wifi.STATE_CHANGE"));
            {
            	Log.d(TAG,"STATE_CHANGED received");
            }
            updateWifiStatus();
        }
    };
    // etc
    private class InputEventPullingThread extends Thread
    {
    	public InputEventPullingThread()
    	{
    		
    	}
    	
    	@Override
    	public void run()
    	{
    		while(true)
    		{
    			String ret = getEncodedInputEvent();
    			
    			if(!ret.equals("null"))
    			{
    				Log.i(TAG,"Encoded String : "+ret);
    				
    				ret = ret.substring(0,ret.indexOf("@@@"));
    				
    				Log.d(TAG,"Verified String : "+ret);
    				
    				
    				
    				try
    				{
    					byte[] encoded = Base64.decode(ret, Base64.NO_WRAP);
        				
        				Log.i(TAG,"Decoded byte array : "+encoded);
        				
    					ByteArrayInputStream inByteStream;
    					ObjectInput objinStream;
    					
    					inByteStream = new ByteArrayInputStream(encoded);
    					objinStream = new ObjectInputStream(inByteStream);
    						
    					MotionEventWrapper p = (MotionEventWrapper)objinStream.readObject();
    					
    					Log.i(TAG,"Object : "+p.toString());
    					
    					int type = p.getType();
    					
    					if(type == MotionEventWrapper.TYPE_KEY)
    					{
    						int keycode = p.getKeycode();
    						
    						if(keycode == KeyEvent.KEYCODE_BACK)
    						{
    							Log.i(TAG,"Back Key Event Arrived");
    							KeyEvent backKeyDownEvent = new KeyEvent(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),
    									KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0,0,-1,0,0x48,0x101);
    							sendCurrentActivityToKeyEvent(backKeyDownEvent);
    						}else if(keycode == KeyEvent.KEYCODE_MENU)
    						{
    							Log.i(TAG,"Home Key Event Arrived");
    							int taskid = mCurrentActivityRecord.getTask().getTaskId();
    							
    							registerCurrentTopActivity();
    							
    							mActivityManagerService.moveTaskToFront(taskid, 0, null);
    							
    						}else if(keycode == KeyEvent.KEYCODE_HOME)
    						{
    							unregisterSavedInfo(true);
    							Log.i(TAG,"Menu Key Event Arrived");
    						}
    						else
    						{
    							Log.e(TAG,"Unexpected Key Layout");
    						}
    						
    					}else if(type == MotionEventWrapper.TYPE_MOTION)
    					{
    						Log.i(TAG,"Motion Event Arrived");
    						//MotionEvent me = MotionEvent.obtain(System.currentTimeMillis(),System.currentTimeMillis(),p.getAction(),p.getX(),p.getY(),p.getMetaData());
    						MotionEvent.PointerCoords[] pc = MotionEvent.PointerCoords.createArray(1);
    						MotionEvent.PointerProperties[] pp = MotionEvent.PointerProperties.createArray(1);
    						
    						pc[0].clear();
    						
    						pc[0].x = p.getX();
    						pc[0].y = p.getY();
    						pc[0].pressure = MotionEvent.AXIS_PRESSURE;
    						pc[0].size = MotionEvent.AXIS_SIZE;
    						
    						pp[0].clear();
    						pp[0].id = 4;
    						pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
    						
    						MotionEvent event = MotionEvent.obtain(android.os.SystemClock.uptimeMillis(),
    								android.os.SystemClock.uptimeMillis(),
    								p.getAction(),
    								1,
    								pp,
    								pc,
    								0,
    								0,
    								(float)p.getX(),(float)p.getY(),4,0,0x1002,0);	
    						sendCurrentActivityToTouchEvent(event);
    						//sendCurrentActivityToTouchEvent(me);
    					}
    					else
    					{
    						// unexpected;
    						Log.e(TAG,"unexpected object from sink");
    					}

    					//recovered = MotionEvent.obtain(System.currentTimeMillis(),System.currentTimeMillis(),p.getAction(),p.getX(),p.getY(),p.getMetaData());
    					
    					//Log.e(TAG,"RECOVERED : "+recovered);
    				}catch(IOException ee)
    				{
    					//Log.e(TAG,"Exception : "+ee.p);
    					ee.printStackTrace();
    				}catch(Exception e)
    				{
    					//Log.e(TAG,"Exception : "+e.getMessage());
    					e.printStackTrace();
    				}
    				
    			}
    			else
    			{
    				try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
    			}
    		}
    	}
    }
	
	private class OurWorkerHandler extends Handler{
		private static final int MESSAGE_SET = 0;
		@Override
		public void handleMessage(Message msg)
		{
			try
			{
				if(msg.what == MESSAGE_SET)
				{
					Log.i(TAG,"set message received: "+msg.arg1);
				}
			}catch(Exception e)
			{
				Log.e(TAG,"Exception in OurWorkerHandler.handleMessage:",e);
			}
		}
	}
}
