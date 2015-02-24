#define LOG_TAG "DudiManagerService"

#include "jni.h"
#include "JNIHelp.h"

#include <utils/Log.h>
#include <utils/Trace.h>
#include <utils/String8.h>
#include <utils/String16.h>

#include <ui/Fence.h>
#include <gui/ISurfaceComposer.h>
#include <binder/IServiceManager.h>


namespace android{

	static void com_android_server_DudiManagerService_setTargetActivityName(JNIEnv *env,jobject,jstring name)
	{
		sp<IServiceManager> sm = defaultServiceManager();
	
		sp<IBinder> b;
	
		sp<ISurfaceComposer> iSurfaceComposer;
		
		b = sm->getService(String16("SurfaceFlinger"));
		
		if(b == 0)
		{
			ALOGE("Cannot find SurfaceFlinger");
			return;
		}
		
		ALOGI("SurfaceFlinger is attached");
		//printf("DudiLowManagerService is Working\n");
		
		const char* nativeString = (env)->GetStringUTFChars(name,0);
		
		ALOGI("ActivityName : %s",nativeString);
		
		iSurfaceComposer = interface_cast<ISurfaceComposer>(b);
		
		ALOGI("Interface casted");
		
		iSurfaceComposer->setTargetActivityName(nativeString);
		
		(env)->ReleaseStringUTFChars(name,nativeString);
	}


	static JNINativeMethod gMethods[] = {
	    { "setTargetActivityName", "(Ljava/lang/String;)V", (void*) com_android_server_DudiManagerService_setTargetActivityName },};

	int register_android_server_DudiManagerService(JNIEnv* env) 
	{
            return jniRegisterNativeMethods(env, "com/android/server/DudiManagerService", gMethods, NELEM(gMethods));
	}

};
