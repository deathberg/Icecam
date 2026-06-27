// RECONSTRUCTED — vcplax daemon entry and cameraserver injection model

#include "binder_service.h"
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <unistd.h>
#include <dlfcn.h>
#include <cstring>

namespace icecam::recon {

static bool inject_library(pid_t pid, const char* lib_path) {
    // Prebuilt vcplax uses ptrace + dlopen remote call into cameraserver.
    // Exact register choreography is device/ABI specific.
    (void)pid; (void)lib_path;
    return false;
}

static bool inject_cameraserver() {
    pid_t pid = 0; // resolve via pidof cameraserver
    (void)pid;
    return inject_library(pid, "/data/libvc.so")
        && inject_library(pid, "/data/libvc++.so");
}

int start_vcplax_daemon(const char* service_name) {
    android::ProcessState::self()->startThreadPool();
    android::sp<android::IServiceManager> sm = android::defaultServiceManager();
    android::sp<android::BBinder> svc = new VcplaxBinderService();
    sm->addService(android::String16(service_name), svc);
    inject_cameraserver();
    android::IPCThreadState::self()->joinThreadPool();
    return 0;
}

}  // namespace icecam::recon

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    return icecam::recon::start_vcplax_daemon(argv[1]);
}
