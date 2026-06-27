package dev.icecam.app;

/**
 * Best-effort live SELinux policy patches for IceCam on KernelSU/Magisk devices.
 *
 * Android 13+ often blocks untrusted_app from service_manager find on services
 * registered by the root daemon as default_android_service. Without these rules
 * Java ServiceManager.getService("privsam_service") fails even when vcplax is running.
 */
public final class SelinuxPolicy {
    private SelinuxPolicy() {}

    public static String applyLivePoliciesScript() {
        return ""
                + "apply_icecam_selinux() {\n"
                + "  RULES=\"\n"
                + "allow untrusted_app default_android_service service_manager find\n"
                + "allow untrusted_app default_android_service binder call\n"
                + "allow untrusted_app default_android_service binder transfer\n"
                + "allow untrusted_app default_android_service binder use\n"
                + "allow untrusted_app_30 default_android_service service_manager find\n"
                + "allow untrusted_app_30 default_android_service binder call\n"
                + "allow untrusted_app_30 default_android_service binder transfer\n"
                + "allow untrusted_app_30 default_android_service binder use\n"
                + "allow ksu default_android_service service_manager add\n"
                + "allow ksu servicemanager binder call\n"
                + "allow ksu servicemanager binder transfer\n"
                + "allow su default_android_service service_manager add\n"
                + "allow su servicemanager binder call\n"
                + "allow su servicemanager binder transfer\n"
                + "allow init default_android_service service_manager add\n"
                + "allow system_server default_android_service service_manager find\n"
                + "\"\n"
                + "  for rule in $RULES; do\n"
                + "    [ -z \"$rule\" ] && continue\n"
                + "    if magiskpolicy --live \"$rule\" 2>/dev/null; then echo \"selinux ok magisk: $rule\"; continue; fi\n"
                + "    if supolicy --live \"$rule\" 2>/dev/null; then echo \"selinux ok supolicy: $rule\"; continue; fi\n"
                + "    if ksud supolicy --live \"$rule\" 2>/dev/null; then echo \"selinux ok ksud: $rule\"; continue; fi\n"
                + "    echo \"selinux skip: $rule\"\n"
                + "  done\n"
                + "}\n"
                + "echo ---selinux-before---\n"
                + "getenforce 2>/dev/null || true\n"
                + "setenforce 0 2>/dev/null || true\n"
                + "apply_icecam_selinux\n";
    }
}
