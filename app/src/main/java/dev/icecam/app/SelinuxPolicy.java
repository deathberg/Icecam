package dev.icecam.app;

/**
 * Live SELinux policy patches for IceCam on KernelSU/Magisk devices.
 */
public final class SelinuxPolicy {
    private SelinuxPolicy() {}

    private static final String[] RULES = {
            "allow untrusted_app default_android_service service_manager find",
            "allow untrusted_app default_android_service binder call",
            "allow untrusted_app default_android_service binder transfer",
            "allow untrusted_app default_android_service binder use",
            "allow untrusted_app_30 default_android_service service_manager find",
            "allow untrusted_app_30 default_android_service binder call",
            "allow untrusted_app_30 default_android_service binder transfer",
            "allow untrusted_app_30 default_android_service binder use",
            "allow ksu default_android_service service_manager add",
            "allow ksu servicemanager binder call",
            "allow ksu servicemanager binder transfer",
            "allow ksu system_data_file file execute",
            "allow ksu system_data_file process execute",
            "allow su default_android_service service_manager add",
            "allow su servicemanager binder call",
            "allow su servicemanager binder transfer",
            "allow init default_android_service service_manager add",
            "allow system_server default_android_service service_manager find",
    };

    public static String applyLivePoliciesScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("apply_icecam_rule() {\n");
        sb.append("  rule=\"$1\"\n");
        sb.append("  [ -z \"$rule\" ] && return 1\n");
        sb.append("  if magiskpolicy --live \"$rule\" 2>/dev/null; then echo \"selinux ok magisk: $rule\"; return 0; fi\n");
        sb.append("  if supolicy --live \"$rule\" 2>/dev/null; then echo \"selinux ok supolicy: $rule\"; return 0; fi\n");
        sb.append("  if ksud supolicy --live \"$rule\" 2>/dev/null; then echo \"selinux ok ksud: $rule\"; return 0; fi\n");
        sb.append("  echo \"selinux skip: $rule\"\n");
        sb.append("  return 1\n");
        sb.append("}\n");
        sb.append("echo ---selinux-before---\n");
        sb.append("getenforce 2>/dev/null || true\n");
        sb.append("setenforce 0 2>/dev/null || true\n");
        for (String rule : RULES) {
            sb.append("apply_icecam_rule ").append(Shell.q(rule)).append("\n");
        }
        return sb.toString();
    }
}
