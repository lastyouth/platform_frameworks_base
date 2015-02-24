/* DudiFloatService.aidl - Settings for dudi Service,
	This interface is for outer approaching by DudiManagerService
*/

package android.os;

interface IDudiFloatService{
	void notifyCurrentTopActivityName(String name);
}