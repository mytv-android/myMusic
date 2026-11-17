#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <mutex>
#include <new>
#include <unordered_set>

// QuickJS's public C ABI. The wrapper AAR exports this symbol on every packaged ABI.
using JSRuntime = void;
using JSInterruptHandler = int (*)(JSRuntime *, void *);
using SetInterruptHandler = void (*)(JSRuntime *, JSInterruptHandler, void *);

namespace {
struct InterruptState {
    JSRuntime *runtime;
    std::atomic<bool> interrupted{false};
};

std::mutex stateMutex;
std::unordered_set<InterruptState *> activeStates;

SetInterruptHandler findSetter() {
    void *library = dlopen("libquickjs-android-wrapper.so", RTLD_NOW | RTLD_NOLOAD);
    if (library == nullptr) return nullptr;
    return reinterpret_cast<SetInterruptHandler>(dlsym(library, "JS_SetInterruptHandler"));
}

int onInterrupt(JSRuntime *, void *opaque) {
    auto *state = static_cast<InterruptState *>(opaque);
    return state->interrupted.load(std::memory_order_relaxed) ? 1 : 0;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mytv_mymusic_lxruntime_LxQuickJsInterrupt_install(JNIEnv *, jclass, jlong runtime) {
    SetInterruptHandler setter = findSetter();
    if (setter == nullptr || runtime == 0) return 0;
    auto *state = new (std::nothrow) InterruptState{reinterpret_cast<JSRuntime *>(runtime)};
    if (state == nullptr) return 0;
    setter(state->runtime, onInterrupt, state);
    std::lock_guard<std::mutex> lock(stateMutex);
    activeStates.insert(state);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mytv_mymusic_lxruntime_LxQuickJsInterrupt_interrupt(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(stateMutex);
    auto *state = reinterpret_cast<InterruptState *>(handle);
    if (activeStates.find(state) != activeStates.end()) {
        state->interrupted.store(true, std::memory_order_relaxed);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mytv_mymusic_lxruntime_LxQuickJsInterrupt_release(JNIEnv *, jclass, jlong handle) {
    SetInterruptHandler setter = findSetter();
    std::lock_guard<std::mutex> lock(stateMutex);
    auto *state = reinterpret_cast<InterruptState *>(handle);
    auto found = activeStates.find(state);
    if (found == activeStates.end()) return;
    // Called on the QuickJS thread while the runtime is still alive, immediately before destroy.
    if (setter != nullptr) setter(state->runtime, nullptr, nullptr);
    activeStates.erase(found);
    delete state;
}
