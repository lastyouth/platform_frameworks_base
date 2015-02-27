package com.android.server;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.display.WifiDisplayStatus;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.IDudiFloatService;
import android.util.Log;
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
	private ActivityRecord current;
	private boolean isAcquired;
	
	// Informations
	private TaskRecord mTargetTaskRecord; // Registered TaskRecord
	private ActivityRecord mCurrentActivityRecord; // current
	private Stack<ActivityRecord> mRegisteredActivityStack;
	private boolean isRegistered; // if register
	
	private static native void setTargetActivityName(String name);
	
	// communicate with DudiFloatService
	private IDudiFloatService dudiFloatService;
	
	ServiceConnection dudiFloatConnection = new ServiceConnection()
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
		mContext.getSharedPreferences("dudi", 0).edit().putBoolean("state", false).apply();
		mWorker = new OurWorkerThread("DudiManagerServiceServiceWorker");
		mWorker.start();
		
		mActivityManagerService = ActivityManagerService.self();
		mSupervisor = mActivityManagerService.getSupervisor();
		mWindowManagerService = mActivityManagerService.getWindowManagerService();
		
		mInputManagerService = mWindowManagerService.getInputManagerService();
		
		mInputMonitor = mWindowManagerService.getInputMonitor();
		
		mRegisteredActivityStack = new Stack<ActivityRecord>();
		
		isRegistered = false;
		isAcquired = false;

		Log.i(TAG,"Spawned worker thread!!!");
	
	}
	public boolean registerCurrentTopActivity()
	{
		if(isRegistered)
		{
			// if this, unregister for switching
			unregisterSavedInfo();
		}
		ActivityRecord r = mSupervisor.topRunningActivityLocked();
		
		if(r == null)
		{
			return false;
		}
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
	public boolean unregisterSavedInfo()
	{
		if(!isRegistered)
		{
			return false;
		}
		// clear current target activity state ( RESUMED to STOPPED 
		
		mSupervisor.pauseForReregisteration(mCurrentActivityRecord);
		
		mTargetTaskRecord.getStack().makeStopAndInvisible(mCurrentActivityRecord);
		
		// clear information
		mCurrentActivityRecord = null;
		setTargetActivityName("");
		mTargetTaskRecord = null;
		isRegistered = false;
		mRegisteredActivityStack.clear();
		
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
			current = mSupervisor.topRunningActivityLocked();
		
			if(mCurrentActivityRecord == null)
			{
				return;
			}
			// registered activity and actual activity are same
			if(mCurrentActivityRecord == current)
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
		
		current = mSupervisor.topRunningActivityLocked();
		
		if(mCurrentActivityRecord == null)
		{
			return false;
		}
		// registered activity and actual activity are same
		
		if(mCurrentActivityRecord == current)
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
		WindowState originalWindow = mWindowManagerService.findAppWindowToken(current.getAppToken().asBinder()).findMainWindow();
		mActivityManagerService.sbhSetFocusedActivityLocked(current,originalWindow);
		
		current = null;
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
					unregisterSavedInfo();
					
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
	// Current WifiDisplayStatus object
	public void setCurrentWFDStatus(WifiDisplayStatus wfdstatus)
	{
		Log.d(TAG,"WifiDisplayStatus changed : "+wfdstatus);
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
