/*
* aidl file : frameworks/base/core/java/android/os/IOurService.aidl
* This file Contains definitions of Interface to interact with our services
*
*/

package android.os;

import android.view.MotionEvent;
import android.view.KeyEvent;

interface IDudiManagerService{
	boolean registerCurrentTopActivity();
	boolean unregisterSavedInfo();
	String getCurrentRealActivityName();
	String inquireSomething(String type);
	boolean checkCurrentActivitySpecial(String realName,int taskid);

	// check current topmost activity
	void topmostActivityChanged(String name);
	// input event injection
	void sendCurrentActivityToTouchEvent(in MotionEvent event);
	void sendCurrentActivityToKeyEvent(in KeyEvent event);
	// get window and input focus
	boolean acquireInputFocus();
	boolean releaseInputFocus();
	boolean isTargetAcquiringFocus();
	// activity change
	boolean notifyActivityChanged(String realName,boolean isfinish,boolean isNew);
	// DudiFloatService
	void bindWithFloatService();
	void unbindWithFloatService();
	
}
