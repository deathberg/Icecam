// RECONSTRUCTED — vcplax Binder dispatcher model

#pragma once

#include <binder/BBinder.h>
#include <binder/Parcel.h>

namespace icecam::recon {

static constexpr const char* kDescriptor = "com.xiaomi.vlive.IMyBinderService";

class VcplaxBinderService : public android::BBinder {
public:
    android::status_t onTransact(uint32_t code, const android::Parcel& data,
                                 android::Parcel* reply, uint32_t flags) override;

private:
    android::status_t txPlaySource(const android::Parcel& data, android::Parcel* reply);
    android::status_t txSetModeString(const android::Parcel& data, android::Parcel* reply);
    android::status_t txSetTransform(const android::Parcel& data, android::Parcel* reply);
    android::status_t txStatus(android::Parcel* reply) const;
};

int start_vcplax_daemon(const char* service_name);

}  // namespace icecam::recon
